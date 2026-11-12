package org.example.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;

public final class GatewayWsHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private final LogicBridge bridge;
    private final ClientConnRegistry registry;
    private final HeartbeatService heartbeat;
    private final WebSocketServerHandshaker handshaker;
    private final String connId;

    public GatewayWsHandler(LogicBridge bridge, ClientConnRegistry registry, HeartbeatService heartbeat,
                            WebSocketServerHandshaker handshaker, String connId) {
        this.bridge = bridge;
        this.registry = registry;
        this.heartbeat = heartbeat;
        this.handshaker = handshaker;
        this.connId = connId;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame closeFrame) {
            handshaker.close(ctx.channel(), closeFrame.retain());
            return;
        }
        if (frame instanceof PingWebSocketFrame ping) {
            heartbeat.touch(connId);
            ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            return;
        }
        if (frame instanceof TextWebSocketFrame text) {
            heartbeat.touch(connId);
            String payload = text.text();
            if (tryReplyAppPing(ctx, payload)) {
                return;
            }
            bridge.forwardText(connId, payload);
        }
    }

    /** @return true if this was an app-level ping and a pong was written */
    private static boolean tryReplyAppPing(ChannelHandlerContext ctx, String payload) {
        final JsonObject o;
        try {
            o = JsonParser.parseString(payload).getAsJsonObject();
        } catch (Exception e) {
            return false;
        }
        if (!o.has("type") || !"ping".equals(o.get("type").getAsString())) {
            return false;
        }
        long t = o.has("t") ? o.get("t").getAsLong() : System.currentTimeMillis();
        JsonObject pong = new JsonObject();
        pong.addProperty("type", "pong");
        pong.addProperty("t", t);
        pong.addProperty("serverTimeMs", System.currentTimeMillis());
        ctx.writeAndFlush(new TextWebSocketFrame(pong.toString()));
        return true;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        heartbeat.remove(connId);
        registry.unregister(connId);
        if (heartbeat.consumeHeartbeatClose(connId)) {
            // Heartbeat path already forwarded CLIENT_INACTIVE(heartbeat) → abandon.
            return;
        }
        bridge.forwardInactive(connId, "disconnect");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        String msg = cause.getMessage();
        if (msg == null || !(msg.contains("Connection reset") || msg.contains("远程主机强迫关闭"))) {
            System.err.println("[gateway-ws] " + msg);
        }
        ctx.close();
    }
}
