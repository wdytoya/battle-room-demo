package org.example.logic;

import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import org.example.internal.InternalMessage;
import org.example.internal.InternalOps;
import org.example.session.ClientTransport;
import org.example.session.PlayerSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logic → Gateway push: deliver JSON / close by connId on the Gateway's RPC channel.
 */
public final class GatewayFanoutTransport implements ClientTransport {

    private final Map<String, Channel> gatewayChannels = new ConcurrentHashMap<>();

    public void registerGateway(String gatewayId, Channel channel) {
        gatewayChannels.put(gatewayId, channel);
    }

    /**
     * @return gatewayId that was bound to this channel, or null if unknown
     */
    public String unregisterGateway(Channel channel) {
        String removedId = null;
        for (var e : gatewayChannels.entrySet()) {
            if (e.getValue() == channel) {
                removedId = e.getKey();
                gatewayChannels.remove(e.getKey(), channel);
                break;
            }
        }
        return removedId;
    }

    public void closeConn(String gatewayId, String connId) {
        Channel ch = gatewayChannels.get(gatewayId);
        if (ch == null || !ch.isActive()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("connId", connId);
        ch.writeAndFlush(InternalMessage.of(InternalOps.CLIENT_CLOSE, null, "logic", payload));
    }

    @Override
    public void deliver(PlayerSession session, String json) {
        if (session == null) {
            return;
        }
        deliverTo(session.getGatewayId(), session.getConnId(), json);
    }

    @Override
    public void deliverTo(String gatewayId, String connId, String json) {
        if (gatewayId == null || connId == null) {
            return;
        }
        Channel ch = gatewayChannels.get(gatewayId);
        if (ch == null || !ch.isActive()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("connId", connId);
        payload.addProperty("json", json);
        ch.writeAndFlush(InternalMessage.of(InternalOps.CLIENT_SEND, null, "logic", payload));
    }
}
