package org.example.internal;

import com.google.gson.JsonObject;

/**
 * Minimal self-check without JUnit: run via {@code main}.
 */
public final class InternalJsonTest {

    public static void main(String[] args) {
        JsonObject payload = new JsonObject();
        payload.addProperty("t", 42L);
        InternalMessage original = InternalMessage.of(InternalOps.SYS_PING, "abc123", "gateway", payload);
        String json = InternalJson.toJson(original);
        InternalMessage parsed = InternalJson.fromJson(json);
        if (!InternalOps.SYS_PING.equals(parsed.getOp())) {
            throw new IllegalStateException("op mismatch");
        }
        if (!"abc123".equals(parsed.getReqId())) {
            throw new IllegalStateException("reqId mismatch");
        }
        if (parsed.getPayload().get("t").getAsLong() != 42L) {
            throw new IllegalStateException("payload mismatch");
        }
        System.out.println("InternalJsonTest OK: " + json);
    }

    private InternalJsonTest() {
    }
}
