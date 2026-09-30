package io.agentic.sdlc.shortener.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local sliding-window limiter; a multi-replica deployment needs a shared store. */
public final class CreateRateLimiter {
    private final int limit;
    private final long windowNanos;
    private final ConcurrentHashMap<String, Deque<Long>> events = new ConcurrentHashMap<>();

    public CreateRateLimiter(int limit, int windowSeconds) {
        if (limit < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("Rate limit and window must be positive.");
        }
        this.limit = limit;
        this.windowNanos = windowSeconds * 1_000_000_000L;
    }

    public boolean allow(String key) {
        long now = System.nanoTime();
        Deque<Long> bucket = events.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && now - bucket.peekFirst() >= windowNanos) {
                bucket.removeFirst();
            }
            if (bucket.size() >= limit) {
                return false;
            }
            bucket.addLast(now);
        }
        if (events.size() > 10_000) {
            events.entrySet().removeIf(entry -> {
                Deque<Long> candidate = entry.getValue();
                synchronized (candidate) {
                    return candidate.isEmpty() || now - candidate.peekLast() >= windowNanos;
                }
            });
        }
        return true;
    }
}
