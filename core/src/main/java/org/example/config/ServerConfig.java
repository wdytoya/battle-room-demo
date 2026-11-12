package org.example.config;

/**
 * Centralized server configuration. Override via system properties / env where noted.
 */
public final class ServerConfig {

    public static final int SERVER_PORT = intVal("battle.port", "BATTLE_PORT", 8080);
    public static final String REDIS_HOST = strVal("battle.redis.host", "BATTLE_REDIS_HOST", "127.0.0.1");
    public static final int REDIS_PORT = intVal("battle.redis.port", "BATTLE_REDIS_PORT", 6379);

    public static final int INITIAL_ELO = 1000;
    public static final int PLACEMENT_GAMES = 10;

    public static final int K_PLACEMENT = 40;
    public static final int K_LOW = 32;
    public static final int K_MID = 24;
    public static final int K_HIGH = 16;
    public static final int ELO_TIER_MID = 1200;
    public static final int ELO_TIER_HIGH = 1800;
    public static final int ELO_SCALE = 400;

    public static final long MATCH_SCAN_INTERVAL_MS = longVal("battle.match.scanMs", "BATTLE_MATCH_SCAN_MS", 500L);
    public static final int MATCH_RANGE_INITIAL = 100;
    public static final int MATCH_RANGE_STEP = 50;
    public static final int MATCH_RANGE_STEP_SECONDS = 5;
    public static final int MATCH_RANGE_MAX = 600;

    /** Physics / snapshot tick. Higher helps prediction demos. */
    public static final int TICK_HZ = intVal("battle.tickHz", "BATTLE_TICK_HZ", 20);
    public static final long PLAYING_DURATION_MS = longVal("battle.playingMs", "BATTLE_PLAYING_MS", 30_000L);

    /**
     * On collision RPS: missing hand loses to opponent with hand; both missing = no light change.
     */
    public static final boolean NO_INPUT_LOSES = boolVal("battle.noInputLoses", "BATTLE_NO_INPUT_LOSES", true);

    /** Arena & ball physics (world units). */
    public static final double ARENA_WIDTH = 1000.0;
    public static final double BALL_RADIUS = 36.0;
    public static final double START_X_A = 220.0;
    public static final double START_X_B = 780.0;
    public static final double ACCEL = 520.0;          // units/s^2 while thrusting
    public static final double MAX_SPEED = 380.0;      // units/s
    public static final double FRICTION = 80.0;        // units/s^2 drag when no thrust
    public static final double WALL_RESTITUTION = 0.55;
    public static final double COLLISION_RESTITUTION = 0.85;
    public static final int MAX_LIGHTS = 3;
    /** Ignore repeated collision resolution for this many ms after a hit. */
    public static final long COLLISION_COOLDOWN_MS = 350L;

    public static final String WS_PATH = "/ws";
    public static final String REDIS_PLAYER_KEY_PREFIX = "rps:player:";
    public static final String REDIS_RANKING_KEY = "rps:ranking";
    public static final int RANKING_TOP_N = 100;
    public static final int MAX_USERNAME_LENGTH = 32;

    public static long tickIntervalMs() {
        return Math.max(1L, 1000L / Math.max(1, TICK_HZ));
    }

    public static double tickDtSeconds() {
        return 1.0 / Math.max(1, TICK_HZ);
    }

    public static int matchRange(long waitMs) {
        long waitSeconds = Math.max(0L, waitMs / 1000L);
        long steps = waitSeconds / MATCH_RANGE_STEP_SECONDS;
        long range = MATCH_RANGE_INITIAL + (long) MATCH_RANGE_STEP * steps;
        return (int) Math.min(MATCH_RANGE_MAX, range);
    }

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

    private static boolean boolVal(String sys, String env, boolean def) {
        String v = raw(sys, env);
        if (v == null || v.isBlank()) {
            return def;
        }
        return Boolean.parseBoolean(v.trim());
    }

    private static String strVal(String sys, String env, String def) {
        String v = raw(sys, env);
        if (v == null || v.isBlank()) {
            return def;
        }
        return v.trim();
    }

    private ServerConfig() {
    }
}
