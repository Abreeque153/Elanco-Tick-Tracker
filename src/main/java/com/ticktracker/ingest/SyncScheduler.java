package com.ticktracker.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs upstream syncs in the background: once at start-up (so the API is
 * usable right away), then every {@code interval}, plus on demand via
 * POST /api/v1/sync.
 *
 * <p>A single worker thread means syncs never overlap. Because it runs in the
 * background, a slow or failing upstream never blocks an API request.
 */
public final class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncService syncService;
    private final boolean syncOnStartup;
    private final Duration interval;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean manualRunQueued = new AtomicBoolean(false);

    public SyncScheduler(SyncService syncService, boolean syncOnStartup, Duration interval) {
        this.syncService = syncService;
        this.syncOnStartup = syncOnStartup;
        this.interval = interval;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tick-sync");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (syncOnStartup) {
            executor.execute(() -> runSafely("STARTUP"));
        }
        if (interval != null && !interval.isZero() && !interval.isNegative()) {
            long millis = interval.toMillis();
            executor.scheduleWithFixedDelay(() -> runSafely("SCHEDULED"), millis, millis, TimeUnit.MILLISECONDS);
            log.info("Upstream sync scheduled every {}", interval);
        }
    }

    /**
     * Queues an immediate sync.
     *
     * @return false if a sync is already running or queued
     */
    public boolean triggerNow() {
        if (syncService.isRunning() || !manualRunQueued.compareAndSet(false, true)) {
            return false;
        }
        executor.execute(() -> {
            manualRunQueued.set(false);
            runSafely("MANUAL");
        });
        return true;
    }

    public void stop() {
        executor.shutdownNow();
    }

    /** A scheduled task that throws would never run again, so nothing may escape. */
    private void runSafely(String trigger) {
        try {
            syncService.syncFromUpstream(trigger);
        } catch (RuntimeException e) {
            log.error("{} sync could not run: {}", trigger, e.getMessage(), e);
        }
    }
}
