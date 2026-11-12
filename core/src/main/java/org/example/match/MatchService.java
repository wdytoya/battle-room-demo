package org.example.match;

/**
 * Boundary for matchmaking. Match process will own this later;
 * monolith keeps {@link Matchmaker} + {@link MatchQueue} behind {@link EmbeddedMatchService}.
 */
public interface MatchService extends Runnable {

    MatchQueue queue();
}
