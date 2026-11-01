package com.ticktracker.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticktracker.domain.Sighting;
import com.ticktracker.domain.SightingSource;
import com.ticktracker.error.ConflictException;
import com.ticktracker.store.Database;
import com.ticktracker.store.SightingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs the ingestion pipeline:
 *
 * <pre>
 *   source (API or file) --stream--> clean/validate --> de-duplicate --> upsert into SQLite (in chunks)
 *                                         |                  |
 *                                         +--> rejected_records (audit) <--+
 * </pre>
 *
 * <ul>
 *   <li><b>Large datasets:</b> records are streamed and written in chunks
 *       (500 by default), one transaction per chunk, so memory stays flat and
 *       an interrupted run keeps the work already done.</li>
 *   <li><b>Duplicates:</b> the same id twice in a run, or the same time, city
 *       and species under a different id (in this run or already stored), is
 *       rejected and logged. Re-running a sync is idempotent: unchanged rows
 *       are only re-stamped as "last seen".</li>
 *   <li><b>Incomplete data:</b> handled by {@link SightingCleaner}, which
 *       repairs what it safely can and rejects the rest with a reason.</li>
 *   <li><b>Failures:</b> if the source fails, existing data is left untouched
 *       and keeps being served. The run is recorded as FAILED or PARTIAL with
 *       the error.</li>
 * </ul>
 */
