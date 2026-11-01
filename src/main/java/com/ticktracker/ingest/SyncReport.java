package com.ticktracker.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What one ingestion run did. Returned by the API and stored in {@code sync_runs}. */
public record SyncReport(
        Long runId,
        String trigger,
        SyncStatus status,
        boolean dryRun,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        long received,
        long inserted,
        long updated,
        long unchanged,
        long rejected,
        Map<RejectionReason, Long> rejectedByReason,
        Map<Repair, Long> repairs,
        List<RejectionSample> rejectionSamples,
        String error) {

    /** A few example rejections so a caller can see what went wrong without paging the audit table. */
    public record RejectionSample(RejectionReason reason, String detail, String recordId, String raw) {
    }

    public static long duration(Instant start, Instant end) {
        return start == null || end == null ? 0 : Duration.between(start, end).toMillis();
    }
}
