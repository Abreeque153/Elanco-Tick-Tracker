package com.ticktracker.api;

import com.ticktracker.analytics.TrendService;
import com.ticktracker.domain.Catalogue;
import com.ticktracker.ingest.SyncService;
import com.ticktracker.query.QueryParser;
import com.ticktracker.store.Dimension;
import com.ticktracker.store.SightingQuery;
import com.ticktracker.store.SightingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/stats")
@Tag(name = "2. Statistics", description = "Aggregate insights: totals, sightings per region/city/species and trends over time")
public class StatsController {

    public record Summary(SightingRepository.DatasetSummary dataset, Instant lastSuccessfulSync, boolean dataStale) {
    }

    public record GroupItem(String name, String region, String latinName, long count, double percentage) {
    }

    public record GroupStats(String groupBy, String filters, long total, List<GroupItem> items) {
    }

    private final SightingRepository sightings;
    private final TrendService trends;
    private final QueryParser queryParser;
    private final Catalogue catalogue;
    private final SyncService syncService;

    public StatsController(SightingRepository sightings, TrendService trends, QueryParser queryParser,
                           Catalogue catalogue, SyncService syncService) {
        this.sightings = sightings;
        this.trends = trends;
        this.queryParser = queryParser;
        this.catalogue = catalogue;
        this.syncService = syncService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Dataset overview", description = "Total sightings, date range, coverage and data freshness.")
    public Summary summary() {
        return new Summary(sightings.summary(), syncService.lastSuccessfulSync().orElse(null), syncService.isDataStale());
    }

    @GetMapping("/by-region")
    @Operation(summary = "Number of sightings per region",
            description = "Counts per UK region (ONS regions and nations), largest first, with each region's share.")
    public GroupStats byRegion(@ParameterObject SightingFilter filter) {
        return group(Dimension.REGION, filter);
    }

    @GetMapping("/by-city")
    @Operation(summary = "Number of sightings per city")
    public GroupStats byCity(@ParameterObject SightingFilter filter) {
        return group(Dimension.CITY, filter);
    }

    @GetMapping("/by-species")
    @Operation(summary = "Number of sightings per species")
    public GroupStats bySpecies(@ParameterObject SightingFilter filter) {
        return group(Dimension.SPECIES, filter);
    }

    @GetMapping("/trends")
    @Operation(summary = "Trends over time",
            description = "Sightings per week (ISO weeks), month or year. Periods with no sightings are filled with 0, "
                    + "so the series can be charted directly. Optionally split into one series per region, city or "
                    + "species.")
    public TrendService.Trend trends(
            @ParameterObject SightingFilter filter,
            @Parameter(description = "week, month (default) or year") @RequestParam(name = "interval", defaultValue = "month") String interval,
            @Parameter(description = "Optional: region, city or species") @RequestParam(name = "splitBy", required = false) String splitBy) {
        return trends.trend(filter.toQuery(queryParser), TrendService.Interval.parse(interval), Dimension.parse(splitBy));
    }

    private GroupStats group(Dimension dimension, SightingFilter filter) {
        SightingQuery query = filter.toQuery(queryParser);
        List<SightingRepository.GroupCount> rows = sightings.countBy(dimension, query);
        long total = rows.stream().mapToLong(SightingRepository.GroupCount::count).sum();
        List<GroupItem> items = rows.stream().map(row -> new GroupItem(
                row.name(),
                dimension == Dimension.CITY
                        ? catalogue.findCity(row.name()).map(Catalogue.City::region).orElse(Catalogue.UNKNOWN_REGION)
                        : null,
                dimension == Dimension.SPECIES
                        ? catalogue.findSpecies(row.name()).map(Catalogue.Species::latinName).orElse(null)
                        : null,
                row.count(),
                total == 0 ? 0 : Math.round(row.count() * 1000.0 / total) / 10.0)).toList();
        return new GroupStats(dimension.column(), filter.describe(), total, items);
    }
}
