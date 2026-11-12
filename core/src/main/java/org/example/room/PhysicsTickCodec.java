package org.example.room;

import com.google.gson.JsonObject;
import org.example.model.Hand;
import org.example.model.Thrust;

/** Encode/decode physics tick payloads for Logic ↔ Physics RPC. */
public final class PhysicsTickCodec {

    public static JsonObject roomCreate(BattleRoom room) {
        JsonObject p = new JsonObject();
        p.addProperty("roomId", room.getRoomId());
        p.addProperty("playerA", room.getPlayerA());
        p.addProperty("playerB", room.getPlayerB());
        p.addProperty("playingStartMs", room.getPlayingStartMs());
        return p;
    }

    public static JsonObject tickResult(BattleRoom room, boolean shouldSettle, long now, long tickMs) {
        JsonObject p = new JsonObject();
        p.addProperty("roomId", room.getRoomId());
        p.addProperty("tick", room.getTick());
        p.addProperty("now", now);
        p.addProperty("tickMs", tickMs);
        p.addProperty("shouldSettle", shouldSettle);
        p.addProperty("xA", room.getXA());
        p.addProperty("vA", room.getVA());
        p.addProperty("xB", room.getXB());
        p.addProperty("vB", room.getVB());
        p.addProperty("lightsA", room.getLightsA());
        p.addProperty("lightsB", room.getLightsB());
        p.addProperty("handA", room.getHand(room.getPlayerA()) == null ? "NONE" : room.getHand(room.getPlayerA()).name());
        p.addProperty("handB", room.getHand(room.getPlayerB()) == null ? "NONE" : room.getHand(room.getPlayerB()).name());
        p.addProperty("thrustA", room.getThrust(room.getPlayerA()).name());
        p.addProperty("thrustB", room.getThrust(room.getPlayerB()).name());
        p.addProperty("endReason", room.getEndReason().name());
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
            p.add("lastCollision", c);
        }
        return p;
    }

    public static void applyTick(BattleRoom room, JsonObject p) {
        BattleRoom.CollisionEvent collision = null;
        if (p.has("lastCollision") && p.get("lastCollision").isJsonObject()) {
            JsonObject c = p.getAsJsonObject("lastCollision");
            collision = new BattleRoom.CollisionEvent(
                    c.get("tick").getAsLong(),
                    c.get("atMs").getAsLong(),
                    c.get("handA").getAsString(),
                    c.get("handB").getAsString(),
                    c.get("rps").getAsInt(),
                    c.has("winner") && !c.get("winner").isJsonNull() ? c.get("winner").getAsString() : null,
                    c.has("loser") && !c.get("loser").isJsonNull() ? c.get("loser").getAsString() : null,
                    c.get("lightsA").getAsInt(),
                    c.get("lightsB").getAsInt());
        }
        BattleRoom.EndReason end = BattleRoom.EndReason.NONE;
        if (p.has("endReason")) {
            try {
                end = BattleRoom.EndReason.valueOf(p.get("endReason").getAsString());
            } catch (Exception ignored) {
            }
        }
        room.applyPhysicsTick(
                p.get("tick").getAsLong(),
                p.get("xA").getAsDouble(),
                p.get("vA").getAsDouble(),
                p.get("xB").getAsDouble(),
                p.get("vB").getAsDouble(),
                p.get("lightsA").getAsInt(),
                p.get("lightsB").getAsInt(),
                parseHand(p.get("handA").getAsString()),
                parseHand(p.get("handB").getAsString()),
                Thrust.parse(p.get("thrustA").getAsString()),
                Thrust.parse(p.get("thrustB").getAsString()),
                collision,
                end);
    }

    private static Hand parseHand(String raw) {
        if (raw == null || "NONE".equals(raw)) {
            return null;
        }
        return Hand.parse(raw);
    }

    private PhysicsTickCodec() {
    }
}
