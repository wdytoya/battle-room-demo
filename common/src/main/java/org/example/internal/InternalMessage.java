package org.example.internal;

import com.google.gson.JsonObject;

/**
 * Length-prefixed JSON frame body for localhost process RPC.
 */
public final class InternalMessage {

    private final String op;
    private final String reqId;
    private final String from;
    private final JsonObject payload;

    public InternalMessage(String op, String reqId, String from, JsonObject payload) {
        this.op = op;
        this.reqId = reqId;
        this.from = from;
        this.payload = payload == null ? new JsonObject() : payload;
    }

    public String getOp() {
        return op;
    }

    public String getReqId() {
        return reqId;
    }

    public String getFrom() {
        return from;
    }

    public JsonObject getPayload() {
        return payload;
    }

    public static InternalMessage of(String op, String reqId, String from) {
        return new InternalMessage(op, reqId, from, new JsonObject());
    }

    public static InternalMessage of(String op, String reqId, String from, JsonObject payload) {
        return new InternalMessage(op, reqId, from, payload);
    }

    public InternalMessage reply(String op, JsonObject payload) {
        return new InternalMessage(op, reqId, from, payload);
    }
}
