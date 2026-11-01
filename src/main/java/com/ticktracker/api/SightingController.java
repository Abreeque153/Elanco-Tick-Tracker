package com.ticktracker.api;

import com.ticktracker.domain.Catalogue;
import com.ticktracker.domain.Sighting;
import com.ticktracker.error.NotFoundException;
import com.ticktracker.query.QueryParser;
import com.ticktracker.reports.ReportService;
import com.ticktracker.store.PageResult;
import com.ticktracker.store.SightingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/sightings")
@Tag(name = "1. Sightings", description = "Search and filter sightings by time and location, or report a new one")
public class SightingController {

    private final SightingRepository sightings;
    private final QueryParser queryParser;
    private final Catalogue catalogue;
    private final ReportService reports;

    public SightingController(SightingRepository sightings, QueryParser queryParser, Catalogue catalogue,
                              ReportService reports) {
        this.sightings = sightings;
        this.queryParser = queryParser;
        this.catalogue = catalogue;
        this.reports = reports;
    }

    @GetMapping
    @Operation(summary = "Search sightings",
            description = "Filter by time range (from/to), city, region, species, or a radius around a point. "
                    + "Filters combine with AND. Results are paged, newest first by default.")
    public PageResult<SightingResponse> search(
            @ParameterObject SightingFilter filter,
            @Parameter(description = "Zero-based page number") @RequestParam(name = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, 1-500") @RequestParam(name = "size", defaultValue = "50") int size,
            @Parameter(description = "date,desc (default) or date,asc") @RequestParam(name = "sort", defaultValue = "date,desc") String sort) {
        QueryParser.validatePage(page, size);
        boolean ascending = QueryParser.parseSortAscending(sort);
        return sightings.search(filter.toQuery(queryParser), page, size, ascending)
                .map(s -> SightingResponse.from(s, catalogue));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one sighting by id")
    public SightingResponse byId(@PathVariable("id") String id) {
        return sightings.findById(id)
                .map(s -> SightingResponse.from(s, catalogue))
                .orElseThrow(() -> new NotFoundException("No sighting with id '" + id + "'"));
    }

    @PostMapping
    @Operation(summary = "Report a sighting",
            description = "Adds a public report to the database. City and species must be known (see "
                    + "/api/v1/reference). Returns 409 if the same sighting (time, city, species) already exists.")
    public ResponseEntity<SightingResponse> report(@Valid @RequestBody NewSightingRequest request) {
        Sighting created = reports.submit(request.date(), request.city(), request.species());
        return ResponseEntity.created(URI.create("/api/v1/sightings/" + created.id()))
                .body(SightingResponse.from(created, catalogue));
    }
}
