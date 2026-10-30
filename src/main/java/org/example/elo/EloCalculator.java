package org.example.elo;

import org.example.config.ServerConfig;

/**
 * Standard ELO with base-10, scale 400.
 */
public final class EloCalculator {

    public record UpdateResult(int newElo, int delta, int kUsed, double expected, double score) {
    }

    public static int kFactor(int gamesPlayedBefore, int eloBefore) {
        if (gamesPlayedBefore < ServerConfig.PLACEMENT_GAMES) {
            return ServerConfig.K_PLACEMENT;
        }
        if (eloBefore < ServerConfig.ELO_TIER_MID) {
            return ServerConfig.K_LOW;
        }
        if (eloBefore < ServerConfig.ELO_TIER_HIGH) {
            return ServerConfig.K_MID;
        }
        return ServerConfig.K_HIGH;
    }

    public static double expectedScore(int eloSelf, int eloOpponent) {
        double exponent = (eloOpponent - eloSelf) / (double) ServerConfig.ELO_SCALE;
        return 1.0 / (1.0 + Math.pow(10.0, exponent));
    }

    /**
     * @param score 1 win / 0.5 draw / 0 loss
     */
    public static UpdateResult update(int eloSelf, int eloOpponent, int gamesPlayedBefore, double score) {
        int k = kFactor(gamesPlayedBefore, eloSelf);
        double expected = expectedScore(eloSelf, eloOpponent);
        int delta = (int) Math.round(k * (score - expected));
        int newElo = eloSelf + delta;
        return new UpdateResult(newElo, delta, k, expected, score);
    }

    private EloCalculator() {
    }
}
