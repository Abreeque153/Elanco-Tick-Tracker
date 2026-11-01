package com.ticktracker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** Typed view of the {@code ticks.*} settings in application.yml. */
@ConfigurationProperties(prefix = "ticks")
public record TicksProperties(Storage storage, Upstream upstream, Sync sync) {

    /** @param path SQLite database file; its folder is created if missing */
    public record Storage(String path) {
    }

    public record Upstream(URI baseUrl, Duration connectTimeout, Duration requestTimeout, Retry retry,
                           CircuitBreaker circuitBreaker) {
    }

    public record Retry(int maxAttempts, Duration initialDelay, Duration maxDelay, double jitter) {
    }

    public record CircuitBreaker(int failureThreshold, Duration openDuration) {
    }

    /**
     * @param onStartup  sync as soon as the app starts
     * @param interval   time between background syncs (0 disables them)
     * @param staleAfter data counts as stale if no upstream sync has succeeded for this long
     * @param chunkSize  records written per database transaction
     */
    public record Sync(boolean onStartup, Duration interval, Duration staleAfter, int chunkSize) {
    }
}
