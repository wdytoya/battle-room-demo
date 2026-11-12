package org.example.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.example.service.ClientMessageHandler;
import org.example.session.LocalNettyTransport;

public final class BattleServerInitializer extends ChannelInitializer<SocketChannel> {

    private final ClientMessageHandler gameService;
    private final LocalNettyTransport transport;

    public BattleServerInitializer(ClientMessageHandler gameService, LocalNettyTransport transport) {
        this.gameService = gameService;
        this.transport = transport;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        p.addLast(new HttpServerCodec());
        p.addLast(new HttpObjectAggregator(65536));
        p.addLast(new ChunkedWriteHandler());
        p.addLast(new HttpStaticAndWsHandler(gameService, transport));
    }
}
