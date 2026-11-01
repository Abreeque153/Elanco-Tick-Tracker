package com.ticktracker.domain;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/**
 * A clean, validated tick sighting.
 *
 * <p>The source data has no time zone, and every location is a UK city, so
 * times are kept as UK local {@link LocalDateTime} with one-second precision.
 */
public record Sighting(
        String id,
        LocalDateTime date,
        String city,
        String region,
        String species,
        String latinName,
        SightingSource source) {

    /** Storage format. Fixed width, so text order equals time order. */
    public static final DateTimeFormatter STORAGE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    public Sighting {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(source, "source");
        date = date.truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Identifies the real-world event (when, where, what) regardless of id. Two
     * records with the same key but different ids are treated as the same
     * sighting submitted twice.
     */
    public String contentKey() {
        return STORAGE_FORMAT.format(date) + "|" + city.toLowerCase(Locale.ROOT) + "|" + species.toLowerCase(Locale.ROOT);
    }

    /** True when every stored attribute except the source matches. */
    public boolean sameContentAs(Sighting other) {
        return other != null
                && date.equals(other.date)
                && city.equals(other.city)
                && region.equals(other.region)
                && species.equals(other.species)
                && Objects.equals(latinName, other.latinName);
    }
}
