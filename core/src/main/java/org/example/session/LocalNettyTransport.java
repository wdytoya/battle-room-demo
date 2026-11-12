package org.example.session;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process delivery: connId → Netty Channel (monolith).
 */
public final class LocalNettyTransport implements ClientTransport {

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public void register(String connId, Channel channel) {
        channels.put(connId, channel);
    }

    public void unregister(String connId) {
        channels.remove(connId);
    }

    public Channel get(String connId) {
        return channels.get(connId);
    }

    public void close(String connId) {
        Channel ch = channels.get(connId);
        if (ch != null && ch.isActive()) {
            ch.close();
        }
    }

    @Override
    public void deliver(PlayerSession session, String json) {
        if (session == null || session.getConnId() == null) {
            return;
        }
        Channel ch = channels.get(session.getConnId());
        if (ch == null) {
            ch = session.getChannel();
        }
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    @Override
    public void deliverTo(String gatewayId, String connId, String json) {
        if (connId == null) {
            return;
        }
        Channel ch = channels.get(connId);
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(new TextWebSocketFrame(json));
        }
    }
}
