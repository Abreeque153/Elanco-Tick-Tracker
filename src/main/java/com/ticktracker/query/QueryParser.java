package com.ticktracker.query;

import com.ticktracker.domain.Catalogue;
import com.ticktracker.domain.Text;
import com.ticktracker.error.InvalidRequestException;
import com.ticktracker.store.SightingQuery;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns raw query-string values into a validated {@link SightingQuery}.
 *
 * <ul>
 *   <li>Dates accept {@code 2024-05-01}, {@code 2024-05} or
 *       {@code 2024-05-01T09:30:00}. A date-only or month-only {@code to} is
 *       inclusive (the whole day or month is included).</li>
 *   <li>City, region and species filters ignore case and whitespace
 *       ({@code london} = {@code London}), accept several values
 *       ({@code city=London&city=Leeds} or {@code city=London,Leeds}), and
 *       species also matches Latin names.</li>
 *   <li>{@code lat}/{@code lon}/{@code radiusKm} finds sightings in cities
 *       within the radius, e.g. "ticks within 50 km of me".</li>
 * </ul>
 */
public final class QueryParser {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 500;
    public static final double DEFAULT_RADIUS_KM = 25;
    public static final double MAX_RADIUS_KM = 1_000;

    private final Catalogue catalogue;

    public QueryParser(Catalogue catalogue) {
        this.catalogue = catalogue;
    }

    public SightingQuery parse(String from, String to, List<String> cities, List<String> regions,
                               List<String> species, Double lat, Double lon, Double radiusKm) {
        LocalDateTime fromTime = parseBound(from, "from", false);
        LocalDateTime toExclusive = parseBound(to, "to", true);
        if (fromTime != null && toExclusive != null && !fromTime.isBefore(toExclusive)) {
            throw new InvalidRequestException("'from' must be earlier than 'to'");
        }

        Set<String> citySet = canonicalCities(cities);
        if (lat != null || lon != null || radiusKm != null) {
            Set<String> nearby = citiesNear(lat, lon, radiusKm);
            if (citySet == null) {
                citySet = nearby;
            } else {
                citySet.retainAll(nearby);
            }
        }
        return new SightingQuery(fromTime, toExclusive, citySet, canonicalRegions(regions), canonicalSpecies(species));
    }

    public static void validatePage(int page, int size) {
        if (page < 0) {
            throw new InvalidRequestException("'page' must be 0 or more");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("'size' must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    /** "date,desc" (default), "date,asc", "desc" or "asc". */
    public static boolean parseSortAscending(String sort) {
        String value = Text.key(sort);
        if (value == null || value.equals("date,desc") || value.equals("desc") || value.equals("-date")) {
            return false;
        }
        if (value.equals("date,asc") || value.equals("asc") || value.equals("date")) {
            return true;
        }
        throw new InvalidRequestException("'sort' must be 'date,desc' or 'date,asc'");
    }

    /** Parses a time bound. For an upper bound the result is exclusive. */
    public static LocalDateTime parseBound(String value, String name, boolean upper) {
        String text = Text.clean(value);
        if (text == null) {
            return null;
        }
        try {
            LocalDate day = LocalDate.parse(text);
            return upper ? day.plusDays(1).atStartOfDay() : day.atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // not a plain date
        }
        try {
            LocalDateTime time = LocalDateTime.parse(text).truncatedTo(ChronoUnit.SECONDS);
            return upper ? time.plusSeconds(1) : time;
        } catch (DateTimeParseException ignored) {
            // not a date-time
        }
        try {
            YearMonth month = YearMonth.parse(text);
            return upper ? month.plusMonths(1).atDay(1).atStartOfDay() : month.atDay(1).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        throw new InvalidRequestException("'" + name + "' must look like 2024-05-01, 2024-05 or 2024-05-01T09:30:00 (got '"
                + value + "')");
    }

    private Set<String> citiesNear(Double lat, Double lon, Double radiusKm) {
        if (lat == null || lon == null) {
            throw new InvalidRequestException("'lat' and 'lon' must be given together");
        }
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new InvalidRequestException("'lat' must be -90..90 and 'lon' -180..180");
        }
        double radius = radiusKm == null ? DEFAULT_RADIUS_KM : radiusKm;
        if (radius <= 0 || radius > MAX_RADIUS_KM) {
            throw new InvalidRequestException("'radiusKm' must be greater than 0 and at most " + (int) MAX_RADIUS_KM);
        }
        return catalogue.citiesWithin(lat, lon, radius).stream()
                .map(Catalogue.City::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> canonicalCities(List<String> raw) {
        List<String> values = split(raw);
        if (values.isEmpty()) {
            return null;
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            // Unknown cities are stored title-cased, so normalise the filter the same way.
            result.add(catalogue.findCity(value).map(Catalogue.City::name).orElse(Text.titleCase(value)));
        }
        return result;
    }

    private Set<String> canonicalRegions(List<String> raw) {
        List<String> values = split(raw);
        if (values.isEmpty()) {
            return null;
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            result.add(catalogue.findRegion(value).orElseThrow(() -> new InvalidRequestException(
                    "Unknown region '" + value + "'. Valid regions: " + String.join(", ", catalogue.regions()))));
        }
        return result;
    }

    private Set<String> canonicalSpecies(List<String> raw) {
        List<String> values = split(raw);
        if (values.isEmpty()) {
            return null;
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            result.add(catalogue.findSpeciesByAnyName(value).map(Catalogue.Species::name).orElse(Text.clean(value)));
        }
        return result;
    }

    /** Accepts repeated params and comma-separated values; drops blanks. */
    private static List<String> split(List<String> raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            for (String part : entry.split(",")) {
                String cleaned = Text.clean(part);
                if (cleaned != null) {
                    values.add(cleaned);
                }
            }
        }
        return values;
    }
}
