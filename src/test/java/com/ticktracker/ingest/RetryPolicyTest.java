package com.ticktracker.ingest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    @Test
    void backsOffExponentiallyUpToTheCap() {
        RetryPolicy policy = new RetryPolicy(10, Duration.ofSeconds(1), Duration.ofSeconds(10), 0, new Random(1));
        assertEquals(Duration.ZERO, policy.delayBefore(1, null));
        assertEquals(Duration.ofSeconds(1), policy.delayBefore(2, null));
        assertEquals(Duration.ofSeconds(2), policy.delayBefore(3, null));
        assertEquals(Duration.ofSeconds(4), policy.delayBefore(4, null));
        assertEquals(Duration.ofSeconds(8), policy.delayBefore(5, null));
        assertEquals(Duration.ofSeconds(10), policy.delayBefore(6, null), "capped at maxDelay");
        assertEquals(Duration.ofSeconds(10), policy.delayBefore(60, null), "no overflow on many attempts");
    }

    @Test
    void honoursRetryAfterButNeverBeyondTheCap() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(60), 0.5, new Random(1));
        assertEquals(Duration.ofSeconds(30), policy.delayBefore(2, Duration.ofSeconds(30)));
        assertEquals(Duration.ofSeconds(60), policy.delayBefore(2, Duration.ofMinutes(10)));
    }

    @Test
    void jitterOnlyEverShortensTheDelay() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(4), Duration.ofSeconds(60), 0.5, new Random(42));
        for (int i = 0; i < 1_000; i++) {
            long millis = policy.delayBefore(2, null).toMillis();
            assertTrue(millis >= 2_000 && millis <= 4_000, "delay " + millis + " outside [2000, 4000]");
        }
    }

    @Test
    void rejectsNonsenseSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO, 0, new Random()));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, Duration.ZERO, Duration.ZERO, 2, new Random()));
    }
}
