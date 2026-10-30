package org.example.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.example.config.ServerConfig;
import org.example.model.PlayerData;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persistent player stats + TOP100 ranking (ZSET).
 */
public final class RedisStore implements AutoCloseable {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> redis;

    public RedisStore(String host, int port) {
        RedisURI uri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofSeconds(5))
                .build();
        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.redis = connection.sync();
    }

    public void ping() {
        String pong = redis.ping();
        if (!"PONG".equalsIgnoreCase(pong)) {
            throw new IllegalStateException("Unexpected Redis PING response: " + pong);
        }
    }

    public PlayerData getOrCreate(String username) {
        String key = playerKey(username);
        Map<String, String> map = redis.hgetall(key);
        if (map == null || map.isEmpty()) {
            PlayerData created = PlayerData.createNew(username);
            save(created);
            return created;
        }
        int elo = parseInt(map.get("elo"), ServerConfig.INITIAL_ELO);
        int games = parseInt(map.get("gamesPlayed"), 0);
        return new PlayerData(username, elo, games);
    }

    public PlayerData get(String username) {
        String key = playerKey(username);
        Map<String, String> map = redis.hgetall(key);
        if (map == null || map.isEmpty()) {
            return null;
        }
        return new PlayerData(
                username,
                parseInt(map.get("elo"), ServerConfig.INITIAL_ELO),
                parseInt(map.get("gamesPlayed"), 0));
    }

    public void save(PlayerData data) {
        String key = playerKey(data.getUsername());
        redis.hset(key, Map.of(
                "username", data.getUsername(),
                "elo", String.valueOf(data.getElo()),
                "gamesPlayed", String.valueOf(data.getGamesPlayed())));
    }

    public void settlePlayers(PlayerData a, PlayerData b) {
        save(a);
        save(b);
        updateRankingMembership(a);
        updateRankingMembership(b);
    }

    public void updateRankingMembership(PlayerData data) {
        if (data.isPlacementComplete()) {
            redis.zadd(ServerConfig.REDIS_RANKING_KEY, data.getElo(), data.getUsername());
        } else {
            // Defensive: not on the board in the normal path; ZREM is a no-op if absent.
            redis.zrem(ServerConfig.REDIS_RANKING_KEY, data.getUsername());
        }
    }

    public Long getRank(String username) {
        // Redis rank is 0-based ascending; we store score=ELO so reverse rank = descending.
        Long rev = redis.zrevrank(ServerConfig.REDIS_RANKING_KEY, username);
        if (rev == null) {
            return null;
        }
        return rev + 1;
    }

    public List<RankingEntry> top100() {
        List<io.lettuce.core.ScoredValue<String>> scored =
                redis.zrevrangeWithScores(ServerConfig.REDIS_RANKING_KEY, 0, ServerConfig.RANKING_TOP_N - 1);
        List<RankingEntry> list = new ArrayList<>();
        int rank = 1;
        for (io.lettuce.core.ScoredValue<String> sv : scored) {
            list.add(new RankingEntry(rank++, sv.getValue(), (int) Math.round(sv.getScore())));
        }
        return list;
    }

    private static String playerKey(String username) {
        return ServerConfig.REDIS_PLAYER_KEY_PREFIX + username;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } finally {
            client.shutdown();
        }
    }

    public record RankingEntry(int rank, String username, int elo) {
    }
}
