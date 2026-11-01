package com.ticktracker.api;

import com.ticktracker.error.InvalidRequestException;
import com.ticktracker.ingest.RejectedRecordRepository;
import com.ticktracker.ingest.RejectionReason;
import com.ticktracker.ingest.SyncReport;
import com.ticktracker.ingest.SyncService;
import com.ticktracker.query.QueryParser;
import com.ticktracker.store.PageResult;
import com.ticktracker.store.SightingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/data-quality")
@Tag(name = "4. Data pipeline")
public class DataQualityController {

    public record DataQuality(
            long sightingsStored,
            long distinctRejectedRecords,
            Map<RejectionReason, Long> rejectedByReason,
            SyncReport latestRun,
            String howToRead) {
    }

    private final SightingRepository sightings;
    private final RejectedRecordRepository rejected;
    private final SyncService syncService;

    public DataQualityController(SightingRepository sightings, RejectedRecordRepository rejected,
                                 SyncService syncService) {
        this.sightings = sightings;
        this.rejected = rejected;
        this.syncService = syncService;
    }

    @GetMapping
    @Operation(summary = "Data-quality report",
            description = "What the pipeline rejected (by reason) and repaired. Rejected records are kept for "
                    + "auditing at /api/v1/data-quality/rejected.")
    public DataQuality report() {
        return new DataQuality(
                sightings.count(com.ticktracker.store.SightingQuery.all()),
                rejected.count(),
                rejected.countByReason(),
                syncService.recentRuns(1).stream().findFirst().orElse(null),
                "rejectedByReason counts distinct bad records ever seen. latestRun.repairs counts fixes applied "
                        + "to records that were kept (e.g. CITY_NORMALISED for ' london ').");
    }

    @GetMapping("/rejected")
    @Operation(summary = "Browse rejected records", description = "Each record with its reason, detail and raw JSON.")
    public PageResult<RejectedRecordRepository.RejectedRecord> rejectedRecords(
            @Parameter(description = "Filter by reason, e.g. INVALID_DATE") @RequestParam(name = "reason", required = false) String reason,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        QueryParser.validatePage(page, size);
        return rejected.page(parseReason(reason), page, size);
    }

    private static RejectionReason parseReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RejectionReason.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Unknown reason '" + value + "'. Use one of: "
                    + Arrays.stream(RejectionReason.values()).map(Enum::name).collect(Collectors.joining(", ")));
        }
    }
}
