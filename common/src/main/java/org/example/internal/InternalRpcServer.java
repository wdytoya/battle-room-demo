package org.example.internal;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Localhost internal RPC listener (Match / Logic / Physics).
 */
public final class InternalRpcServer implements AutoCloseable {

    private final String serviceName;
    private final String bindHost;
    private final int port;
    private final InternalHandler handler;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;

    public InternalRpcServer(String serviceName, String bindHost, int port, InternalHandler handler) {
        this.serviceName = serviceName;
        this.bindHost = bindHost;
        this.port = port;
        this.handler = handler;
    }

    public void start() throws InterruptedException {
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        InternalPipeline.addCodec(ch.pipeline());
                        ch.pipeline().addLast(new InternalNettyHandler(serviceName, handler));
                    }
                });
        serverChannel = b.bind(bindHost, port).sync().channel();
        System.out.println("[" + serviceName + "] internal RPC listening on " + bindHost + ":" + port);
    }

    public Channel channel() {
        return serverChannel;
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (boss != null) {
            boss.shutdownGracefully();
        }
        if (worker != null) {
            worker.shutdownGracefully();
        }
    }
}
