package org.example.logic;

import com.google.gson.JsonObject;
import io.netty.channel.ChannelHandlerContext;
import org.example.config.ClusterConfig;
import org.example.config.ServerConfig;
import org.example.internal.InternalHandler;
import org.example.internal.InternalMessage;
import org.example.internal.InternalOps;
import org.example.internal.InternalRpcServer;
import org.example.redis.RedisStore;
import org.example.service.GameService;
import org.example.spi.ServiceRoles;

/**
 * Logic process: Redis + rooms/physics; Match is a separate process; no public client port.
 */
public final class LogicServer {

    public static void main(String[] args) throws Exception {
        System.out.println("[logic] Connecting Redis " + ServerConfig.REDIS_HOST + ":" + ServerConfig.REDIS_PORT + " ...");
        RedisStore redis = new RedisStore(ServerConfig.REDIS_HOST, ServerConfig.REDIS_PORT);
        redis.ping();
        System.out.println("[logic] Redis OK");

        GatewayFanoutTransport transport = new GatewayFanoutTransport();
        // Logic uses remote Match + Physics (monolith embeds both).
        GameService game = new GameService(redis, transport, old -> {
            System.out.println("[logic] kick old conn user=" + old.getUsername()
                    + " connId=" + old.getConnId() + " gateway=" + old.getGatewayId());
            transport.closeConn(old.getGatewayId(), old.getConnId());
        }, true, true);

        InternalHandler handler = (ctx, msg) -> onInternal(ctx, msg, game, transport);

        InternalRpcServer server = new InternalRpcServer(
                ServiceRoles.LOGIC,
                ClusterConfig.BIND_HOST,
                ClusterConfig.LOGIC_RPC_PORT,
                handler);
        server.start();
        System.out.println("[logic] tick=" + ServerConfig.TICK_HZ + "Hz playingMs=" + ServerConfig.PLAYING_DURATION_MS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            game.close();
            redis.close();
            server.close();
        }));
        server.channel().closeFuture().sync();
    }

    private static void onInternal(ChannelHandlerContext ctx, InternalMessage msg,
                                   GameService game, GatewayFanoutTransport transport) {
        String op = msg.getOp();
        JsonObject p = msg.getPayload();
        switch (op) {
            case InternalOps.SYS_PING -> {
                JsonObject payload = new JsonObject();
                payload.addProperty("service", ServiceRoles.LOGIC);
                payload.addProperty("echoAtMs", System.currentTimeMillis());
                if (p.has("t")) {
                    payload.add("t", p.get("t"));
                }
                ctx.writeAndFlush(msg.reply(InternalOps.SYS_PONG, payload));
            }
            case InternalOps.SYS_HELLO -> {
                String gatewayId = p.has("gatewayId") ? p.get("gatewayId").getAsString() : msg.getFrom();
                transport.registerGateway(gatewayId, ctx.channel());
                ctx.channel().closeFuture().addListener(f -> {
                    String lostId = transport.unregisterGateway(ctx.channel());
                    if (lostId != null) {
                        System.out.println("[logic] gateway channel lost id=" + lostId
                                + " → soft-disconnect sessions (keep reconnect tokens)");
                        game.softDisconnectGateway(lostId);
                    }
                });
                JsonObject ok = new JsonObject();
                ok.addProperty("service", ServiceRoles.LOGIC);
                ctx.writeAndFlush(msg.reply(InternalOps.SYS_HELLO, ok));
                System.out.println("[logic] gateway registered id=" + gatewayId);
            }
            case InternalOps.CLIENT_TEXT -> {
                game.handleText(
                        p.get("connId").getAsString(),
                        p.get("gatewayId").getAsString(),
                        p.get("text").getAsString());
            }
            case InternalOps.CLIENT_INACTIVE -> {
                String reason = p.has("reason") ? p.get("reason").getAsString() : "disconnect";
                game.onConnInactive(p.get("connId").getAsString(), reason);
            }
            default -> System.out.println("[logic] unknown op=" + op);
        }
    }

    private LogicServer() {
    }
}
