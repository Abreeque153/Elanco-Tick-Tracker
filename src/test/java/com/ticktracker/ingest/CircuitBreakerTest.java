package com.ticktracker.ingest;

import com.ticktracker.support.MutableClock;
import com.ticktracker.support.TestSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private final MutableClock clock = TestSupport.clock();
    private final CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofMinutes(2), clock);

    @Test
    void opensAfterTheThresholdOfConsecutiveFailures() {
        breaker.recordFailure();
        breaker.recordFailure();
        assertTrue(breaker.allowRequest(), "still closed after 2 failures");
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
        assertEquals(TestSupport.NOW.plus(Duration.ofMinutes(2)), breaker.openUntil().orElseThrow());
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void allowsOneTrialAfterTheCoolDownAndClosesOnSuccess() {
        tripBreaker();
        clock.advance(Duration.ofMinutes(2));
        assertTrue(breaker.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void reopensIfTheTrialCallFails() {
        tripBreaker();
        clock.advance(Duration.ofMinutes(3));
        assertTrue(breaker.allowRequest());
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
    }

    private void tripBreaker() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    }
}
