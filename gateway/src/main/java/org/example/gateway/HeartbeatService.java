package org.example.gateway;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.example.config.ClusterConfig;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Application-level heartbeat on Gateway. Merged periodic scan (not per-connection timers).
 */
public final class HeartbeatService implements AutoCloseable {

    private final Map<String, Long> lastActiveMs = new ConcurrentHashMap<>();
    /** connIds closed due to heartbeat — channelInactive must not soft-disconnect. */
    private final Set<String> heartbeatClosing = ConcurrentHashMap.newKeySet();
    private final ClientConnRegistry registry;
    private final Consumer<String> onTimeout;
    private final ScheduledExecutorService scheduler;

    public HeartbeatService(ClientConnRegistry registry, Consumer<String> onTimeout) {
        this.registry = registry;
        this.onTimeout = onTimeout;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gw-heartbeat");
            t.setDaemon(true);
            return t;
        });
        if (ClusterConfig.HEARTBEAT_ENABLED) {
            scheduler.scheduleAtFixedRate(this::scan,
                    ClusterConfig.HEARTBEAT_CHECK_MS,
                    ClusterConfig.HEARTBEAT_CHECK_MS,
                    TimeUnit.MILLISECONDS);
            System.out.println("[gateway] heartbeat enabled timeoutMs=" + ClusterConfig.HEARTBEAT_TIMEOUT_MS
                    + " checkMs=" + ClusterConfig.HEARTBEAT_CHECK_MS
                    + " hintClientIntervalMs=" + ClusterConfig.HEARTBEAT_CLIENT_INTERVAL_MS);
        }
    }

    public void touch(String connId) {
        lastActiveMs.put(connId, System.currentTimeMillis());
    }

    public void remove(String connId) {
        lastActiveMs.remove(connId);
    }

    /**
     * @return true if this close was a heartbeat kick (caller should skip soft-disconnect inactive).
     */
    public boolean consumeHeartbeatClose(String connId) {
        return heartbeatClosing.remove(connId);
    }

    private void scan() {
        long now = System.currentTimeMillis();
        long timeout = ClusterConfig.HEARTBEAT_TIMEOUT_MS;
        for (Map.Entry<String, Long> e : lastActiveMs.entrySet()) {
            if (now - e.getValue() <= timeout) {
                continue;
            }
            String connId = e.getKey();
            Channel ch = registry.get(connId);
            System.out.println("[gateway] heartbeat timeout connId=" + connId
                    + " onlineApprox=" + registry.connectionCount());
            // Notify Logic with heartbeat reason BEFORE close. Mark so channelInactive
            // does not also soft-disconnect (which would race and keep a reconnect window).
            lastActiveMs.remove(connId, e.getValue());
            heartbeatClosing.add(connId);
            onTimeout.accept(connId);
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(new TextWebSocketFrame(
                        "{\"type\":\"kick\",\"reason\":\"heartbeat_timeout\"}"));
                ch.close();
            } else {
                heartbeatClosing.remove(connId);
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
