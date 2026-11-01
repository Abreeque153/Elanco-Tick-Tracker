package com.ticktracker.api;

import com.ticktracker.query.QueryParser;
import com.ticktracker.store.SightingQuery;
import io.swagger.v3.oas.annotations.Parameter;

import java.util.List;

/**
 * Query-string filters shared by the search, statistics and trend endpoints.
 * Spring binds the request parameters straight into this record.
 */
public record SightingFilter(
        @Parameter(description = "Start, inclusive. 2024-05-01, 2024-05 or 2024-05-01T09:30:00")
        String from,

        @Parameter(description = "End, inclusive. 2024-05-31, 2024-05 or 2024-05-31T18:00:00")
        String to,

        @Parameter(description = "City, case-insensitive. Repeat or comma-separate for several, e.g. london,Leeds")
        List<String> city,

        @Parameter(description = "Region, e.g. Scotland, North West. Repeat or comma-separate for several")
        List<String> region,

        @Parameter(description = "Species common or Latin name, e.g. Marsh tick or Ixodes canisuga")
        List<String> species,

        @Parameter(description = "Latitude for a radius search, e.g. 51.546 (Uxbridge)")
        Double lat,

        @Parameter(description = "Longitude for a radius search, e.g. -0.479")
        Double lon,

        @Parameter(description = "Radius in km around lat/lon (default 25, max 1000)")
        Double radiusKm) {

    public SightingQuery toQuery(QueryParser parser) {
        return parser.parse(from, to, city, region, species, lat, lon, radiusKm);
    }

    /** Human-readable description of the active filters. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        append(sb, "from", from);
        append(sb, "to", to);
        append(sb, "city", city == null ? null : String.join(",", city));
        append(sb, "region", region == null ? null : String.join(",", region));
        append(sb, "species", species == null ? null : String.join(",", species));
        if (lat != null || lon != null) {
            append(sb, "near", lat + "," + lon + " within " + (radiusKm == null ? QueryParser.DEFAULT_RADIUS_KM : radiusKm) + " km");
        }
        return sb.isEmpty() ? "All UK sightings" : sb.toString();
    }

    private static void append(StringBuilder sb, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("; ");
        }
        sb.append(name).append('=').append(value);
    }
}
