package org.example.session;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {

    public static final AttributeKey<String> ATTR_USERNAME = AttributeKey.valueOf("username");

    private final Map<String, PlayerSession> byUsername = new ConcurrentHashMap<>();
    private final Map<Channel, String> byChannel = new ConcurrentHashMap<>();

    public PlayerSession getByUsername(String username) {
        return byUsername.get(username);
    }

    public PlayerSession getByChannel(Channel channel) {
        String u = byChannel.get(channel);
        return u == null ? null : byUsername.get(u);
    }

    /**
     * Bind username to channel. If username already online, kick old connection.
     */
    public PlayerSession bind(String username, Channel channel, PlayerSession existingOrNew) {
        PlayerSession prev = byUsername.put(username, existingOrNew);
        if (prev != null && prev.getChannel() != null && prev.getChannel() != channel) {
            prev.markInactive();
            Channel old = prev.getChannel();
            byChannel.remove(old, username);
            sendQuiet(old, "{\"type\":\"error\",\"code\":\"KICKED\",\"message\":\"同一用户名在其他连接登录，本连接已失效\"}");
            old.close();
        }
        existingOrNew.setChannel(channel);
        existingOrNew.markActive();
        byChannel.put(channel, username);
        channel.attr(ATTR_USERNAME).set(username);
        return existingOrNew;
    }

    public void unbindChannel(Channel channel) {
        String username = byChannel.remove(channel);
        if (username == null) {
            return;
        }
        PlayerSession session = byUsername.get(username);
        if (session != null && session.isChannel(channel)) {
            // Keep session object for queue/room rebind; clear only channel if still same.
            session.setChannel(null);
        }
        channel.attr(ATTR_USERNAME).set(null);
    }

    public void send(PlayerSession session, String json) {
        if (session == null) {
            return;
        }
        Channel ch = session.getChannel();
        if (ch != null && ch.isActive() && session.isActive()) {
            ch.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    private static void sendQuiet(Channel ch, String json) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(new TextWebSocketFrame(json)).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
