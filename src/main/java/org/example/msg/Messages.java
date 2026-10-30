package org.example.msg;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.example.config.ServerConfig;
import org.example.model.Hand;
import org.example.model.PlayerData;
import org.example.model.Thrust;
import org.example.redis.RedisStore;
import org.example.room.BattleRoom;

import java.util.List;

public final class Messages {

    public static String error(String code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.addProperty("code", code);
        o.addProperty("message", message);
        return o.toString();
    }

    public static String loginOk(PlayerData data, Long rank, String activity) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "login_ok");
        o.add("player", playerView(data, rank));
        o.addProperty("activity", activity == null ? "idle" : activity);
        o.add("physics", physicsParams());
        return o.toString();
    }

    public static JsonObject playerView(PlayerData data, Long rank) {
        JsonObject p = new JsonObject();
        p.addProperty("username", data.getUsername());
        p.addProperty("gamesPlayed", data.getGamesPlayed());
        p.addProperty("placementGames", ServerConfig.PLACEMENT_GAMES);
        p.addProperty("placementComplete", data.isPlacementComplete());
        p.addProperty("placementProgress", data.placementProgress());
        p.addProperty("eloReal", data.getElo());
        if (data.isPlacementComplete()) {
            p.addProperty("elo", data.getElo());
            if (rank != null) {
                p.addProperty("rank", rank);
            } else {
                p.add("rank", null);
            }
            p.addProperty("display", "elo");
        } else {
            p.add("elo", null);
            p.add("rank", null);
            p.addProperty("display", "placement");
            p.addProperty("placementLabel", "定级中 " + data.placementProgress() + "/" + ServerConfig.PLACEMENT_GAMES);
        }
        return p;
    }

    public static String matchStatus(String state, long waitMs, int range, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "match_status");
        o.addProperty("state", state);
        o.addProperty("waitMs", waitMs);
        o.addProperty("range", range);
        if (message != null) {
            o.addProperty("message", message);
        }
        return o.toString();
    }

    public static String roomSnapshot(BattleRoom room, long now) {
        JsonObject o = snapshotBody(room, now);
        o.addProperty("type", "room_snapshot");
        return o.toString();
    }

    public static String finalResult(BattleRoom room, PlayerData dataA, PlayerData dataB,
                                    Long rankA, Long rankB, long now) {
        JsonObject o = snapshotBody(room, now);
        o.addProperty("type", "final_result");
        BattleRoom.SettleResult r = room.getSettleResult();
        if (r != null) {
            o.add("result", settleView(r, dataA, dataB, rankA, rankB));
        }
        return o.toString();
    }

    private static JsonObject snapshotBody(BattleRoom room, long now) {
        JsonObject o = new JsonObject();
        o.addProperty("roomId", room.getRoomId());
        o.addProperty("tick", room.getTick());
        o.addProperty("phase", room.getPhase().name());
        o.addProperty("remainingMs", room.remainingMs(now));
        o.addProperty("playingDurationMs", ServerConfig.PLAYING_DURATION_MS);
        o.addProperty("serverTimeMs", now);

        JsonObject players = new JsonObject();
        players.add(room.getPlayerA(), playerState(room, room.getPlayerA(), true));
        players.add(room.getPlayerB(), playerState(room, room.getPlayerB(), false));
        o.add("players", players);
        o.addProperty("playerA", room.getPlayerA());
        o.addProperty("playerB", room.getPlayerB());

        BattleRoom.CollisionEvent hit = room.getLastCollision();
        if (hit != null) {
            JsonObject c = new JsonObject();
            c.addProperty("tick", hit.tick());
            c.addProperty("atMs", hit.atMs());
            c.addProperty("handA", hit.handA());
            c.addProperty("handB", hit.handB());
            c.addProperty("rps", hit.rps());
            c.addProperty("winner", hit.winner());
            c.addProperty("loser", hit.loser());
            c.addProperty("lightsA", hit.lightsA());
            c.addProperty("lightsB", hit.lightsB());
            o.add("lastCollision", c);
        }
        if (room.getEndReason() != null && room.getEndReason() != BattleRoom.EndReason.NONE) {
            o.addProperty("endReason", room.getEndReason().name());
        }
        return o;
    }

    private static JsonObject physicsParams() {
        JsonObject p = new JsonObject();
        p.addProperty("arenaWidth", ServerConfig.ARENA_WIDTH);
        p.addProperty("ballRadius", ServerConfig.BALL_RADIUS);
        p.addProperty("accel", ServerConfig.ACCEL);
        p.addProperty("maxSpeed", ServerConfig.MAX_SPEED);
        p.addProperty("friction", ServerConfig.FRICTION);
        p.addProperty("tickHz", ServerConfig.TICK_HZ);
        p.addProperty("maxLights", ServerConfig.MAX_LIGHTS);
        p.addProperty("wallRestitution", ServerConfig.WALL_RESTITUTION);
        p.addProperty("collisionRestitution", ServerConfig.COLLISION_RESTITUTION);
        p.addProperty("collisionCooldownMs", ServerConfig.COLLISION_COOLDOWN_MS);
        return p;
    }

    private static JsonObject playerState(BattleRoom room, String username, boolean sideA) {
        JsonObject h = new JsonObject();
        h.addProperty("username", username);
        Hand hand = room.getHand(username);
        Thrust thrust = room.getThrust(username);
        h.addProperty("hasInput", hand != null);
        h.addProperty("hand", hand == null ? "NONE" : hand.name());
        h.addProperty("thrust", thrust == null ? "NONE" : thrust.name());
        h.addProperty("x", sideA ? room.getXA() : room.getXB());
        h.addProperty("vx", sideA ? room.getVA() : room.getVB());
        h.addProperty("lights", sideA ? room.getLightsA() : room.getLightsB());
        h.addProperty("side", sideA ? "A" : "B");
        return h;
    }

    private static JsonObject settleView(BattleRoom.SettleResult r,
                                        PlayerData dataA, PlayerData dataB,
                                        Long rankA, Long rankB) {
        JsonObject root = new JsonObject();
        root.addProperty("endReason", r.endReason().name());
        root.addProperty("lightsA", r.lightsA());
        root.addProperty("lightsB", r.lightsB());
        root.add("A", sideResult(roomSide("A", r, dataA, rankA)));
        root.add("B", sideResult(roomSide("B", r, dataB, rankB)));
        JsonObject byUser = new JsonObject();
        byUser.add(dataA.getUsername(), root.get("A"));
        byUser.add(dataB.getUsername(), root.get("B"));
        root.add("byUsername", byUser);
        return root;
    }

    private record SidePack(
            String username,
            Hand hand,
            BattleRoom.Outcome outcome,
            int eloBefore,
            EloCalculatorUpdate upd,
            int gamesBefore,
            int gamesAfter,
            boolean ranked,
            PlayerData after,
            Long rank,
            int lights
    ) {
    }

    private record EloCalculatorUpdate(int newElo, int delta, int kUsed) {
    }

    private static SidePack roomSide(String side, BattleRoom.SettleResult r, PlayerData after, Long rank) {
        if ("A".equals(side)) {
            return new SidePack(
                    after.getUsername(), r.handA(), r.outcomeA(), r.eloABefore(),
                    new EloCalculatorUpdate(r.updA().newElo(), r.updA().delta(), r.updA().kUsed()),
                    r.gamesABefore(), r.gamesAAfter(), r.rankedA(), after, rank, r.lightsA());
        }
        return new SidePack(
                after.getUsername(), r.handB(), r.outcomeB(), r.eloBBefore(),
                new EloCalculatorUpdate(r.updB().newElo(), r.updB().delta(), r.updB().kUsed()),
                r.gamesBBefore(), r.gamesBAfter(), r.rankedB(), after, rank, r.lightsB());
    }

    private static JsonObject sideResult(SidePack s) {
        JsonObject o = new JsonObject();
        o.addProperty("username", s.username());
        o.addProperty("hand", s.hand() == null ? "NONE" : s.hand().name());
        o.addProperty("outcome", s.outcome().name());
        o.addProperty("lights", s.lights());
        o.addProperty("gamesPlayed", s.gamesAfter());
        o.addProperty("gamesBefore", s.gamesBefore());
        o.addProperty("placementComplete", s.after().isPlacementComplete());
        o.addProperty("ranked", s.ranked());
        o.addProperty("kUsed", s.upd().kUsed());
        boolean showElo = s.gamesAfter() >= ServerConfig.PLACEMENT_GAMES;
        if (showElo) {
            o.addProperty("elo", s.upd().newElo());
            o.addProperty("eloDelta", s.upd().delta());
            o.addProperty("eloBefore", s.eloBefore());
            if (s.rank() != null) {
                o.addProperty("rank", s.rank());
            }
            o.addProperty("display", "elo");
        } else {
            o.add("elo", null);
            o.add("eloDelta", null);
            o.addProperty("display", "placement");
            o.addProperty("placementLabel",
                    "定级中 " + Math.min(s.gamesAfter(), ServerConfig.PLACEMENT_GAMES)
                            + "/" + ServerConfig.PLACEMENT_GAMES);
        }
        o.addProperty("eloReal", s.upd().newElo());
        o.addProperty("eloDeltaReal", s.upd().delta());
        return o;
    }

    public static String rankingTop100(List<RedisStore.RankingEntry> entries) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "ranking_top100");
        JsonArray arr = new JsonArray();
        for (RedisStore.RankingEntry e : entries) {
            JsonObject row = new JsonObject();
            row.addProperty("rank", e.rank());
            row.addProperty("username", e.username());
            row.addProperty("elo", e.elo());
            arr.add(row);
        }
        o.add("entries", arr);
        return o.toString();
    }

    public static String playerQuery(PlayerData data, Long rank) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "player_query");
        o.addProperty("username", data.getUsername());
        o.addProperty("eloReal", data.getElo());
        o.addProperty("gamesPlayed", data.getGamesPlayed());
        o.addProperty("placementComplete", data.isPlacementComplete());
        if (rank != null) {
            o.addProperty("rank", rank);
        }
        o.add("player", playerView(data, rank));
        return o.toString();
    }

    private Messages() {
    }
}
