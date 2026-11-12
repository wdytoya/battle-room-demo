package org.example.gateway;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.example.internal.InternalMessage;
import org.example.internal.InternalOps;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientConnRegistry {

    private final AtomicLong seq = new AtomicLong();
    private final Map<String, Channel> byConnId = new ConcurrentHashMap<>();

    public String register(Channel channel) {
        String connId = "c" + seq.incrementAndGet();
        byConnId.put(connId, channel);
        return connId;
    }

    public void unregister(String connId) {
        if (connId != null) {
            byConnId.remove(connId);
        }
    }

    public Channel get(String connId) {
        return byConnId.get(connId);
    }

    public int connectionCount() {
        return byConnId.size();
    }

    public void onLogicPush(InternalMessage msg) {
        String op = msg.getOp();
        if (InternalOps.CLIENT_SEND.equals(op)) {
            String connId = msg.getPayload().get("connId").getAsString();
            String json = msg.getPayload().get("json").getAsString();
            Channel ch = byConnId.get(connId);
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(new TextWebSocketFrame(json));
            }
            return;
        }
        if (InternalOps.CLIENT_CLOSE.equals(op)) {
            String connId = msg.getPayload().get("connId").getAsString();
            Channel ch = byConnId.get(connId);
            if (ch != null && ch.isActive()) {
                ch.close();
            }
        }
    }
}
