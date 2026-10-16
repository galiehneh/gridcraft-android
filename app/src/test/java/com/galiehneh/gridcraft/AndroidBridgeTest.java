package com.galiehneh.gridcraft;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AndroidBridgeTest {
    @Test
    public void idleExpiry_onlyAfterLimit() {
        assertFalse(AndroidBridge.isIdleExpired(1000, 1000 + AndroidBridge.SESSION_IDLE_MS));
        assertTrue(AndroidBridge.isIdleExpired(1000, 1001 + AndroidBridge.SESSION_IDLE_MS));
    }
}
