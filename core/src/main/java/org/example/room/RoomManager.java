package org.example.room;

import com.google.gson.JsonObject;
import org.example.model.PlayerData;
import org.example.model.RoomPhase;
import org.example.msg.Messages;
import org.example.redis.RedisStore;
import org.example.session.PlayerSession;
import org.example.session.SessionStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-memory rooms + tick/settle. With remote physics, simulate runs on Physics process.
 */
public final class RoomManager implements RoomService {

    private final Map<String, BattleRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> userToRoom = new ConcurrentHashMap<>();
    private final SessionStore sessions;
    private final RedisStore redis;
    private final Consumer<BattleRoom> onRoomClosed;
    private final RemotePhysicsClient physics;
    private final boolean remotePhysics;

    public RoomManager(SessionStore sessions, RedisStore redis, Consumer<BattleRoom> onRoomClosed) {
        this(sessions, redis, onRoomClosed, null);
    }

    public RoomManager(SessionStore sessions, RedisStore redis, Consumer<BattleRoom> onRoomClosed,
                       RemotePhysicsClient physics) {
        this.sessions = sessions;
        this.redis = redis;
        this.onRoomClosed = onRoomClosed;
        this.physics = physics;
        this.remotePhysics = physics != null;
    }

    public BattleRoom get(String roomId) {
        return rooms.get(roomId);
    }

    public BattleRoom getByUser(String username) {
        String id = userToRoom.get(username);
        return id == null ? null : rooms.get(id);
    }

