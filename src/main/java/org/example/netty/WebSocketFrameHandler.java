package org.example.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.example.service.GameService;

public final class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private final GameService gameService;
    private final WebSocketServerHandshaker handshaker;

    public WebSocketFrameHandler(GameService gameService, WebSocketServerHandshaker handshaker) {
        this.gameService = gameService;
        this.handshaker = handshaker;
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
            gameService.handleText(ctx.channel(), text.text());
            return;
        }
        // Binary / other: ignore
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        gameService.onChannelInactive(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        String msg = cause.getMessage();
        if (msg == null || !(msg.contains("Connection reset") || msg.contains("远程主机强迫关闭"))) {
            System.err.println("[WS] error: " + msg);
            cause.printStackTrace(System.err);
        }
        ctx.close();
    }
}
