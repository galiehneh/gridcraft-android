package com.galiehneh.gridcraft;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RateLimitTest {
    @Test
    public void allowsUpToMaxWithinWindow() {
        RateLimit limit = new RateLimit(2, 30000);
        assertTrue(limit.tryAcquire(0));
        assertTrue(limit.tryAcquire(1000));
        assertFalse(limit.tryAcquire(2000));
    }

    @Test
    public void allowsAgainAfterWindowPasses() {
        RateLimit limit = new RateLimit(2, 30000);
        assertTrue(limit.tryAcquire(0));
        assertTrue(limit.tryAcquire(1000));
        assertFalse(limit.tryAcquire(29999));
        assertTrue(limit.tryAcquire(30000));
    }
}
