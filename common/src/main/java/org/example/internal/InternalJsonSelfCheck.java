package org.example.internal;

import com.google.gson.JsonObject;

/** Phase 0/1 self-check for internal JSON codec. */
public final class InternalJsonSelfCheck {

    public static void main(String[] args) {
        JsonObject payload = new JsonObject();
        payload.addProperty("t", 42L);
        InternalMessage original = InternalMessage.of(InternalOps.SYS_PING, "abc123", "gateway", payload);
        InternalMessage parsed = InternalJson.fromJson(InternalJson.toJson(original));
        if (!InternalOps.SYS_PING.equals(parsed.getOp())
                || !"abc123".equals(parsed.getReqId())
                || parsed.getPayload().get("t").getAsLong() != 42L) {
            throw new IllegalStateException("InternalJsonSelfCheck failed");
        }
        System.out.println("InternalJsonSelfCheck OK");
    }

    private InternalJsonSelfCheck() {
    }
}
