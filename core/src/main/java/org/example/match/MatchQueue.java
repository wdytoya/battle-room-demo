package org.example.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MatchQueue {

    private final Map<String, QueueEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong seqGen = new AtomicLong(0);

    public boolean contains(String username) {
        return entries.containsKey(username);
    }

    public QueueEntry get(String username) {
        return entries.get(username);
    }

    /** Allocates the next queue order id (smaller = earlier = left spawn). */
    public long nextSeq() {
        return seqGen.incrementAndGet();
    }

    public void enqueue(QueueEntry entry) {
        entries.put(entry.getUsername(), entry);
    }

    public QueueEntry remove(String username) {
        return entries.remove(username);
    }

    public List<QueueEntry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    public int size() {
        return entries.size();
    }
}
