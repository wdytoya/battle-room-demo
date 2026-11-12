package org.example.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class InternalJson {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static String toJson(InternalMessage msg) {
        JsonObject o = new JsonObject();
        o.addProperty("op", msg.getOp());
        if (msg.getReqId() != null) {
            o.addProperty("reqId", msg.getReqId());
        }
        if (msg.getFrom() != null) {
            o.addProperty("from", msg.getFrom());
        }
        o.add("payload", msg.getPayload());
        return GSON.toJson(o);
    }

    public static InternalMessage fromJson(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String op = o.has("op") ? o.get("op").getAsString() : "";
        String reqId = o.has("reqId") && !o.get("reqId").isJsonNull() ? o.get("reqId").getAsString() : null;
        String from = o.has("from") && !o.get("from").isJsonNull() ? o.get("from").getAsString() : null;
        JsonObject payload = o.has("payload") && o.get("payload").isJsonObject()
                ? o.getAsJsonObject("payload")
                : new JsonObject();
        return new InternalMessage(op, reqId, from, payload);
    }

    private InternalJson() {
    }
}
