package com.ticktracker.ingest;

import com.ticktracker.domain.Sighting;
import com.ticktracker.store.Database;
import com.ticktracker.store.SightingQuery;
import com.ticktracker.store.SightingRepository;
import com.ticktracker.support.FakeUpstream;
import com.ticktracker.support.MutableClock;
import com.ticktracker.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncServiceTest {

    @TempDir
    Path tempDir;

    private final FakeUpstream upstream = new FakeUpstream();
    private final MutableClock clock = TestSupport.clock();
    private Database db;
    private SightingRepository sightings;
    private RejectedRecordRepository rejected;
    private SyncRunRepository runs;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        db = TestSupport.database(tempDir);
        sightings = new SightingRepository(db);
        rejected = new RejectedRecordRepository(db);
        runs = new SyncRunRepository(db, TestSupport.MAPPER);
        breaker = new CircuitBreaker(3, Duration.ofMinutes(2), clock);
    }

    @AfterEach
    void tearDown() {
        upstream.close();
    }

    private SyncService service(int chunkSize) {
        ElancoApiClient client = TestSupport.client(upstream, 2, breaker, new ArrayList<>(), clock);
        return new SyncService(db, sightings, rejected, runs, new SightingCleaner(TestSupport.CATALOGUE, clock), client,
                new JsonArrayReader(TestSupport.MAPPER), clock, chunkSize, Duration.ofHours(2));
    }

    @Test
    void cleansDeduplicatesAndStoresARealisticDirtyFeed() {
        upstream.respondAlways(FakeUpstream.Response.ok(TestSupport.fixture("upstream-dirty.json")));
        SyncReport report = service(500).syncFromUpstream("TEST");

        assertEquals(SyncStatus.SUCCESS, report.status());
        assertEquals(29, report.received());
        assertEquals(19, report.inserted());
        assertEquals(10, report.rejected());
        assertEquals(19, sightings.count(SightingQuery.all()));

        Map<RejectionReason, Long> byReason = report.rejectedByReason();
        for (RejectionReason reason : RejectionReason.values()) {
            assertEquals(1L, byReason.get(reason), reason + " should occur exactly once in the fixture");
        }
        assertEquals(1L, report.repairs().get(Repair.UNKNOWN_CITY));
        assertEquals(1L, report.repairs().get(Repair.TIMEZONE_CONVERTED));
        assertEquals(10, rejected.count(), "every rejection is kept for auditing");

        Sighting repaired = sightings.findById("fix-1").orElseThrow();
        assertEquals("Newcastle", repaired.city());
        assertEquals("Marsh tick", repaired.species());
        assertEquals("Ixodes apronophorus", repaired.latinName());
        assertEquals(Sighting.STORAGE_FORMAT.format(repaired.date()), "2024-08-14T09:30:00");
    }

    @Test
    void reSyncingIsIdempotent() {
        upstream.respondAlways(FakeUpstream.Response.ok(TestSupport.fixture("upstream-dirty.json")));
        SyncService service = service(500);
        service.syncFromUpstream("FIRST");
        SyncReport second = service.syncFromUpstream("SECOND");

        assertEquals(0, second.inserted());
        assertEquals(0, second.updated());
        assertEquals(19, second.unchanged());
        assertEquals(19, sightings.count(SightingQuery.all()), "no duplicates created");
        assertEquals(10, rejected.count(), "the same bad records are not stored twice");
    }

    @Test
    void detectsUpdatedRecordsAndDuplicatesOfStoredSightings() {
        upstream.respondAlways(FakeUpstream.Response.ok(TestSupport.fixture("upstream-clean.json")));
        SyncService service = service(500);
        service.syncFromUpstream("FIRST");

        upstream.respondAlways(FakeUpstream.Response.ok("""
                [
                  {"id":"02WNholuSg6ndCk4c1dA","date":"2022-08-01T06:40:31","location":"Liverpool","species":"Marsh tick"},
                  {"id":"brand-new-id","date":"2014-09-12T23:33:03","location":"LONDON","species":"Southern rodent tick"}
                ]"""));
        SyncReport second = service.syncFromUpstream("SECOND");

        assertEquals(1, second.updated());
        assertEquals("Liverpool", sightings.findById("02WNholuSg6ndCk4c1dA").orElseThrow().city());
        assertEquals(1L, second.rejectedByReason().get(RejectionReason.DUPLICATE_CONTENT));
        assertTrue(sightings.findById("brand-new-id").isEmpty());
    }

    @Test
    void anUpstreamOutageLeavesExistingDataUntouched() {
        upstream.respondAlways(FakeUpstream.Response.ok(TestSupport.fixture("upstream-clean.json")));
        SyncService service = service(500);
        service.syncFromUpstream("FIRST");
        assertNotNull(service.lastSuccessfulSync().orElse(null));

        upstream.respondAlways(FakeUpstream.Response.status(500, "{\"error\":\"Internal server error\"}"));
        SyncReport failed = service.syncFromUpstream("SECOND");

        assertEquals(SyncStatus.FAILED, failed.status());
        assertTrue(failed.error().contains("HTTP 500"), failed.error());
        assertEquals(15, sightings.count(SightingQuery.all()), "data is still served");
        assertEquals(SyncStatus.FAILED, runs.latest().orElseThrow().status());
        assertEquals(SyncStatus.SUCCESS, runs.latestUpstream(SyncStatus.SUCCESS).orElseThrow().status());
    }

    @Test
    void aBrokenStreamKeepsTheChunksAlreadyCommitted() {
        String clean = TestSupport.fixture("upstream-clean.json").strip();
        String truncated = clean.substring(0, clean.lastIndexOf("{")); // drop the last record and the closing ]
        upstream.respondAlways(FakeUpstream.Response.ok(truncated));

        SyncReport report = service(4).syncFromUpstream("TEST");

        assertEquals(SyncStatus.PARTIAL, report.status());
        assertEquals(14, report.received());
        assertEquals(14, sightings.count(SightingQuery.all()));
        assertNotNull(report.error());
    }

    @Test
    void dryRunImportReportsWithoutWriting() {
        SyncService service = service(500);
        SyncReport report = service.importJson(stream(TestSupport.fixture("upstream-dirty.json")), true);

        assertTrue(report.dryRun());
        assertNull(report.runId());
        assertEquals(19, report.inserted(), "would insert 19");
        assertEquals(10, report.rejected());
        assertFalse(report.rejectionSamples().isEmpty());
        assertEquals(0, sightings.count(SightingQuery.all()));
        assertEquals(0, rejected.count());
        assertTrue(runs.recent(10).isEmpty());
    }

    @Test
    void importOfInvalidJsonFails() {
        SyncReport report = service(500).importJson(stream("{\"not\":\"an array\"}"), false);
        assertEquals(SyncStatus.FAILED, report.status());
        assertEquals(0, report.received());
    }

    @Test
    void reportsStalenessFromTheLastSuccessfulSync() {
        SyncService service = service(500);
        assertTrue(service.isDataStale(), "never synced");

        upstream.respondAlways(FakeUpstream.Response.ok("[]"));
        service.syncFromUpstream("TEST");
        assertFalse(service.isDataStale());

        clock.advance(Duration.ofHours(3));
        assertTrue(service.isDataStale());
    }

    @Test
    void marksRunsInterruptedByAShutdownAsFailed() {
        runs.start("SCHEDULED", TestSupport.NOW);
        service(500); // constructing the service performs start-up recovery
        List<SyncReport> recent = runs.recent(1);
        assertEquals(SyncStatus.FAILED, recent.get(0).status());
        assertTrue(recent.get(0).error().startsWith("Interrupted"));
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
