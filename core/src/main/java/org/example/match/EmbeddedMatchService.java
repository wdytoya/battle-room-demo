package org.example.match;

import java.util.function.BiConsumer;

/**
 * In-process Match implementation used by the monolith (and as rollback for Phase 3).
 */
public final class EmbeddedMatchService implements MatchService {

    private final MatchQueue queue;
    private final Matchmaker matchmaker;

    public EmbeddedMatchService(MatchQueue queue, BiConsumer<QueueEntry, QueueEntry> onMatched) {
        this.queue = queue;
        this.matchmaker = new Matchmaker(queue, onMatched);
    }

    @Override
    public MatchQueue queue() {
        return queue;
    }

    @Override
    public void run() {
        matchmaker.run();
    }
}
