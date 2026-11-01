package com.ticktracker.api;

import com.ticktracker.error.ConflictException;
import com.ticktracker.error.InvalidRequestException;
import com.ticktracker.ingest.SyncReport;
import com.ticktracker.ingest.SyncScheduler;
import com.ticktracker.ingest.SyncService;
import com.ticktracker.ingest.SyncStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sync")
@Tag(name = "4. Data pipeline", description = "Ingestion status and controls: sync from the Elanco API or import a JSON file")
public class SyncController {

    private final SyncService syncService;
    private final SyncScheduler scheduler;

    public SyncController(SyncService syncService, SyncScheduler scheduler) {
        this.syncService = syncService;
        this.scheduler = scheduler;
    }

    @GetMapping("/status")
    @Operation(summary = "Sync health",
            description = "Whether a sync is running, when the data was last refreshed, whether it is stale, the "
                    + "circuit-breaker state, and the latest run's report.")
    public SyncService.SyncState status() {
        return syncService.state();
    }

    @GetMapping("/runs")
    @Operation(summary = "Recent ingestion runs")
    public List<SyncReport> runs(
            @Parameter(description = "How many runs, 1-100") @RequestParam(name = "limit", defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) {
            throw new InvalidRequestException("'limit' must be between 1 and 100");
        }
        return syncService.recentRuns(limit);
    }

    @PostMapping
    @Operation(summary = "Sync from the Elanco API now",
            description = "Starts a background sync and returns 202 straight away, because with retries and rate "
                    + "limits a sync can take a while. Poll /api/v1/sync/status for the result.")
    public ResponseEntity<Map<String, String>> syncNow() {
        if (!scheduler.triggerNow()) {
            throw new ConflictException("A sync is already running or queued");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "message", "Sync started",
                "status", "/api/v1/sync/status"));
    }

    @PostMapping(path = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Import a JSON array of sightings",
            description = "Runs the body (same shape as the Elanco API) through the full pipeline: validation, "
                    + "repairs, de-duplication and storage. Use dryRun=true to see the data-quality report without "
                    + "saving anything. Try it with docs/demo-dirty-batch.json.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject(value = """
                            [
                              {"id": "demo-1", "date": "14/08/2024 09:30", "location": " london ", "species": "marsh tick"},
                              {"id": "demo-2", "date": "2024-08-14", "location": "Leeds", "latinName": "Ixodes canisuga"},
                              {"id": "demo-3", "date": "2024-02-31T10:00:00", "location": "Leeds", "species": "Marsh tick"},
                              {"id": "demo-4", "location": "Bristol", "species": "Passerine tick"}
                            ]"""))))
    public ResponseEntity<SyncReport> importJson(
            InputStream body,
            @Parameter(description = "Validate and report only; write nothing")
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        SyncReport report = syncService.importJson(body, dryRun);
        HttpStatus status = report.status() == SyncStatus.FAILED ? HttpStatus.BAD_REQUEST : HttpStatus.OK;
        return ResponseEntity.status(status).body(report);
    }
}
