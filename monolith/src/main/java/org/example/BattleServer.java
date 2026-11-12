package org.example;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.example.config.ServerConfig;
import org.example.netty.BattleServerInitializer;
import org.example.redis.RedisStore;
import org.example.service.GameService;
import org.example.session.LocalNettyTransport;

/**
 * Legacy single-process entry (HTTP static + WebSocket + game loop).
 * Prefer Gateway + Logic for the multi-process path.
 */
public final class BattleServer {

    public static void main(String[] args) throws Exception {
        System.out.println("Connecting Redis " + ServerConfig.REDIS_HOST + ":" + ServerConfig.REDIS_PORT + " ...");
        RedisStore redis = new RedisStore(ServerConfig.REDIS_HOST, ServerConfig.REDIS_PORT);
        redis.ping();
        System.out.println("Redis OK");

        LocalNettyTransport transport = new LocalNettyTransport();
        GameService gameService = new GameService(redis, transport, old -> transport.close(old.getConnId()));

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new BattleServerInitializer(gameService, transport));

            Channel ch = b.bind(ServerConfig.SERVER_PORT).sync().channel();
            System.out.println("BattleServer (monolith) started on http://localhost:" + ServerConfig.SERVER_PORT + "/");
            System.out.println("WebSocket endpoint: ws://localhost:" + ServerConfig.SERVER_PORT + ServerConfig.WS_PATH);
            System.out.println("Playing duration=" + ServerConfig.PLAYING_DURATION_MS + "ms, tick="
                    + ServerConfig.TICK_HZ + "Hz, matchScan=" + ServerConfig.MATCH_SCAN_INTERVAL_MS + "ms");
            ch.closeFuture().sync();
        } finally {
            gameService.close();
            redis.close();
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }

    private BattleServer() {
    }
}
