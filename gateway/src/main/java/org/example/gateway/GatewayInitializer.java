package org.example.gateway;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;

public final class GatewayInitializer extends ChannelInitializer<SocketChannel> {

    private final LogicBridge bridge;
    private final ClientConnRegistry registry;
    private final HeartbeatService heartbeat;

    public GatewayInitializer(LogicBridge bridge, ClientConnRegistry registry, HeartbeatService heartbeat) {
        this.bridge = bridge;
        this.registry = registry;
        this.heartbeat = heartbeat;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        p.addLast(new HttpServerCodec());
        p.addLast(new HttpObjectAggregator(65536));
        p.addLast(new ChunkedWriteHandler());
        p.addLast(new GatewayHttpWsHandler(bridge, registry, heartbeat));
    }
}
