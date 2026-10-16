package com.galiehneh.gridcraft;

import java.util.ArrayDeque;

/** Allows at most {@code max} events within any {@code windowMs} span. */
final class RateLimit {
    private final int max;
    private final long windowMs;
    private final ArrayDeque<Long> events = new ArrayDeque<>();

    RateLimit(int max, long windowMs) {
        this.max = max;
        this.windowMs = windowMs;
    }

    /** Records an event at {@code now}; false when it exceeds the limit (and is not recorded). */
    boolean tryAcquire(long now) {
        while (!events.isEmpty() && now - events.peekFirst() >= windowMs) events.pollFirst();
        if (events.size() >= max) return false;
        events.addLast(now);
        return true;
    }
}
