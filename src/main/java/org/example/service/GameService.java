package org.example.service;

import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import org.example.config.ServerConfig;
import org.example.match.MatchQueue;
import org.example.match.Matchmaker;
import org.example.match.QueueEntry;
import org.example.model.Hand;
import org.example.model.PlayerData;
import org.example.model.RoomPhase;
import org.example.model.Thrust;
import org.example.msg.Json;
import org.example.msg.Messages;
import org.example.redis.RedisStore;
import org.example.room.BattleRoom;
import org.example.room.RoomManager;
import org.example.session.PlayerSession;
import org.example.session.SessionManager;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates login, matchmaking, input, ranking queries.
 */
public final class GameService implements AutoCloseable {

    private final RedisStore redis;
    private final SessionManager sessions = new SessionManager();
    private final MatchQueue matchQueue = new MatchQueue();
    private final RoomManager rooms;
    private final Matchmaker matchmaker;
    /** Wall-clock of last match scan inside gameLoop (match + rooms share one thread). */
    private long lastMatchScanMs;
    // gameLoop + queue heartbeat
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "battle-scheduler");
        t.setDaemon(true);
        return t;
    });

    public GameService(RedisStore redis) {
        this.redis = redis;
        this.rooms = new RoomManager(sessions, redis, this::onRoomClosed);
        this.matchmaker = new Matchmaker(matchQueue, this::onMatched);
        long tickMs = ServerConfig.tickIntervalMs();
        scheduler.scheduleAtFixedRate(this::gameLoop, tickMs, tickMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::broadcastQueueStatus, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Single game thread: optional match scan, then room ticks.
     * onMatched → create runs here too, so open-room and tickAll never race across threads.
     */
    private void gameLoop() {
        long now = System.currentTimeMillis();
        if (now - lastMatchScanMs >= ServerConfig.MATCH_SCAN_INTERVAL_MS) {
            lastMatchScanMs = now;
            matchmaker.run();
        }
        rooms.run();
    }

    public SessionManager sessions() {
        return sessions;
    }

    public void onChannelInactive(Channel channel) {
        PlayerSession session = sessions.getByChannel(channel);
        sessions.unbindChannel(channel);
        // Keep queue/room membership; reconnect with same username can resume.
        if (session != null) {
            System.out.println("[Session] channel closed user=" + session.getUsername());
        }
    }

    public void handleText(Channel channel, String text) {
        JsonObject obj;
        try {
            obj = Json.parseObject(text);
        } catch (Exception e) {
            send(channel, Messages.error("BAD_JSON", "消息不是合法 JSON"));
            return;
        }
        if (!obj.has("type")) {
            send(channel, Messages.error("MISSING_TYPE", "缺少 type 字段"));
            return;
        }
        String type = obj.get("type").getAsString();
        try {
            switch (type) {
                case "login" -> handleLogin(channel, obj);
                case "match_start" -> handleMatchStart(channel);
                case "match_cancel" -> handleMatchCancel(channel);
                case "input_hand" -> handleInputHand(channel, obj);
                case "input_thrust" -> handleInputThrust(channel, obj);
                case "query_ranking_top100" -> handleRanking(channel);
                case "query_me" -> handleQueryMe(channel);
                default -> send(channel, Messages.error("UNKNOWN_TYPE", "未知消息类型: " + type));
            }
        } catch (Exception e) {
            System.err.println("[GameService] handle error type=" + type + ": " + e.getMessage());
            e.printStackTrace(System.err);
            send(channel, Messages.error("INTERNAL", "服务端处理异常: " + e.getMessage()));
        }
    }

    private void handleLogin(Channel channel, JsonObject obj) {
        if (!obj.has("username")) {
            send(channel, Messages.error("BAD_USERNAME", "缺少 username"));
            return;
        }
        String username = obj.get("username").getAsString().trim();
        if (username.isEmpty() || username.length() > ServerConfig.MAX_USERNAME_LENGTH) {
            send(channel, Messages.error("BAD_USERNAME", "用户名长度须为 1-" + ServerConfig.MAX_USERNAME_LENGTH));
            return;
        }
        if (!username.matches("[\\w\\u4e00-\\u9fa5\\-]+")) {
            send(channel, Messages.error("BAD_USERNAME", "用户名仅允许字母数字下划线中文与短横线"));
            return;
        }

        PlayerData data = redis.getOrCreate(username);
        Long rank = data.isPlacementComplete() ? redis.getRank(username) : null;

        PlayerSession existing = sessions.getByUsername(username);
        PlayerSession session;
        if (existing != null) {
            session = existing;
            session.setCachedData(data);
        } else {
            session = new PlayerSession(username, channel, data);
        }
        sessions.bind(username, channel, session);

        long now = System.currentTimeMillis();

        BattleRoom room = rooms.getByUser(username);
        if (room != null && room.getPhase() != RoomPhase.CLOSED) {
            session.setRoomId(room.getRoomId());
            session.setInQueue(false);
            // login_ok only hints activity; authoritative state via sendCurrentTo (snapshot/final).
            send(channel, Messages.loginOk(data, rank, "in_room"));
            rooms.sendCurrentTo(username);
            System.out.println("[Login] " + username + " resumed room " + room.getRoomId() + " phase=" + room.getPhase());
            return;
        }

        QueueEntry q = matchQueue.get(username);
        if (q != null) {
            session.setInQueue(true);
            session.setRoomId(null);
            long wait = q.waitMs(now);
            int range = ServerConfig.matchRange(wait);
            // login_ok hints queued; wait/range come from match_status (same as heartbeat).
            send(channel, Messages.loginOk(data, rank, "queued"));
            send(channel, Messages.matchStatus("queued", wait, range, "已接续匹配队列"));
            System.out.println("[Login] " + username + " resumed queue waitMs=" + wait);
            return;
        }

        session.setInQueue(false);
        session.setRoomId(null);
        send(channel, Messages.loginOk(data, rank, "idle"));
        System.out.println("[Login] " + username + " elo=" + data.getElo() + " games=" + data.getGamesPlayed());
    }

    private void handleMatchStart(Channel channel) {
        PlayerSession session = requireLogin(channel);
        if (session == null) {
            return;
        }
        if (activeRoomOf(session.getUsername()) != null) {
            send(channel, Messages.error("ALREADY_IN_ROOM", "已在房间中，无法开始匹配"));
            return;
        }
        if (matchQueue.contains(session.getUsername())) {
            QueueEntry q = matchQueue.get(session.getUsername());
            long wait = q.waitMs(System.currentTimeMillis());
            send(channel, Messages.matchStatus("queued", wait, ServerConfig.matchRange(wait), "已在队列中"));
            return;
        }

        PlayerData data = redis.getOrCreate(session.getUsername());
        session.setCachedData(data);
        long seq = matchQueue.nextSeq();
        QueueEntry entry = new QueueEntry(session.getUsername(), data.getElo(), System.currentTimeMillis(), seq);
        matchQueue.enqueue(entry);
        session.setInQueue(true);
        send(channel, Messages.matchStatus("queued", 0, ServerConfig.MATCH_RANGE_INITIAL, "已进入匹配队列"));
        System.out.println("[Match] enqueue " + session.getUsername() + " elo=" + data.getElo() + " seq=" + seq);
    }

    private void handleMatchCancel(Channel channel) {
        PlayerSession session = requireLogin(channel);
        if (session == null) {
            return;
        }
        if (activeRoomOf(session.getUsername()) != null) {
            send(channel, Messages.error("ALREADY_IN_ROOM", "已在房间中，无法取消"));
            return;
        }

        QueueEntry removed = matchQueue.remove(session.getUsername());
        session.setInQueue(false);
        if (removed == null) {
            send(channel, Messages.matchStatus("idle", 0, 0, "当前不在匹配队列"));
        } else {
            send(channel, Messages.matchStatus("cancelled", 0, 0, "已取消匹配"));
            System.out.println("[Match] cancel " + session.getUsername());
        }
    }

    private void handleInputHand(Channel channel, JsonObject obj) {
        PlayerSession session = requireLogin(channel);
        if (session == null) {
            return;
        }
        BattleRoom room = activeRoomOf(session.getUsername());
        if (room == null) {
            send(channel, Messages.error("NOT_IN_ROOM", "当前不在对战房间"));
            return;
        }
        String handRaw = obj.has("hand") ? obj.get("hand").getAsString() : null;
        Hand hand = Hand.parse(handRaw);
        if (hand == null) {
            send(channel, Messages.error("INVALID_HAND", "非法手势，仅支持 ROCK/PAPER/SCISSORS"));
            return;
        }
        String err = room.inputHand(session.getUsername(), hand);
        if (err != null) {
            String msg = switch (err) {
                case "ROOM_NOT_PLAYING" -> "房间不在 PLAYING 阶段，无法输入";
                case "INVALID_HAND" -> "非法手势";
                case "NOT_IN_ROOM" -> "你不在该房间";
                default -> err;
            };
            send(channel, Messages.error(err, msg));
            return;
        }
        // Same as thrust: sticky input; next RoomManager tick snapshot carries it.
        // Local client already updates own hand UI; opponent sees it via tick sync.
    }

    private void handleInputThrust(Channel channel, JsonObject obj) {
        PlayerSession session = requireLogin(channel);
        if (session == null) {
            return;
        }
        BattleRoom room = activeRoomOf(session.getUsername());
        if (room == null) {
            send(channel, Messages.error("NOT_IN_ROOM", "当前不在对战房间"));
            return;
        }
        String raw = obj.has("thrust") ? obj.get("thrust").getAsString() : null;
        Thrust thrust = Thrust.parse(raw);
        if (thrust == null) {
            send(channel, Messages.error("INVALID_THRUST", "非法推力，仅支持 LEFT/RIGHT/NONE"));
            return;
        }
        String err = room.inputThrust(session.getUsername(), thrust);
        if (err != null) {
            String msg = switch (err) {
                case "ROOM_NOT_PLAYING" -> "房间不在 PLAYING 阶段，无法移动";
                case "INVALID_THRUST" -> "非法推力";
                case "NOT_IN_ROOM" -> "你不在该房间";
                default -> err;
            };
            send(channel, Messages.error(err, msg));
        }
        // Thrust is sampled every physics tick; no need to broadcast immediately.
    }

    private void handleRanking(Channel channel) {
        PlayerSession session = requireLogin(channel);
        if (session == null) {
            return;
        }
        List<RedisStore.RankingEntry> top = redis.top100();
        send(channel, Messages.rankingTop100(top));
    }

    private void handleQueryMe(Channel channel) {
        PlayerSession session = requireLogin(channel);
        if (session == null) {
            return;
        }
        PlayerData data = redis.getOrCreate(session.getUsername());
        session.setCachedData(data);
        Long rank = data.isPlacementComplete() ? redis.getRank(data.getUsername()) : null;
        send(channel, Messages.playerQuery(data, rank));
    }

    private void onMatched(QueueEntry a, QueueEntry b) {
        // Defensive: left = earlier queueSeq, right = later (a/b from matchmaker already ordered).
        QueueEntry left = a.getQueueSeq() <= b.getQueueSeq() ? a : b;
        QueueEntry right = left == a ? b : a;
        PlayerSession sa = sessions.getByUsername(left.getUsername());
        PlayerSession sb = sessions.getByUsername(right.getUsername());
        if (sa != null) {
            sa.setInQueue(false);
        }
        if (sb != null) {
            sb.setInQueue(false);
        }
        BattleRoom room = rooms.create(left.getUsername(), right.getUsername());
        System.out.println("[Match] room " + room.getRoomId()
                + " left=" + left.getUsername() + "(seq=" + left.getQueueSeq() + ")"
                + " right=" + right.getUsername() + "(seq=" + right.getQueueSeq() + ")");
    }

    private void onRoomClosed(BattleRoom room) {
        String idle = Messages.matchStatus("idle", 0, 0, "对局结束，可再次匹配");
        sessions.send(sessions.getByUsername(room.getPlayerA()), idle);
        sessions.send(sessions.getByUsername(room.getPlayerB()), idle);
        System.out.println("[Room] closed " + room.getRoomId());
    }

    private void broadcastQueueStatus() {
        long now = System.currentTimeMillis();
        for (QueueEntry e : matchQueue.snapshot()) {
            PlayerSession s = sessions.getByUsername(e.getUsername());
            if (s == null || s.getChannel() == null) {
                continue;
            }
            long wait = e.waitMs(now);
            int range = ServerConfig.matchRange(wait);
            sessions.send(s, Messages.matchStatus("queued", wait, range, null));
        }
    }

    /** Authoritative room membership: RoomManager userToRoom, not Session.roomId cache. */
    private BattleRoom activeRoomOf(String username) {
        BattleRoom room = rooms.getByUser(username);
        if (room == null || room.getPhase() == RoomPhase.CLOSED) {
            return null;
        }
        return room;
    }

    private PlayerSession requireLogin(Channel channel) {
        PlayerSession session = sessions.getByChannel(channel);
        if (session == null) {
            send(channel, Messages.error("NOT_LOGGED_IN", "请先登录"));
            return null;
        }
        if (!session.isActive()) {
            send(channel, Messages.error("SESSION_INACTIVE", "连接已失效"));
            return null;
        }
        return session;
    }

    private void send(Channel channel, String json) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(json));
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
