package com.ticktracker.health;

import com.ticktracker.ingest.SyncService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Adds an "upstream" section to /actuator/health. The service stays UP while
 * the Elanco API is down, because it keeps serving stored data; this section
 * shows how fresh that data is.
 */
@Component("upstreamHealthIndicator")
public class UpstreamHealthIndicator implements HealthIndicator {

    private final SyncService syncService;

    public UpstreamHealthIndicator(SyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public Health health() {
        SyncService.SyncState state = syncService.state();
        Health.Builder builder = state.lastSuccessfulSyncAt() == null ? Health.unknown() : Health.up();
        return builder
                .withDetail("source", state.upstream())
                .withDetail("lastSuccessfulSync", String.valueOf(state.lastSuccessfulSyncAt()))
                .withDetail("dataStale", state.dataStale())
                .withDetail("circuitBreaker", state.circuitBreaker().name())
                .withDetail("syncRunning", state.running())
                .build();
    }
}
