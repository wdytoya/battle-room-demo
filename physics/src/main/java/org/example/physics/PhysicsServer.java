package org.example.physics;

import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.example.config.ClusterConfig;
import org.example.config.ServerConfig;
import org.example.internal.InternalHandler;
import org.example.internal.InternalMessage;
import org.example.internal.InternalOps;
import org.example.internal.InternalRpcServer;
import org.example.model.Hand;
import org.example.model.RoomPhase;
import org.example.model.Thrust;
import org.example.room.BattleRoom;
import org.example.room.PhysicsTickCodec;
import org.example.spi.ServiceRoles;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Physics process: fixed tick loop, no public client port. Logic is the only peer.
 */
public final class PhysicsServer {

    public static void main(String[] args) throws Exception {
        Map<String, BattleRoom> rooms = new ConcurrentHashMap<>();
        AtomicReference<Channel> logicChannel = new AtomicReference<>();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "physics-tick");
            t.setDaemon(true);
            return t;
        });
        long tickMs = ServerConfig.tickIntervalMs();
        scheduler.scheduleAtFixedRate(() -> tickAll(rooms, logicChannel.get(), tickMs),
                tickMs, tickMs, TimeUnit.MILLISECONDS);

        InternalHandler handler = (ctx, msg) -> onMessage(ctx, msg, rooms, logicChannel);
        InternalRpcServer server = new InternalRpcServer(
                ServiceRoles.PHYSICS,
                ClusterConfig.BIND_HOST,
                ClusterConfig.PHYSICS_RPC_PORT,
                handler);
        server.start();
        System.out.println("[physics] tick=" + ServerConfig.TICK_HZ + "Hz intervalMs=" + tickMs);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            server.close();
        }));
        server.channel().closeFuture().sync();
    }

    private static void onMessage(ChannelHandlerContext ctx, InternalMessage msg,
                                  Map<String, BattleRoom> rooms, AtomicReference<Channel> logicChannel) {
        JsonObject p = msg.getPayload();
        switch (msg.getOp()) {
            case InternalOps.SYS_PING -> {
                JsonObject payload = new JsonObject();
                payload.addProperty("service", ServiceRoles.PHYSICS);
                payload.addProperty("echoAtMs", System.currentTimeMillis());
                if (p.has("t")) {
                    payload.add("t", p.get("t"));
                }
                ctx.writeAndFlush(msg.reply(InternalOps.SYS_PONG, payload));
            }
            case InternalOps.SYS_HELLO -> {
                logicChannel.set(ctx.channel());
                ctx.channel().closeFuture().addListener(f -> logicChannel.compareAndSet(ctx.channel(), null));
                JsonObject ok = new JsonObject();
                ok.addProperty("service", ServiceRoles.PHYSICS);
                ctx.writeAndFlush(msg.reply(InternalOps.SYS_HELLO, ok));
                System.out.println("[physics] logic registered");
            }
            case InternalOps.PHYS_ROOM_CREATE -> {
                String roomId = p.get("roomId").getAsString();
                BattleRoom room = new BattleRoom(roomId, p.get("playerA").getAsString(), p.get("playerB").getAsString());
                room.startPlaying(p.get("playingStartMs").getAsLong());
                rooms.put(roomId, room);
                JsonObject ok = new JsonObject();
                ok.addProperty("ok", true);
                ctx.writeAndFlush(msg.reply(InternalOps.PHYS_ROOM_CREATE, ok));
            }
            case InternalOps.PHYS_ROOM_DESTROY -> {
                String roomId = p.get("roomId").getAsString();
                BattleRoom room = rooms.remove(roomId);
                if (room != null) {
                    room.close();
                }
                JsonObject ok = new JsonObject();
                ok.addProperty("ok", true);
                ctx.writeAndFlush(msg.reply(InternalOps.PHYS_ROOM_DESTROY, ok));
            }
            case InternalOps.PHYS_INPUT_HAND -> {
                BattleRoom room = rooms.get(p.get("roomId").getAsString());
                if (room != null) {
                    Hand hand = Hand.parse(p.get("hand").getAsString());
                    room.inputHand(p.get("username").getAsString(), hand);
                }
            }
            case InternalOps.PHYS_INPUT_THRUST -> {
                BattleRoom room = rooms.get(p.get("roomId").getAsString());
                if (room != null) {
                    Thrust thrust = Thrust.parse(p.get("thrust").getAsString());
                    room.inputThrust(p.get("username").getAsString(), thrust);
                }
            }
            default -> System.out.println("[physics] unknown op=" + msg.getOp());
        }
    }

    private static void tickAll(Map<String, BattleRoom> rooms, Channel logic, long tickMs) {
        if (logic == null || !logic.isActive()) {
            return;
        }
        long now = System.currentTimeMillis();
        long started = System.nanoTime();
        int n = 0;
        for (BattleRoom room : rooms.values()) {
            if (room.getPhase() != RoomPhase.PLAYING) {
                continue;
            }
            boolean settle = room.simulateTick(now);
            logic.writeAndFlush(InternalMessage.of(
                    InternalOps.PHYS_TICK_RESULT, null, ServiceRoles.PHYSICS,
                    PhysicsTickCodec.tickResult(room, settle, now, tickMs)));
            n++;
            if (settle) {
                // Logic will destroy; keep simulating until destroy arrives (lights stay).
            }
        }
        long costMs = (System.nanoTime() - started) / 1_000_000L;
        if (n > 0 && costMs > tickMs) {
            System.out.println("[physics] tick slow rooms=" + n + " costMs=" + costMs + " budgetMs=" + tickMs);
        }
    }

    private PhysicsServer() {
    }
}
