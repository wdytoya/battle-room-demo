package org.example.gateway;

import com.google.gson.JsonObject;
import org.example.internal.InternalMessage;
import org.example.internal.InternalOps;
import org.example.internal.InternalRpcClient;

public final class LogicBridge {

    private final String gatewayId;
    private final InternalRpcClient logic;

    public LogicBridge(String gatewayId, InternalRpcClient logic) {
        this.gatewayId = gatewayId;
        this.logic = logic;
    }

    public String gatewayId() {
        return gatewayId;
    }

    public void forwardText(String connId, String text) {
        JsonObject p = new JsonObject();
        p.addProperty("connId", connId);
        p.addProperty("gatewayId", gatewayId);
        p.addProperty("text", text);
        logic.send(InternalMessage.of(InternalOps.CLIENT_TEXT, null, gatewayId, p));
    }

    public void forwardInactive(String connId, String reason) {
        JsonObject p = new JsonObject();
        p.addProperty("connId", connId);
        p.addProperty("gatewayId", gatewayId);
        p.addProperty("reason", reason == null ? "disconnect" : reason);
        logic.send(InternalMessage.of(InternalOps.CLIENT_INACTIVE, null, gatewayId, p));
    }
}
