package org.example.internal;

import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbound internal RPC connection with request/response correlation via reqId.
 */
public final class InternalRpcClient implements AutoCloseable {

    private final String clientName;
    private final String host;
    private final int port;

    private EventLoopGroup group;
    private Channel channel;
    private final Map<String, CompletableFuture<InternalMessage>> pending = new ConcurrentHashMap<>();
    private volatile InternalHandler pushHandler;

    public InternalRpcClient(String clientName, String host, int port) {
        this.clientName = clientName;
        this.host = host;
        this.port = port;
    }

    public void setPushHandler(InternalHandler pushHandler) {
        this.pushHandler = pushHandler;
    }

    public void connect() throws InterruptedException {
        group = new NioEventLoopGroup(1);
        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        InternalPipeline.addCodec(ch.pipeline());
                        ch.pipeline().addLast(new InternalNettyHandler(clientName, InternalRpcClient.this::onInbound));
                    }
                });
        channel = b.connect(host, port).sync().channel();
        System.out.println("[" + clientName + "] connected to " + host + ":" + port);
    }

    private void onInbound(io.netty.channel.ChannelHandlerContext ctx, InternalMessage msg) throws Exception {
        if (msg.getReqId() != null) {
            CompletableFuture<InternalMessage> f = pending.remove(msg.getReqId());
            if (f != null) {
                f.complete(msg);
                return;
            }
        }
        InternalHandler push = pushHandler;
        if (push != null) {
            push.onMessage(ctx, msg);
            return;
        }
        System.out.println("[" + clientName + "] unsolicited op=" + msg.getOp());
    }

    public void send(InternalMessage msg) {
        ensureConnected();
        channel.writeAndFlush(msg);
    }

    public Channel channel() {
        return channel;
    }

    public InternalMessage request(String op, JsonObject payload, long timeoutMs) throws Exception {
        ensureConnected();
        String reqId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        CompletableFuture<InternalMessage> future = new CompletableFuture<>();
        pending.put(reqId, future);
        channel.writeAndFlush(InternalMessage.of(op, reqId, clientName, payload == null ? new JsonObject() : payload));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(reqId);
            throw new TimeoutException("RPC timeout op=" + op + " to " + host + ":" + port);
        }
    }

    public InternalMessage ping(long timeoutMs) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("t", System.currentTimeMillis());
        return request(InternalOps.SYS_PING, payload, timeoutMs);
    }

    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    private void ensureConnected() {
        if (!isActive()) {
            throw new IllegalStateException("Not connected to " + host + ":" + port);
        }
    }

    @Override
    public void close() {
        pending.forEach((id, f) -> f.completeExceptionally(new IllegalStateException("client closed")));
        pending.clear();
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }
}
