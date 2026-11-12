package org.example.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.example.service.ClientMessageHandler;
import org.example.session.LocalNettyTransport;

public final class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final String LOCAL_GATEWAY = "monolith";

    private final ClientMessageHandler gameService;
    private final LocalNettyTransport transport;
    private final WebSocketServerHandshaker handshaker;
    private final String connId;

    public WebSocketFrameHandler(ClientMessageHandler gameService, LocalNettyTransport transport,
                                 WebSocketServerHandshaker handshaker, String connId) {
        this.gameService = gameService;
        this.transport = transport;
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
            ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            return;
        }
        if (frame instanceof TextWebSocketFrame text) {
            gameService.handleText(connId, LOCAL_GATEWAY, text.text());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        transport.unregister(connId);
        gameService.onConnInactive(connId);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        String msg = cause.getMessage();
        if (msg == null || !(msg.contains("Connection reset") || msg.contains("远程主机强迫关闭"))) {
            System.err.println("[WS] error: " + msg);
        }
        ctx.close();
    }
}
