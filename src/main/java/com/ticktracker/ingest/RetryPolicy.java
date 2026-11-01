package com.ticktracker.ingest;

import java.time.Duration;
import java.util.Random;

/**
 * Exponential backoff with jitter: 1s, 2s, 4s, ... capped at {@code maxDelay},
 * each reduced by a random amount up to {@code jitter} so that many clients
 * don't retry in lockstep. A server-supplied Retry-After always wins (up to
 * the cap).
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double jitter;
    private final Random random;

    public RetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay, double jitter, Random random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (jitter < 0 || jitter > 1) {
            throw new IllegalArgumentException("jitter must be between 0 and 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.jitter = jitter;
        this.random = random;
    }

    public static RetryPolicy noRetries() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 0, new Random());
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * How long to wait before the given attempt.
     *
     * @param attempt    the attempt about to be made (2 for the first retry)
     * @param retryAfter the server's Retry-After hint, or null
     */
    public Duration delayBefore(int attempt, Duration retryAfter) {
        if (attempt < 2) {
            return Duration.ZERO;
        }
        if (retryAfter != null && !retryAfter.isNegative()) {
            return min(retryAfter, maxDelay);
        }
        long base = initialDelay.toMillis();
        int doublings = Math.min(attempt - 2, 30);
        long exponential = Math.min(maxDelay.toMillis(), base * (1L << doublings));
        long jittered = Math.round(exponential * (1.0 - jitter * random.nextDouble()));
        return Duration.ofMillis(Math.max(0, jittered));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
