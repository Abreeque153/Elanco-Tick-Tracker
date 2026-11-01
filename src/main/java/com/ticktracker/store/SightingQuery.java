package com.ticktracker.store;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Filter for sightings. A {@code null} set means "no filter on this field".
 * An <em>empty</em> set means "match nothing", which happens when, say, a
 * radius search finds no cities.
 *
 * @param from        inclusive lower bound, or null
 * @param toExclusive exclusive upper bound, or null
 */
public record SightingQuery(
        LocalDateTime from,
        LocalDateTime toExclusive,
        Set<String> cities,
        Set<String> regions,
        Set<String> species) {

    public static SightingQuery all() {
        return new SightingQuery(null, null, null, null, null);
    }

    public SightingQuery withRegions(Set<String> newRegions) {
        return new SightingQuery(from, toExclusive, cities, newRegions, species);
    }

    public SightingQuery withSpecies(Set<String> newSpecies) {
        return new SightingQuery(from, toExclusive, cities, regions, newSpecies);
    }

    public SightingQuery withTimeRange(LocalDateTime newFrom, LocalDateTime newToExclusive) {
        return new SightingQuery(newFrom, newToExclusive, cities, regions, species);
    }
}