public final class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private static final int MAX_SAMPLES = 25;
    private static final int MAX_RAW_LENGTH = 2_000;

    /** Anything that can push raw JSON records into the pipeline. */
    @FunctionalInterface
    interface RecordSource {
        long stream(Consumer<JsonNode> sink) throws IOException;
    }

    /** Snapshot of sync health for the status endpoint. */
    public record SyncState(
            boolean running,
            Instant lastSuccessfulSyncAt,
            boolean dataStale,
            Duration staleAfter,
            CircuitBreaker.State circuitBreaker,
            Instant circuitOpenUntil,
            String upstream,
            SyncReport lastRun) {
    }

    private final Database db;
    private final SightingRepository sightings;
    private final RejectedRecordRepository rejected;
    private final SyncRunRepository runs;
    private final SightingCleaner cleaner;
    private final ElancoApiClient client;
    private final JsonArrayReader reader;
    private final Clock clock;
    private final int chunkSize;
    private final Duration staleAfter;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastSuccessAt;

    public SyncService(Database db, SightingRepository sightings, RejectedRecordRepository rejected,
                       SyncRunRepository runs, SightingCleaner cleaner, ElancoApiClient client,
                       JsonArrayReader reader, Clock clock, int chunkSize, Duration staleAfter) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("chunkSize must be at least 1");
        }
        this.db = db;
        this.sightings = sightings;
        this.rejected = rejected;
        this.runs = runs;
        this.cleaner = cleaner;
        this.client = client;
        this.reader = reader;
        this.clock = clock;
        this.chunkSize = chunkSize;
        this.staleAfter = staleAfter;

        int interrupted = runs.markInterruptedRuns(clock.instant());
        if (interrupted > 0) {
            log.warn("Marked {} sync run(s) interrupted by the last shutdown as FAILED", interrupted);
        }
        this.lastSuccessAt = runs.latestUpstream(SyncStatus.SUCCESS).map(SyncReport::finishedAt).orElse(null);
    }

    /** Pulls everything from the Elanco API. Blocks until done; use {@link SyncScheduler} to run it in the background. */
    public SyncReport syncFromUpstream(String trigger) {
        return run(trigger, SightingSource.ELANCO_API, false, client::streamAllSightings);
    }

    /**
     * Runs a JSON array (same shape as the Elanco API) through the same
     * pipeline. With {@code dryRun} nothing is written; the report shows what
     * would have happened.
     */
    public SyncReport importJson(InputStream body, boolean dryRun) {
        return run("IMPORT", SightingSource.IMPORT, dryRun, sink -> reader.forEach(body, sink));
    }

    public boolean isRunning() {
        return running.get();
    }

    public Optional<Instant> lastSuccessfulSync() {
        return Optional.ofNullable(lastSuccessAt);
    }

    public boolean isDataStale() {
        Instant last = lastSuccessAt;
        return last == null || Duration.between(last, clock.instant()).compareTo(staleAfter) > 0;
    }

    public SyncState state() {
        CircuitBreaker breaker = client.circuitBreaker();
        return new SyncState(
                running.get(),
                lastSuccessAt,
                isDataStale(),
                staleAfter,
                breaker.state(),
                breaker.openUntil().orElse(null),
                client.baseUri().toString(),
                runs.latest().orElse(null));
    }

    public List<SyncReport> recentRuns(int limit) {
        return runs.recent(limit);
    }

    // ------------------------------------------------------------------ run

    private SyncReport run(String trigger, SightingSource source, boolean dryRun, RecordSource recordSource) {
        if (!running.compareAndSet(false, true)) {
            throw new ConflictException("A sync is already running. Check GET /api/v1/sync/status");
        }
        try {
            Instant started = clock.instant();
            Long runId = dryRun ? null : runs.start(trigger, started);
            Batch batch = new Batch(source, runId, dryRun);
            SyncStatus status;
            String error = null;
            try {
                recordSource.stream(batch::add);
                batch.flush();
                status = SyncStatus.SUCCESS;
            } catch (UpstreamException | IOException e) {
                // Keep everything read before the failure; it was valid.
                batch.flush();
                status = batch.received == 0 ? SyncStatus.FAILED : SyncStatus.PARTIAL;
                error = e.getMessage();
                log.warn("{} sync {} after {} records: {}", trigger, status, batch.received, e.getMessage());
            } catch (RuntimeException e) {
                status = SyncStatus.FAILED;
                error = "Internal error: " + e.getMessage();
                log.error("{} sync failed unexpectedly", trigger, e);
            }

            SyncReport report = batch.report(trigger, status, started, clock.instant(), error);
            if (runId != null) {
                runs.finish(report);
            }
            if (status == SyncStatus.SUCCESS && !dryRun && source == SightingSource.ELANCO_API) {
                lastSuccessAt = report.finishedAt();
            }
            log.info("{} sync {}: received={} inserted={} updated={} unchanged={} rejected={} in {} ms",
                    trigger, status, report.received(), report.inserted(), report.updated(), report.unchanged(),
                    report.rejected(), report.durationMs());
            return report;
        } finally {
            running.set(false);
        }
    }

    /** Accumulates one run's records, processes them a chunk at a time, and tallies the results. */
    private final class Batch {

        private record Candidate(Sighting sighting, Set<Repair> repairs, JsonNode raw) {
        }

        private final SightingSource source;
        private final Long runId;
        private final boolean dryRun;
        private final List<JsonNode> pending = new ArrayList<>();
        /** Ids and content keys seen earlier in this run, so duplicates across chunks are caught. */
        private final Set<String> seenIds = new HashSet<>();
        private final Map<String, String> idByContentKey = new HashMap<>();

        private long received;
        private long inserted;
        private long updated;
        private long unchanged;
        private long rejectedCount;
        private final Map<RejectionReason, Long> rejectedByReason = new EnumMap<>(RejectionReason.class);
        private final Map<Repair, Long> repairs = new EnumMap<>(Repair.class);
        private final List<SyncReport.RejectionSample> samples = new ArrayList<>();

        Batch(SightingSource source, Long runId, boolean dryRun) {
            this.source = source;
            this.runId = runId;
            this.dryRun = dryRun;
        }

        void add(JsonNode node) {
            received++;
            pending.add(node);
            if (pending.size() >= chunkSize) {
                flush();
            }
        }

        void flush() {
            if (pending.isEmpty()) {
                return;
            }
            List<JsonNode> nodes = new ArrayList<>(pending);
            pending.clear();
            List<RejectedRecordRepository.NewRejection> rejections = new ArrayList<>();

            // 1. Validate, normalise and remove duplicates within this run.
            List<Candidate> candidates = new ArrayList<>();
            for (JsonNode node : nodes) {
                CleanResult result = cleaner.clean(node, source);
                switch (result) {
                    case CleanResult.Rejected r -> rejections.add(rejection(r.reason(), r.detail(), r.recordId(), node));
                    case CleanResult.Accepted a -> {
                        Sighting s = a.sighting();
                        String firstWithSameContent = idByContentKey.get(s.contentKey());
                        if (!seenIds.add(s.id())) {
                            rejections.add(rejection(RejectionReason.DUPLICATE_ID,
                                    "Id '" + s.id() + "' appears more than once in this batch", s.id(), node));
                        } else if (firstWithSameContent != null) {
                            rejections.add(rejection(RejectionReason.DUPLICATE_CONTENT,
                                    "Same date, city and species as '" + firstWithSameContent + "' in this batch",
                                    s.id(), node));
                        } else {
                            idByContentKey.put(s.contentKey(), s.id());
                            candidates.add(new Candidate(s, a.repairs(), node));
                        }
                    }
                }
            }

            // 2. Compare with what is already stored.
            Map<String, Sighting> existing = sightings.findByIds(candidates.stream().map(c -> c.sighting().id()).toList());
            Map<String, String> holders = sightings.findIdsByContentKeys(
                    candidates.stream().map(c -> c.sighting().contentKey()).toList());
            List<Sighting> inserts = new ArrayList<>();
            List<Sighting> updates = new ArrayList<>();
            List<String> unchangedIds = new ArrayList<>();
            for (Candidate candidate : candidates) {
                Sighting s = candidate.sighting();
                String holder = holders.get(s.contentKey());
                if (holder != null && !holder.equals(s.id())) {
                    rejections.add(rejection(RejectionReason.DUPLICATE_CONTENT,
                            "Same date, city and species as stored sighting '" + holder + "'", s.id(), candidate.raw()));
                    continue;
                }
                Sighting old = existing.get(s.id());
                if (old == null) {
                    inserts.add(s);
                } else if (old.sameContentAs(s)) {
                    unchangedIds.add(s.id());
                } else {
                    updates.add(s);
                }
                candidate.repairs().forEach(repair -> repairs.merge(repair, 1L, Long::sum));
            }

            // 3. Write the chunk atomically.
            if (!dryRun) {
                Instant now = clock.instant();
                db.inTransaction(connection -> {
                    sightings.applyChanges(connection, inserts, updates, unchangedIds, now);
                    rejected.saveAll(connection, runId, rejections, now);
                    return null;
                });
            }

            inserted += inserts.size();
            updated += updates.size();
            unchanged += unchangedIds.size();
            rejectedCount += rejections.size();
            for (RejectedRecordRepository.NewRejection r : rejections) {
                rejectedByReason.merge(r.reason(), 1L, Long::sum);
                if (samples.size() < MAX_SAMPLES) {
                    samples.add(new SyncReport.RejectionSample(r.reason(), r.detail(), r.recordId(), r.raw()));
                }
            }
        }

        SyncReport report(String trigger, SyncStatus status, Instant started, Instant finished, String error) {
            return new SyncReport(runId, trigger, status, dryRun, started, finished,
                    SyncReport.duration(started, finished), received, inserted, updated, unchanged, rejectedCount,
                    rejectedByReason, repairs, List.copyOf(samples), error);
        }

        private RejectedRecordRepository.NewRejection rejection(RejectionReason reason, String detail,
                                                                String recordId, JsonNode raw) {
            String text = raw == null ? "null" : raw.toString();
            if (text.length() > MAX_RAW_LENGTH) {
                text = text.substring(0, MAX_RAW_LENGTH) + "...";
            }
            return new RejectedRecordRepository.NewRejection(reason, detail, recordId, text);
        }
    }
}
