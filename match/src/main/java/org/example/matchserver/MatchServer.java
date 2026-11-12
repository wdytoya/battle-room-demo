package org.example.matchserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.example.config.ClusterConfig;
import org.example.config.ServerConfig;
import org.example.internal.InternalHandler;
import org.example.internal.InternalMessage;
import org.example.internal.InternalOps;
import org.example.internal.InternalRpcServer;
import org.example.match.MatchQueue;
import org.example.match.Matchmaker;
import org.example.match.QueueEntry;
import org.example.spi.ServiceRoles;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone matchmaking process. Logic connects and receives match.result / queue_status pushes.
 */
public final class MatchServer {

    public static void main(String[] args) throws Exception {
        MatchQueue queue = new MatchQueue();
        AtomicReference<Channel> logicChannel = new AtomicReference<>();

        Matchmaker matchmaker = new Matchmaker(queue, (a, b) -> {
            Channel ch = logicChannel.get();
            if (ch == null || !ch.isActive()) {
                System.err.println("[match] paired but no logic channel; re-queue skipped");
                return;
            }
            QueueEntry left = a.getQueueSeq() <= b.getQueueSeq() ? a : b;
            QueueEntry right = left == a ? b : a;
            JsonObject p = new JsonObject();
            p.addProperty("left", left.getUsername());
            p.addProperty("right", right.getUsername());
            p.addProperty("leftSeq", left.getQueueSeq());
            p.addProperty("rightSeq", right.getQueueSeq());
            p.addProperty("leftElo", left.getElo());
            p.addProperty("rightElo", right.getElo());
            ch.writeAndFlush(InternalMessage.of(InternalOps.MATCH_RESULT, null, ServiceRoles.MATCH, p));
            System.out.println("[match] paired left=" + left.getUsername()
                    + "(elo=" + left.getElo() + ",seq=" + left.getQueueSeq() + ")"
                    + " right=" + right.getUsername()
                    + "(elo=" + right.getElo() + ",seq=" + right.getQueueSeq() + ")");
        });

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "match-loop");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                matchmaker.run();
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }, ServerConfig.MATCH_SCAN_INTERVAL_MS, ServerConfig.MATCH_SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(() -> pushQueueStatus(logicChannel.get(), queue), 1, 1, TimeUnit.SECONDS);

        InternalHandler handler = (ctx, msg) -> onMessage(ctx, msg, queue, logicChannel);

        InternalRpcServer server = new InternalRpcServer(
                ServiceRoles.MATCH,
                ClusterConfig.BIND_HOST,
                ClusterConfig.MATCH_RPC_PORT,
                handler);
        server.start();
        System.out.println("[match] scanMs=" + ServerConfig.MATCH_SCAN_INTERVAL_MS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            server.close();
        }));
        server.channel().closeFuture().sync();
    }

    private static void onMessage(ChannelHandlerContext ctx, InternalMessage msg,
                                  MatchQueue queue, AtomicReference<Channel> logicChannel) {
        JsonObject p = msg.getPayload();
        switch (msg.getOp()) {
            case InternalOps.SYS_PING -> {
                JsonObject payload = new JsonObject();
                payload.addProperty("service", ServiceRoles.MATCH);
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
                ok.addProperty("service", ServiceRoles.MATCH);
                ctx.writeAndFlush(msg.reply(InternalOps.SYS_HELLO, ok));
                System.out.println("[match] logic registered");
            }
            case InternalOps.MATCH_ENQUEUE -> {
                String username = p.get("username").getAsString();
                int elo = p.get("elo").getAsInt();
                long seq = queue.nextSeq();
                queue.enqueue(new QueueEntry(username, elo, System.currentTimeMillis(), seq));
                JsonObject ok = new JsonObject();
                ok.addProperty("ok", true);
                ok.addProperty("seq", seq);
                ctx.writeAndFlush(msg.reply(InternalOps.MATCH_ENQUEUE, ok));
                System.out.println("[match] enqueue " + username + " elo=" + elo + " seq=" + seq);
            }
            case InternalOps.MATCH_CANCEL -> {
                String username = p.get("username").getAsString();
                QueueEntry removed = queue.remove(username);
                JsonObject ok = new JsonObject();
                ok.addProperty("removed", removed != null);
                ctx.writeAndFlush(msg.reply(InternalOps.MATCH_CANCEL, ok));
                if (removed != null) {
                    System.out.println("[match] cancel " + username
                            + " waitMs=" + removed.waitMs(System.currentTimeMillis()));
                }
            }
            case InternalOps.MATCH_CONTAINS -> {
                String username = p.get("username").getAsString();
                JsonObject ok = new JsonObject();
                ok.addProperty("contains", queue.contains(username));
                QueueEntry e = queue.get(username);
                if (e != null) {
                    ok.addProperty("waitMs", e.waitMs(System.currentTimeMillis()));
                    ok.addProperty("elo", e.getElo());
                }
                ctx.writeAndFlush(msg.reply(InternalOps.MATCH_CONTAINS, ok));
            }
            default -> System.out.println("[match] unknown op=" + msg.getOp());
        }
    }

    private static void pushQueueStatus(Channel logic, MatchQueue queue) {
        if (logic == null || !logic.isActive()) {
            return;
        }
        List<QueueEntry> snap = queue.snapshot();
        if (snap.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        JsonArray arr = new JsonArray();
        for (QueueEntry e : snap) {
            JsonObject row = new JsonObject();
            row.addProperty("username", e.getUsername());
            row.addProperty("waitMs", e.waitMs(now));
            row.addProperty("range", ServerConfig.matchRange(e.waitMs(now)));
            arr.add(row);
        }
        JsonObject p = new JsonObject();
        p.add("entries", arr);
        logic.writeAndFlush(InternalMessage.of(InternalOps.MATCH_QUEUE_STATUS, null, ServiceRoles.MATCH, p));
    }

    private MatchServer() {
    }
}
