package org.example.room;

import org.example.config.ServerConfig;
import org.example.model.PlayerData;
import org.example.model.RoomPhase;
import org.example.msg.Messages;
import org.example.redis.RedisStore;
import org.example.session.PlayerSession;
import org.example.session.SessionManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-memory rooms + tick loop. Settles and broadcasts snapshots.
 */
public final class RoomManager implements Runnable {

    private final Map<String, BattleRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> userToRoom = new ConcurrentHashMap<>();
    private final SessionManager sessions;
    private final RedisStore redis;
    private final Consumer<BattleRoom> onRoomClosed;

    public RoomManager(SessionManager sessions, RedisStore redis, Consumer<BattleRoom> onRoomClosed) {
        this.sessions = sessions;
        this.redis = redis;
        this.onRoomClosed = onRoomClosed;
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
        // Finish local init before publishing into maps (avoids tick seeing a half-ready room).
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
        broadcastSnapshot(room);
        return room;
    }

    public void removeUserMapping(String username) {
        userToRoom.remove(username);
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
                // Previous tick already sent finalResult and set RESULT; close one tick later
                // so the room still exists briefly for reconnect / sendCurrentTo.
                closeRoom(room);
                continue;
            }
            if (room.getPhase() == RoomPhase.PLAYING) {
                boolean settle = room.simulateTick(now);
                if (settle || room.shouldSettle(now)) {
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

    public void settleAndBroadcast(BattleRoom room) {
        PlayerData dataA = loadFresh(room.getPlayerA());
        PlayerData dataB = loadFresh(room.getPlayerB());
        room.settle(dataA, dataB);
        redis.settlePlayers(dataA, dataB);

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
        sessions.send(sa, json);
        sessions.send(sb, json);
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

    /** Send current snapshot (or final) to a reconnecting player. */
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
