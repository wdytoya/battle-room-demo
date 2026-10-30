package org.example.model;

import org.example.config.ServerConfig;

/**
 * Persistent player stats (Redis-backed).
 */
public final class PlayerData {
    private final String username;
    private int elo;
    private int gamesPlayed;

    public PlayerData(String username, int elo, int gamesPlayed) {
        this.username = username;
        this.elo = elo;
        this.gamesPlayed = gamesPlayed;
    }

    public static PlayerData createNew(String username) {
        return new PlayerData(username, ServerConfig.INITIAL_ELO, 0);
    }

    public String getUsername() {
        return username;
    }

    public int getElo() {
        return elo;
    }

    public void setElo(int elo) {
        this.elo = elo;
    }

    public int getGamesPlayed() {
        return gamesPlayed;
    }

    public void setGamesPlayed(int gamesPlayed) {
        this.gamesPlayed = gamesPlayed;
    }

    public boolean isPlacementComplete() {
        return gamesPlayed >= ServerConfig.PLACEMENT_GAMES;
    }

    /** Games completed toward placement (capped at PLACEMENT_GAMES for display). */
    public int placementProgress() {
        return Math.min(gamesPlayed, ServerConfig.PLACEMENT_GAMES);
    }
}
