package org.example.config;

/**
 * Multi-process bind/connect endpoints. Override via system properties / env.
 * Battle-side services bind localhost only (no public client ports).
 */
public final class ClusterConfig {

    public static final String BIND_HOST = strVal("battle.bind.host", "BATTLE_BIND_HOST", "127.0.0.1");

    public static final int GATEWAY_HTTP_PORT = intVal("battle.gateway.port", "BATTLE_GATEWAY_PORT", 8080);
    public static final int MATCH_RPC_PORT = intVal("battle.match.port", "BATTLE_MATCH_PORT", 9100);
    public static final int LOGIC_RPC_PORT = intVal("battle.logic.port", "BATTLE_LOGIC_PORT", 9200);
    public static final int PHYSICS_RPC_PORT = intVal("battle.physics.port", "BATTLE_PHYSICS_PORT", 9300);

    public static final String MATCH_HOST = strVal("battle.match.host", "BATTLE_MATCH_HOST", BIND_HOST);
    public static final String LOGIC_HOST = strVal("battle.logic.host", "BATTLE_LOGIC_HOST", BIND_HOST);
    public static final String PHYSICS_HOST = strVal("battle.physics.host", "BATTLE_PHYSICS_HOST", BIND_HOST);

    public static final String WS_PATH = "/ws";

    /**
     * When true, Logic uses the Match process (localhost RPC).
     * Monolith should leave this false (embedded matchmaker).
     */
    public static final boolean MATCH_REMOTE = boolVal("battle.match.remote", "BATTLE_MATCH_REMOTE", false);

    /**
     * Heartbeat: client app-ping interval (pushed on login_ok / reconnect_ok),
     * gateway idle scan, and kick timeout.
     */
    public static final long HEARTBEAT_CLIENT_INTERVAL_MS =
            longVal("battle.heartbeat.intervalMs", "BATTLE_HEARTBEAT_INTERVAL_MS", 5_000L);
    public static final long HEARTBEAT_CHECK_MS =
            longVal("battle.heartbeat.checkMs", "BATTLE_HEARTBEAT_CHECK_MS", 1_000L);
    public static final long HEARTBEAT_TIMEOUT_MS =
            longVal("battle.heartbeat.timeoutMs", "BATTLE_HEARTBEAT_TIMEOUT_MS", 15_000L);
    public static final boolean HEARTBEAT_ENABLED =
            boolVal("battle.heartbeat.enabled", "BATTLE_HEARTBEAT_ENABLED", true);

    /** Soft-disconnect reconnect window; heartbeat kick abandons reconnect. */
    public static final long RECONNECT_WINDOW_MS =
            longVal("battle.reconnect.windowMs", "BATTLE_RECONNECT_WINDOW_MS", 60_000L);
    public static final boolean RECONNECT_ENABLED =
            boolVal("battle.reconnect.enabled", "BATTLE_RECONNECT_ENABLED", true);

    /**
     * Redis lease for both {@code rps:route:} and {@code rps:reconnect:} while ONLINE
     * (same TTL — reconnect needs both keys). Logic renews periodically.
     * Default ≈ 3× heartbeat timeout so a few missed renews still keep the pair.
     */
    public static final long ROUTE_LEASE_MS =
            longVal("battle.route.leaseMs", "BATTLE_ROUTE_LEASE_MS", 45_000L);
    /** Logic interval to EXPIRE-refresh ONLINE route + reconnect-token keys. */
    public static final long ROUTE_RENEW_MS =
            longVal("battle.route.renewMs", "BATTLE_ROUTE_RENEW_MS", 15_000L);
    /** Extra Redis TTL beyond {@link #RECONNECT_WINDOW_MS} after soft-disconnect (both keys). */
    public static final long RECONNECT_REDIS_SLACK_MS =
            longVal("battle.reconnect.redisSlackMs", "BATTLE_RECONNECT_REDIS_SLACK_MS", 30_000L);

    private static String raw(String sys, String env) {
        String v = System.getProperty(sys);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return v;
    }

    private static int intVal(String sys, String env, int def) {
        String v = raw(sys, env);
        if (v == null || v.isBlank()) {
            return def;
        }
        return Integer.parseInt(v.trim());
    }

    private static long longVal(String sys, String env, long def) {
        String v = raw(sys, env);
        if (v == null || v.isBlank()) {
            return def;
        }
        return Long.parseLong(v.trim());
    }

    private static String strVal(String sys, String env, String def) {
        String v = raw(sys, env);
        if (v == null || v.isBlank()) {
            return def;
        }
        return v.trim();
    }

    private static boolean boolVal(String sys, String env, boolean def) {
        String v = raw(sys, env);
        if (v == null || v.isBlank()) {
            return def;
        }
        return Boolean.parseBoolean(v.trim());
    }

    private ClusterConfig() {
    }
}
