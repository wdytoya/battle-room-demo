package org.example.gateway;

import com.google.gson.JsonObject;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.example.config.ClusterConfig;
import org.example.internal.InternalMessage;
import org.example.internal.InternalOps;
import org.example.internal.InternalRpcClient;
import org.example.spi.ServiceRoles;

import java.util.UUID;

/**
 * Client-facing Gateway: HTTP static + WebSocket; forwards to Logic over internal TCP.
 */
public final class GatewayServer {

    public static void main(String[] args) throws Exception {
        String gatewayId = System.getProperty("battle.gateway.id",
                "gw-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));

        InternalRpcClient logic = new InternalRpcClient(
                "gateway->" + ServiceRoles.LOGIC,
                ClusterConfig.LOGIC_HOST,
                ClusterConfig.LOGIC_RPC_PORT);

        ClientConnRegistry registry = new ClientConnRegistry();
        logic.setPushHandler((ctx, msg) -> registry.onLogicPush(msg));

        logic.connect();
        JsonObject hello = new JsonObject();
        hello.addProperty("gatewayId", gatewayId);
        InternalMessage helloAck = logic.request(InternalOps.SYS_HELLO, hello, 5000);
        System.out.println("[gateway] hello logic service="
                + (helloAck.getPayload().has("service") ? helloAck.getPayload().get("service").getAsString() : "?"));

        InternalMessage pong = logic.ping(3000);
        if (!InternalOps.SYS_PONG.equals(pong.getOp())) {
            throw new IllegalStateException("logic ping failed");
        }
        System.out.println("[gateway] logic sys.pong OK id=" + gatewayId);

        LogicBridge bridge = new LogicBridge(gatewayId, logic);
        HeartbeatService heartbeat = new HeartbeatService(registry, connId -> {
            // Timeout path: channel close triggers inactive; also notify Logic with heartbeat reason.
            bridge.forwardInactive(connId, "heartbeat");
            registry.unregister(connId);
        });

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new GatewayInitializer(bridge, registry, heartbeat));

            Channel ch = b.bind(ClusterConfig.GATEWAY_HTTP_PORT).sync().channel();
            System.out.println("[gateway] HTTP/WS on http://localhost:" + ClusterConfig.GATEWAY_HTTP_PORT + "/");
            System.out.println("[gateway] WebSocket " + ClusterConfig.WS_PATH + " → logic "
                    + ClusterConfig.LOGIC_HOST + ":" + ClusterConfig.LOGIC_RPC_PORT);
            worker.scheduleAtFixedRate(() ->
                            System.out.println("[gateway] metrics connections=" + registry.connectionCount()),
                    30, 30, java.util.concurrent.TimeUnit.SECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                heartbeat.close();
                logic.close();
                boss.shutdownGracefully();
                worker.shutdownGracefully();
            }));
            ch.closeFuture().sync();
        } finally {
            heartbeat.close();
            logic.close();
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }

    private GatewayServer() {
    }
}
