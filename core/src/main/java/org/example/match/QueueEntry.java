package org.example.match;

public final class QueueEntry {
    private final String username;
    private final int elo;
    private final long enqueuedAtMs;
    /** Monotonic queue order: smaller = queued earlier = spawn on the left. */
    private final long queueSeq;

    public QueueEntry(String username, int elo, long enqueuedAtMs, long queueSeq) {
        this.username = username;
        this.elo = elo;
        this.enqueuedAtMs = enqueuedAtMs;
        this.queueSeq = queueSeq;
    }

    public String getUsername() {
        return username;
    }

    public int getElo() {
        return elo;
    }

    public long getEnqueuedAtMs() {
        return enqueuedAtMs;
    }

    public long getQueueSeq() {
        return queueSeq;
    }

    public long waitMs(long now) {
        return Math.max(0L, now - enqueuedAtMs);
    }
}
