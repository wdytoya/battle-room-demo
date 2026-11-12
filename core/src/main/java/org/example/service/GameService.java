package org.example.service;

import com.google.gson.JsonObject;
import org.example.config.ClusterConfig;
import org.example.config.ServerConfig;
import org.example.match.EmbeddedMatchService;
import org.example.match.MatchQueue;
import org.example.match.MatchService;
import org.example.match.QueueEntry;
import org.example.match.RemoteMatchClient;
import org.example.model.Hand;
import org.example.model.PlayerData;
import org.example.model.RoomPhase;
import org.example.model.Thrust;
import org.example.msg.Json;
import org.example.msg.Messages;
import org.example.redis.RedisStore;
import org.example.room.BattleRoom;
import org.example.room.RemotePhysicsClient;
import org.example.room.RoomManager;
import org.example.session.ClientTransport;
import org.example.session.ConnState;
import org.example.session.PlayerSession;
import org.example.session.SessionManager;
import org.example.session.SessionStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Orchestrates login, matchmaking, input, ranking.
 * Optional remote Match / Physics when running as Logic process.
 */
public final class GameService implements ClientMessageHandler, AutoCloseable {

    private final RedisStore redis;
    private final SessionStore sessions;
    private final RoomManager rooms;
    private final MatchQueue matchQueue;
    private final MatchService matchService;
    private final RemoteMatchClient remoteMatch;
    private final RemotePhysicsClient remotePhysics;
    private final boolean remoteMatchMode;
    private long lastMatchScanMs;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10, r -> {
        Thread t = new Thread(r, "battle-scheduler");
        t.setDaemon(true);
        return t;
    });

    public GameService(RedisStore redis, ClientTransport transport, Consumer<PlayerSession> onKickOld) {
        this(redis, transport, onKickOld, false, false);
    }

    public GameService(RedisStore redis, ClientTransport transport,
                       Consumer<PlayerSession> onKickOld,
                       boolean remoteMatchMode, boolean remotePhysicsMode) {
        this.redis = redis;
        this.remoteMatchMode = remoteMatchMode;
        this.sessions = new SessionManager(transport, onKickOld);

        final RoomManager[] roomRef = new RoomManager[1];
        RemotePhysicsClient phys = null;
        if (remotePhysicsMode) {
            try {
                phys = new RemotePhysicsClient(payload -> roomRef[0].onPhysicsTick(payload));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to connect Physics service", e);
            }
        }
        this.remotePhysics = phys;
        this.rooms = new RoomManager(sessions, redis, this::onRoomClosed, phys);
        roomRef[0] = this.rooms;

        if (remoteMatchMode) {
            this.matchQueue = null;
            this.matchService = null;
            try {
                this.remoteMatch = new RemoteMatchClient(sessions, this::onRemoteMatched);
            } catch (Exception e) {
                if (phys != null) {
                    phys.close();
                }
                throw new IllegalStateException("Failed to connect Match service", e);
            }
        } else {
            this.matchQueue = new MatchQueue();
            this.matchService = new EmbeddedMatchService(matchQueue, this::onMatched);
            this.remoteMatch = null;
        }

        long tickMs = ServerConfig.tickIntervalMs();
        scheduler.scheduleAtFixedRate(this::gameLoop, tickMs, tickMs, TimeUnit.MILLISECONDS);
        if (!remoteMatchMode) {
            scheduler.scheduleAtFixedRate(this::broadcastQueueStatus, 1, 1, TimeUnit.SECONDS);
        }
        long renewMs = Math.max(1_000L, ClusterConfig.ROUTE_RENEW_MS);
        scheduler.scheduleAtFixedRate(this::renewOnlineRoutes, renewMs, renewMs, TimeUnit.MILLISECONDS);
    }

    private void gameLoop() {
        long now = System.currentTimeMillis();
        if (!remoteMatchMode && now - lastMatchScanMs >= ServerConfig.MATCH_SCAN_INTERVAL_MS) {
            lastMatchScanMs = now;
            matchService.run();
        }
        rooms.run();
    }

    public SessionStore sessions() {
        return sessions;
    }

    public RoomManager rooms() {
        return rooms;
    }

    @Override
    public void onConnInactive(String connId) {
        onConnInactive(connId, "disconnect");
    }

    @Override
    public void onConnInactive(String connId, String reason) {
        PlayerSession session = sessions.getByConnId(connId);
        sessions.unbindConn(connId);
        if (session == null) {
            return;
        }
        boolean heartbeat = "heartbeat".equals(reason);
        System.out.println("[Session] conn closed user=" + session.getUsername()
                + " connId=" + connId + " reason=" + reason);
        if (heartbeat || !ClusterConfig.RECONNECT_ENABLED) {
            abandonReconnect(session, "heartbeat_or_disabled");
            return;
        }
        // Soft disconnect: keep room/queue; allow reconnect within window. Do NOT clear Redis tokens.
        softDisconnect(session, reason == null ? "disconnect" : reason);
    }

    /**
     * Gateway RPC channel lost: soft-disconnect every ONLINE session on that gateway
     * without abandoning reconnect (Redis tokens stay). Covers hard Gateway kills where
     * per-conn CLIENT_INACTIVE never arrives.
     */
    public void softDisconnectGateway(String gatewayId) {
        if (gatewayId == null || gatewayId.isBlank() || !(sessions instanceof SessionManager sm)) {
            return;
        }
        int n = 0;
        for (PlayerSession session : sm.allSessions()) {
            if (!gatewayId.equals(session.getGatewayId())) {
                continue;
            }
            if (session.getConnState() != ConnState.ONLINE) {
                continue;
            }
            String connId = session.getConnId();
            if (connId != null) {
                sessions.unbindConn(connId);
            }
            softDisconnect(session, "gateway_down");
            n++;
        }
        if (n > 0) {
            System.out.println("[Session] gateway-down soft-disconnected count=" + n + " gateway=" + gatewayId);
        }
    }

    private void softDisconnect(PlayerSession session, String why) {
        if (session.getConnState() == ConnState.DISCONNECTED || session.getConnState() == ConnState.GONE) {
            return;
        }
        session.setConnState(ConnState.DISCONNECTED);
        final long disconnectAt = System.currentTimeMillis();
        session.setDisconnectAtMs(disconnectAt);
        session.setConnId(null);
        persistRoute(session, ConnState.DISCONNECTED);
        final String username = session.getUsername();
        final String token = session.getReconnectToken();
        // Capture disconnectAt so an older timer cannot clear Redis after a later
        // reconnect + soft-disconnect cycle (window would otherwise be truncated).
        scheduler.schedule(() -> expireReconnect(username, token, disconnectAt),
                ClusterConfig.RECONNECT_WINDOW_MS, TimeUnit.MILLISECONDS);
        System.out.println("[Session] soft-disconnect user=" + username + " why=" + why);
    }

    private void abandonReconnect(PlayerSession session, String why) {
        session.markInactive(); // ConnState.GONE
        redis.clearRoute(session.getUsername(), session.getReconnectToken());
        System.out.println("[Session] abandon reconnect user=" + session.getUsername() + " why=" + why);
        // Keep session object if still in room so settle can update cache; drop conn mapping only.
    }

    private void expireReconnect(String username, String token, long disconnectAtMs) {
        PlayerSession s = sessions.getByUsername(username);
        if (s == null) {
            redis.clearRoute(username, token);
            return;
        }
        if (s.getConnState() != ConnState.DISCONNECTED) {
            return;
        }
        // Superseded by a newer soft-disconnect (or reconnect cleared the stamp).
        if (s.getDisconnectAtMs() != disconnectAtMs) {
            return;
        }
        if (token != null && !token.equals(s.getReconnectToken())) {
            return;
        }
        System.out.println("[Session] reconnect window expired user=" + username);
        s.setConnState(ConnState.GONE);
        redis.clearRoute(username, token);
        // Room continues empty-spin until natural settle.
    }

    private void persistRoute(PlayerSession session, ConnState state) {
        // Same TTL on both keys within a phase (reconnect always needs the pair).
        // Phases differ: ONLINE = short lease + renew; soft-disconnect = reconnect window + slack.
        long ttl = state == ConnState.ONLINE
                ? ClusterConfig.ROUTE_LEASE_MS
                : ClusterConfig.RECONNECT_WINDOW_MS + ClusterConfig.RECONNECT_REDIS_SLACK_MS;
        redis.saveRoute(session.getUsername(), session.getGatewayId(), session.getConnId(),
                session.getSessionToken(), session.getReconnectToken(), state.name(),
                ttl, ttl);
    }

    /** Keep Redis online route + reconnect-token leases alive without rewriting fields. */
    private void renewOnlineRoutes() {
        if (!(sessions instanceof SessionManager sm)) {
            return;
        }
        for (PlayerSession session : sm.allSessions()) {
            if (session.getConnState() != ConnState.ONLINE) {
                continue;
            }
            String token = session.getReconnectToken();
            if (token == null || token.isBlank()) {
                continue;
            }
            long lease = ClusterConfig.ROUTE_LEASE_MS;
            redis.refreshRouteTtl(session.getUsername(), token, lease, lease);
        }
    }

    private void issueTokens(PlayerSession session) {
        session.setSessionToken(UUID.randomUUID().toString().replace("-", ""));
        session.setReconnectToken(UUID.randomUUID().toString().replace("-", ""));
        persistRoute(session, ConnState.ONLINE);
    }

    private String activityOf(PlayerSession session) {
        BattleRoom room = rooms.getByUser(session.getUsername());
        if (room != null && room.getPhase() != RoomPhase.CLOSED) {
            return "in_room";
        }
        if (isQueued(session.getUsername())) {
            return "queued";
        }
        return "idle";
    }

    private void sendLoginOk(PlayerSession session, PlayerData data, Long rank, String activity) {
        long seq = session.nextOutboundSeq();
        String json = Messages.loginOk(data, rank, activity,
                session.getSessionToken(), session.getReconnectToken(), seq);
        String withSeq = Messages.withSeq(json, seq);
        session.rememberReliable(seq, withSeq);
        sessions.send(session, withSeq);
    }

    @Override
    public void handleText(String connId, String gatewayId, String text) {
        JsonObject obj;
        try {
            obj = Json.parseObject(text);
        } catch (Exception e) {
            sendRaw(connId, gatewayId, Messages.error("BAD_JSON", "消息不是合法 JSON"));
            return;
        }
        if (!obj.has("type")) {
            sendRaw(connId, gatewayId, Messages.error("MISSING_TYPE", "缺少 type 字段"));
            return;
        }
        String type = obj.get("type").getAsString();
        try {
            switch (type) {
                case "login" -> handleLogin(connId, gatewayId, obj);
                case "reconnect" -> handleReconnect(connId, gatewayId, obj);
                case "match_start" -> handleMatchStart(connId);
                case "match_cancel" -> handleMatchCancel(connId);
                case "input_hand" -> handleInputHand(connId, obj);
                case "input_thrust" -> handleInputThrust(connId, obj);
                case "query_ranking_top100" -> handleRanking(connId);
                case "query_me" -> handleQueryMe(connId);
                case "ping" -> { /* answered at Gateway */ }
                default -> sendToConn(connId, Messages.error("UNKNOWN_TYPE", "未知消息类型: " + type));
            }
        } catch (Exception e) {
            System.err.println("[GameService] handle error type=" + type + ": " + e.getMessage());
            e.printStackTrace(System.err);
            sendToConn(connId, Messages.error("INTERNAL", "服务端处理异常: " + e.getMessage()));
        }
    }

    private void handleReconnect(String connId, String gatewayId, JsonObject obj) {
        if (!ClusterConfig.RECONNECT_ENABLED) {
            sendRaw(connId, gatewayId, Messages.error("RECONNECT_DISABLED", "重连未开启"));
            return;
        }
        if (!obj.has("username") || !obj.has("reconnectToken")) {
            sendRaw(connId, gatewayId, Messages.error("BAD_RECONNECT", "缺少 username/reconnectToken"));
            return;
        }
        String username = obj.get("username").getAsString().trim();
        String token = obj.get("reconnectToken").getAsString().trim();
        long lastAck = obj.has("lastAckSeq") ? obj.get("lastAckSeq").getAsLong() : 0L;

        // Redis is authoritative for reconnect eligibility.
        String mapped = redis.usernameByReconnectToken(token);
        if (mapped == null || !mapped.equals(username)) {
            sendRaw(connId, gatewayId, Messages.error("BAD_RECONNECT_TOKEN", "重连凭证无效或已过期"));
            return;
        }

        Map<String, String> route = redis.getRoute(username);
        PlayerData data = redis.getOrCreate(username);
        PlayerSession session = sessions.getByUsername(username);

        if (session == null) {
            session = restoreSession(username, connId, data, route, token);
        } else if (session.getConnState() == ConnState.GONE) {
            // Redis still has a valid token → revive rather than force re-login.
            session.setCachedData(data);
            syncTokensFromRedis(session, route, token);
            System.out.println("[Reconnect] revive GONE session user=" + username);
        } else {
            session.setCachedData(data);
            // Minor in-memory token drift: trust Redis, do not fail.
            if (session.getReconnectToken() == null || !session.getReconnectToken().equals(token)) {
                System.out.println("[Reconnect] sync in-memory token from Redis user=" + username);
                syncTokensFromRedis(session, route, token);
            }
        }

        sessions.bind(username, connId, gatewayId, session); // sets conn/gateway + ONLINE
        session.setDisconnectAtMs(0L);
        persistRoute(session, ConnState.ONLINE);

        Long rank = data.isPlacementComplete() ? redis.getRank(username) : null;
        String activity = activityOf(session);
        long seq = session.nextOutboundSeq();
        String ok = Messages.withSeq(
                Messages.reconnectOk(data, rank, activity,
                        session.getSessionToken(), session.getReconnectToken(), seq),
                seq);
        session.rememberReliable(seq, ok);
        sessions.send(session, ok);

        for (String replay : session.reliableSince(lastAck)) {
            sessions.send(session, replay);
        }
        if ("in_room".equals(activity)) {
            rooms.sendCurrentTo(username);
        } else if ("queued".equals(activity)) {
            long wait = queuedWaitMs(username);
            sessions.send(session, Messages.matchStatus("queued", wait, ServerConfig.matchRange(wait), "重连后接续匹配"));
        }
        System.out.println("[Reconnect] ok user=" + username + " gateway=" + gatewayId + " activity=" + activity);
    }

    private PlayerSession restoreSession(String username, String connId, PlayerData data,
                                         Map<String, String> route, String token) {
        PlayerSession session = new PlayerSession(username, connId, data);
        syncTokensFromRedis(session, route, token);
        if (session.getSessionToken() == null || session.getSessionToken().isBlank()) {
            session.setSessionToken(UUID.randomUUID().toString().replace("-", ""));
        }
        System.out.println("[Reconnect] restore session from Redis user=" + username);
        return session;
    }

    private static void syncTokensFromRedis(PlayerSession session, Map<String, String> route, String token) {
        session.setReconnectToken(token);
        if (route != null) {
            String st = route.get("sessionToken");
            if (st != null && !st.isBlank()) {
                session.setSessionToken(st);
            }
        }
    }

    private void handleLogin(String connId, String gatewayId, JsonObject obj) {
        if (!obj.has("username")) {
            sendRaw(connId, gatewayId, Messages.error("BAD_USERNAME", "缺少 username"));
            return;
        }
        String username = obj.get("username").getAsString().trim();
        if (username.isEmpty() || username.length() > ServerConfig.MAX_USERNAME_LENGTH) {
            sendRaw(connId, gatewayId, Messages.error("BAD_USERNAME", "用户名长度须为 1-" + ServerConfig.MAX_USERNAME_LENGTH));
            return;
        }
        if (!username.matches("[\\w\\u4e00-\\u9fa5\\-]+")) {
            sendRaw(connId, gatewayId, Messages.error("BAD_USERNAME", "用户名仅允许字母数字下划线中文与短横线"));
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
            session = new PlayerSession(username, connId, data);
        }
        sessions.bind(username, connId, gatewayId, session); // sets conn/gateway + ONLINE
        issueTokens(session);

        BattleRoom room = rooms.getByUser(username);
        if (room != null && room.getPhase() != RoomPhase.CLOSED) {
            session.setRoomId(room.getRoomId());
            session.setInQueue(false);
            sendLoginOk(session, data, rank, "in_room");
            rooms.sendCurrentTo(username);
            System.out.println("[Login] " + username + " resumed room " + room.getRoomId() + " phase=" + room.getPhase());
            return;
        }

        if (isQueued(username)) {
            session.setInQueue(true);
            session.setRoomId(null);
            long wait = queuedWaitMs(username);
            int range = ServerConfig.matchRange(wait);
            sendLoginOk(session, data, rank, "queued");
            sessions.send(session, Messages.matchStatus("queued", wait, range, "已接续匹配队列"));
            System.out.println("[Login] " + username + " resumed queue waitMs=" + wait);
            return;
        }

        session.setInQueue(false);
        session.setRoomId(null);
        sendLoginOk(session, data, rank, "idle");
        System.out.println("[Login] " + username + " elo=" + data.getElo() + " games=" + data.getGamesPlayed());
    }

    private void handleMatchStart(String connId) {
        PlayerSession session = requireLogin(connId);
        if (session == null) {
            return;
        }
        if (activeRoomOf(session.getUsername()) != null) {
            sessions.send(session, Messages.error("ALREADY_IN_ROOM", "已在房间中，无法开始匹配"));
            return;
        }
        if (isQueued(session.getUsername())) {
            long wait = queuedWaitMs(session.getUsername());
            sessions.send(session, Messages.matchStatus("queued", wait, ServerConfig.matchRange(wait), "已在队列中"));
            return;
        }

        PlayerData data = redis.getOrCreate(session.getUsername());
        session.setCachedData(data);
        try {
            long seq = enqueue(session.getUsername(), data.getElo());
            session.setInQueue(true);
            sessions.send(session, Messages.matchStatus("queued", 0, ServerConfig.MATCH_RANGE_INITIAL, "已进入匹配队列"));
            // Detail enqueue log lives on Match process; monolith embeds match so log here.
            if (!remoteMatchMode) {
                System.out.println("[Match] enqueue " + session.getUsername() + " elo=" + data.getElo() + " seq=" + seq);
            }
        } catch (Exception e) {
            sessions.send(session, Messages.error("MATCH_UNAVAILABLE", "匹配服务不可用: " + e.getMessage()));
        }
    }

    private void handleMatchCancel(String connId) {
        PlayerSession session = requireLogin(connId);
        if (session == null) {
            return;
        }
        if (activeRoomOf(session.getUsername()) != null) {
            sessions.send(session, Messages.error("ALREADY_IN_ROOM", "已在房间中，无法取消"));
            return;
        }

        try {
            boolean removed = cancelQueue(session.getUsername());
            session.setInQueue(false);
            if (!removed) {
                sessions.send(session, Messages.matchStatus("idle", 0, 0, "当前不在匹配队列"));
            } else {
                sessions.send(session, Messages.matchStatus("cancelled", 0, 0, "已取消匹配"));
                if (!remoteMatchMode) {
                    System.out.println("[Match] cancel " + session.getUsername());
                }
            }
        } catch (Exception e) {
            sessions.send(session, Messages.error("MATCH_UNAVAILABLE", "匹配服务不可用: " + e.getMessage()));
        }
    }

    private void handleInputHand(String connId, JsonObject obj) {
        PlayerSession session = requireLogin(connId);
        if (session == null) {
            return;
        }
        BattleRoom room = activeRoomOf(session.getUsername());
        if (room == null) {
            sessions.send(session, Messages.error("NOT_IN_ROOM", "当前不在对战房间"));
            return;
        }
        String handRaw = obj.has("hand") ? obj.get("hand").getAsString() : null;
        Hand hand = Hand.parse(handRaw);
        if (hand == null) {
            sessions.send(session, Messages.error("INVALID_HAND", "非法手势，仅支持 ROCK/PAPER/SCISSORS"));
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
            sessions.send(session, Messages.error(err, msg));
            return;
        }
        if (remotePhysics != null) {
            remotePhysics.inputHand(room.getRoomId(), session.getUsername(), hand);
        }
    }

    private void handleInputThrust(String connId, JsonObject obj) {
        PlayerSession session = requireLogin(connId);
        if (session == null) {
            return;
        }
        BattleRoom room = activeRoomOf(session.getUsername());
        if (room == null) {
            sessions.send(session, Messages.error("NOT_IN_ROOM", "当前不在对战房间"));
            return;
        }
        String raw = obj.has("thrust") ? obj.get("thrust").getAsString() : null;
        Thrust thrust = Thrust.parse(raw);
        if (thrust == null) {
            sessions.send(session, Messages.error("INVALID_THRUST", "非法推力，仅支持 LEFT/RIGHT/NONE"));
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
            sessions.send(session, Messages.error(err, msg));
            return;
        }
        if (remotePhysics != null) {
            remotePhysics.inputThrust(room.getRoomId(), session.getUsername(), thrust);
        }
    }

    private void handleRanking(String connId) {
        PlayerSession session = requireLogin(connId);
        if (session == null) {
            return;
        }
        sessions.send(session, Messages.rankingTop100(redis.top100()));
    }

    private void handleQueryMe(String connId) {
        PlayerSession session = requireLogin(connId);
        if (session == null) {
            return;
        }
        PlayerData data = redis.getOrCreate(session.getUsername());
        session.setCachedData(data);
        Long rank = data.isPlacementComplete() ? redis.getRank(data.getUsername()) : null;
        sessions.send(session, Messages.playerQuery(data, rank));
    }

    private void onMatched(QueueEntry a, QueueEntry b) {
        QueueEntry left = a.getQueueSeq() <= b.getQueueSeq() ? a : b;
        QueueEntry right = left == a ? b : a;
        createMatchedRoom(left.getUsername(), right.getUsername(), left.getQueueSeq(), right.getQueueSeq());
    }

    private void onRemoteMatched(String left, String right) {
        createMatchedRoom(left, right, 0, 1);
    }

    private void createMatchedRoom(String left, String right, long leftSeq, long rightSeq) {
        PlayerSession sa = sessions.getByUsername(left);
        PlayerSession sb = sessions.getByUsername(right);
        if (sa != null) {
            sa.setInQueue(false);
        }
        if (sb != null) {
            sb.setInQueue(false);
        }
        BattleRoom room = rooms.create(left, right);
        // Pairing detail is on Match; Logic owns room lifecycle summary.
        System.out.println("[Room] created id=" + room.getRoomId()
                + " A=" + left + " B=" + right);
    }

    private void onRoomClosed(BattleRoom room) {
        String idle = Messages.matchStatus("idle", 0, 0, "对局结束，可再次匹配");
        sessions.send(sessions.getByUsername(room.getPlayerA()), idle);
        sessions.send(sessions.getByUsername(room.getPlayerB()), idle);
        System.out.println("[Room] closed id=" + room.getRoomId()
                + " A=" + room.getPlayerA() + " B=" + room.getPlayerB());
    }

    private void broadcastQueueStatus() {
        long now = System.currentTimeMillis();
        for (QueueEntry e : matchQueue.snapshot()) {
            PlayerSession s = sessions.getByUsername(e.getUsername());
            if (s == null || s.getConnId() == null) {
                continue;
            }
            long wait = e.waitMs(now);
            int range = ServerConfig.matchRange(wait);
            sessions.send(s, Messages.matchStatus("queued", wait, range, null));
        }
    }

    private boolean isQueued(String username) {
        try {
            if (remoteMatchMode) {
                return remoteMatch.contains(username).contains();
            }
            return matchQueue.contains(username);
        } catch (Exception e) {
            return false;
        }
    }

    private long queuedWaitMs(String username) {
        try {
            if (remoteMatchMode) {
                return remoteMatch.contains(username).waitMs();
            }
            QueueEntry q = matchQueue.get(username);
            return q == null ? 0L : q.waitMs(System.currentTimeMillis());
        } catch (Exception e) {
            return 0L;
        }
    }

    private long enqueue(String username, int elo) throws Exception {
        if (remoteMatchMode) {
            return remoteMatch.enqueue(username, elo);
        }
        long seq = matchQueue.nextSeq();
        matchQueue.enqueue(new QueueEntry(username, elo, System.currentTimeMillis(), seq));
        return seq;
    }

    private boolean cancelQueue(String username) throws Exception {
        if (remoteMatchMode) {
            return remoteMatch.cancel(username);
        }
        return matchQueue.remove(username) != null;
    }

    private BattleRoom activeRoomOf(String username) {
        BattleRoom room = rooms.getByUser(username);
        if (room == null || room.getPhase() == RoomPhase.CLOSED) {
            return null;
        }
        return room;
    }

    private PlayerSession requireLogin(String connId) {
        PlayerSession session = sessions.getByConnId(connId);
        if (session == null) {
            sendToConn(connId, Messages.error("NOT_LOGGED_IN", "请先登录"));
            return null;
        }
        if (!session.isActive()) {
            sessions.send(session, Messages.error("SESSION_INACTIVE", "连接已失效"));
            return null;
        }
        return session;
    }

    private void sendToConn(String connId, String json) {
        PlayerSession s = sessions.getByConnId(connId);
        if (s != null) {
            sessions.send(s, json);
        }
    }

    private void sendRaw(String connId, String gatewayId, String json) {
        PlayerSession shell = new PlayerSession("__raw__", connId, null);
        shell.setGatewayId(gatewayId);
        sessions.send(shell, json);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        if (remoteMatch != null) {
            remoteMatch.close();
        }
        if (remotePhysics != null) {
            remotePhysics.close();
        }
    }
}