    public BattleRoom create(String userA, String userB) {
        BattleRoom room = new BattleRoom(userA, userB);
        room.startPlaying();

        PlayerSession sa = sessions.getByUsername(userA);
        PlayerSession sb = sessions.getByUsername(userB);
        if (sa != null) {
            sa.setInQueue(false);
            sa.setRoomId(room.getRoomId());
        }
        if (sb != null) {
            sb.setInQueue(false);
            sb.setRoomId(room.getRoomId());
        }

        rooms.put(room.getRoomId(), room);
        userToRoom.put(userA, room.getRoomId());
        userToRoom.put(userB, room.getRoomId());

        if (remotePhysics) {
            try {
                physics.createRoom(room);
            } catch (Exception e) {
                System.err.println("[RoomManager] physics create failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
        broadcastSnapshot(room);
        return room;
    }

    public void removeUserMapping(String username) {
        userToRoom.remove(username);
    }

    public RemotePhysicsClient physics() {
        return physics;
    }

    public boolean isRemotePhysics() {
        return remotePhysics;
    }

    @Override
    public void run() {
        try {
            tickAll();
        } catch (Exception e) {
            System.err.println("[RoomManager] tick error: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    void tickAll() {
        long now = System.currentTimeMillis();
        for (BattleRoom room : rooms.values()) {
            if (room.getPhase() == RoomPhase.CLOSED) {
                continue;
            }
            if (room.getPhase() == RoomPhase.RESULT) {
                closeRoom(room);
                continue;
            }
            if (remotePhysics) {
                // Physics pushes PHYS_TICK_RESULT; Logic only closes RESULT rooms here.
                continue;
            }
            if (room.getPhase() == RoomPhase.PLAYING) {
                boolean settle = room.simulateTick(now);
                if (settle) {
                    settleAndBroadcast(room);
                } else {
                    broadcastSnapshot(room);
                }
            } else if (room.getPhase() == RoomPhase.MATCHED) {
                room.startPlaying();
                room.simulateTick(now);
                broadcastSnapshot(room);
            }
        }
    }

    /** Called on Logic event loop / Netty thread from physics push — keep short. */
    public void onPhysicsTick(JsonObject payload) {
        String roomId = payload.get("roomId").getAsString();
        BattleRoom room = rooms.get(roomId);
        if (room == null || room.getPhase() != RoomPhase.PLAYING) {
            return;
        }
        PhysicsTickCodec.applyTick(room, payload);
        boolean shouldSettle = payload.has("shouldSettle") && payload.get("shouldSettle").getAsBoolean();
        if (shouldSettle) {
            settleAndBroadcast(room);
        } else {
            broadcastSnapshot(room);
        }
    }

    public void settleAndBroadcast(BattleRoom room) {
        if (room.getPhase() == RoomPhase.RESULT || room.getPhase() == RoomPhase.CLOSED) {
            return;
        }
        PlayerData dataA = loadFresh(room.getPlayerA());
        PlayerData dataB = loadFresh(room.getPlayerB());
        room.settle(dataA, dataB);
        redis.settlePlayers(dataA, dataB);

        BattleRoom.SettleResult r = room.getSettleResult();
        if (r != null) {
            System.out.println("[Room] settle id=" + room.getRoomId()
                    + " reason=" + r.endReason()
                    + " lights=" + r.lightsA() + ":" + r.lightsB()
                    + " A=" + room.getPlayerA() + "/" + r.outcomeA()
                    + " elo=" + r.eloABefore() + "->" + r.updA().newElo() + "(" + r.updA().delta() + ")"
                    + " B=" + room.getPlayerB() + "/" + r.outcomeB()
                    + " elo=" + r.eloBBefore() + "->" + r.updB().newElo() + "(" + r.updB().delta() + ")");
        }

        PlayerSession sa = sessions.getByUsername(room.getPlayerA());
        PlayerSession sb = sessions.getByUsername(room.getPlayerB());
        if (sa != null) {
            sa.setCachedData(dataA);
        }
        if (sb != null) {
            sb.setCachedData(dataB);
        }

        Long rankA = dataA.isPlacementComplete() ? redis.getRank(dataA.getUsername()) : null;
        Long rankB = dataB.isPlacementComplete() ? redis.getRank(dataB.getUsername()) : null;
        long now = System.currentTimeMillis();
        String json = Messages.finalResult(room, dataA, dataB, rankA, rankB, now);
        sendReliable(sa, json);
        sendReliable(sb, json);
    }

    private void sendReliable(PlayerSession session, String json) {
        if (session == null) {
            return;
        }
        long seq = session.nextOutboundSeq();
        String withSeq = Messages.withSeq(json, seq);
        session.rememberReliable(seq, withSeq);
        sessions.send(session, withSeq);
    }

    private PlayerData loadFresh(String username) {
        PlayerData d = redis.getOrCreate(username);
        PlayerSession s = sessions.getByUsername(username);
        if (s != null) {
            s.setCachedData(d);
        }
        return d;
    }

    public void broadcastSnapshot(BattleRoom room) {
        long now = System.currentTimeMillis();
        String json = Messages.roomSnapshot(room, now);
        sessions.send(sessions.getByUsername(room.getPlayerA()), json);
        sessions.send(sessions.getByUsername(room.getPlayerB()), json);
    }

    public void closeRoom(BattleRoom room) {
        if (room.getPhase() == RoomPhase.CLOSED) {
            return;
        }
        room.close();
        rooms.remove(room.getRoomId());
        userToRoom.remove(room.getPlayerA(), room.getRoomId());
        userToRoom.remove(room.getPlayerB(), room.getRoomId());
        if (remotePhysics) {
            physics.destroyRoom(room.getRoomId());
        }
        clearRoomState(room.getPlayerA());
        clearRoomState(room.getPlayerB());
        if (onRoomClosed != null) {
            onRoomClosed.accept(room);
        }
    }

    private void clearRoomState(String username) {
        PlayerSession s = sessions.getByUsername(username);
        if (s != null) {
            s.setRoomId(null);
        }
    }

    public void sendCurrentTo(String username) {
        BattleRoom room = getByUser(username);
        if (room == null) {
            return;
        }
        PlayerSession session = sessions.getByUsername(username);
        long now = System.currentTimeMillis();
        if (room.getPhase() == RoomPhase.RESULT || room.getSettleResult() != null) {
            PlayerData dataA = redis.getOrCreate(room.getPlayerA());
            PlayerData dataB = redis.getOrCreate(room.getPlayerB());
            Long rankA = dataA.isPlacementComplete() ? redis.getRank(dataA.getUsername()) : null;
            Long rankB = dataB.isPlacementComplete() ? redis.getRank(dataB.getUsername()) : null;
            sessions.send(session, Messages.finalResult(room, dataA, dataB, rankA, rankB, now));
        } else {
            sessions.send(session, Messages.roomSnapshot(room, now));
        }
    }
}
