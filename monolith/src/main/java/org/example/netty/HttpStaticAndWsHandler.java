package org.example.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.CharsetUtil;
import org.example.config.ServerConfig;
import org.example.service.ClientMessageHandler;
import org.example.session.LocalNettyTransport;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public final class HttpStaticAndWsHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final AtomicLong CONN_SEQ = new AtomicLong();

    private final ClientMessageHandler gameService;
    private final LocalNettyTransport transport;
    private WebSocketServerHandshaker handshaker;

    public HttpStaticAndWsHandler(ClientMessageHandler gameService, LocalNettyTransport transport) {
        this.gameService = gameService;
        this.transport = transport;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        if (!req.decoderResult().isSuccess()) {
            sendText(ctx, HttpResponseStatus.BAD_REQUEST, "Bad Request");
            return;
        }
        if (req.method() != HttpMethod.GET) {
            sendText(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "GET only");
            return;
        }

        String uri = req.uri();
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        path = URLDecoder.decode(path, StandardCharsets.UTF_8);

        if (ServerConfig.WS_PATH.equals(path)) {
            WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                    getWebSocketLocation(req), null, true, 64 * 1024);
            handshaker = wsFactory.newHandshaker(req);
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            } else {
                handshaker.handshake(ctx.channel(), req);
                String connId = "m" + CONN_SEQ.incrementAndGet();
                transport.register(connId, ctx.channel());
                ctx.pipeline().replace(this, "wsHandler",
                        new WebSocketFrameHandler(gameService, transport, handshaker, connId));
            }
            return;
        }

        serveStatic(ctx, req, path);
    }

    private void serveStatic(ChannelHandlerContext ctx, FullHttpRequest req, String path) throws Exception {
        String resourcePath;
        if ("/".equals(path) || path.isEmpty()) {
            resourcePath = "static/index.html";
        } else {
            if (path.contains("..")) {
                sendText(ctx, HttpResponseStatus.FORBIDDEN, "Forbidden");
                return;
            }
            resourcePath = "static" + path;
        }

        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            sendText(ctx, HttpResponseStatus.NOT_FOUND, "Not Found: " + path);
            return;
        }
        byte[] bytes;
        try (in) {
            bytes = in.readAllBytes();
        }

        DefaultFullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType(resourcePath));
        HttpUtil.setContentLength(res, bytes.length);
        if (HttpUtil.isKeepAlive(req)) {
            res.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(res);
        } else {
            ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "application/octet-stream";
    }

    private static String getWebSocketLocation(FullHttpRequest req) {
        String location = req.headers().get(HttpHeaderNames.HOST) + ServerConfig.WS_PATH;
        return "ws://" + location;
    }

    private static void sendText(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        DefaultFullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        HttpUtil.setContentLength(res, body.getBytes(StandardCharsets.UTF_8).length);
        ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
