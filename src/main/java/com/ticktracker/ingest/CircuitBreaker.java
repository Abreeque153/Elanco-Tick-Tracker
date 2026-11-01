package com.ticktracker.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Stops calling the upstream API for a cool-down period after repeated failures.
 *
 * <pre>
 *  CLOSED --(failureThreshold consecutive failures)--> OPEN
 *  OPEN   --(openDuration elapsed)-------------------> HALF_OPEN (one trial call allowed)
 *  HALF_OPEN --success--> CLOSED      HALF_OPEN --failure--> OPEN
 * </pre>
 *
 * <p>While open, syncs fail immediately instead of spending minutes on
 * retries, and the API keeps serving the data already in SQLite.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;

    public CircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be at least 1");
        }
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    /** True if a call may be made now. Moves OPEN to HALF_OPEN once the cool-down is over. */
    public synchronized boolean allowRequest() {
        refresh();
        return state != State.OPEN;
    }

    public synchronized void recordSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        openedAt = null;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock.instant();
        }
    }

    public synchronized State state() {
        refresh();
        return state;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** When an open breaker will allow a trial call, if it is open. */
    public synchronized Optional<Instant> openUntil() {
        refresh();
        return state == State.OPEN ? Optional.of(openedAt.plus(openDuration)) : Optional.empty();
    }

    private void refresh() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            state = State.HALF_OPEN;
        }
    }
}
