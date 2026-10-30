package org.example.match;

import org.example.config.ServerConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Periodically pairs players by smallest ELO delta within dynamic ranges.
 */
public final class Matchmaker implements Runnable {

    private final MatchQueue queue;
    private final BiConsumer<QueueEntry, QueueEntry> onMatched;

    public Matchmaker(MatchQueue queue, BiConsumer<QueueEntry, QueueEntry> onMatched) {
        this.queue = queue;
        this.onMatched = onMatched;
    }

    @Override
    public void run() {
        try {
            matchOnce();
        } catch (Exception e) {
            System.err.println("[Matchmaker] error: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    void matchOnce() {
        List<QueueEntry> list = queue.snapshot();
        if (list.size() < 2) {
            return;
        }
        long now = System.currentTimeMillis();

        record Candidate(QueueEntry a, QueueEntry b, int delta) {
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            QueueEntry a = list.get(i);
            int rangeA = ServerConfig.matchRange(a.waitMs(now));
            for (int j = i + 1; j < list.size(); j++) {
                QueueEntry b = list.get(j);
                int rangeB = ServerConfig.matchRange(b.waitMs(now));
                int allowed = Math.max(rangeA, rangeB);
                int delta = Math.abs(a.getElo() - b.getElo());
                if (delta <= allowed) {
                    candidates.add(new Candidate(a, b, delta));
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        candidates.sort(Comparator.comparingInt(Candidate::delta));

        // Greedy: take non-overlapping pairs with smallest delta first.
        java.util.HashSet<String> used = new java.util.HashSet<>();
        for (Candidate c : candidates) {
            if (used.contains(c.a().getUsername()) || used.contains(c.b().getUsername())) {
                continue;
            }
            // Re-check still in queue (may have cancelled).
            if (queue.get(c.a().getUsername()) == null || queue.get(c.b().getUsername()) == null) {
                continue;
            }
            queue.remove(c.a().getUsername());
            queue.remove(c.b().getUsername());
            used.add(c.a().getUsername());
            used.add(c.b().getUsername());
            // Left = smaller queueSeq (clicked 开始匹配 earlier); right = later.
            QueueEntry left = c.a().getQueueSeq() <= c.b().getQueueSeq() ? c.a() : c.b();
            QueueEntry right = left == c.a() ? c.b() : c.a();
            onMatched.accept(left, right);
        }
    }
}
