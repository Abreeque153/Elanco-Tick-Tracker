package com.ticktracker.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticktracker.domain.Catalogue;
import com.ticktracker.domain.Sighting;
import com.ticktracker.domain.SightingSource;
import com.ticktracker.domain.Text;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Turns one raw JSON record into a clean {@link Sighting} or a rejection with
 * a reason.
 *
 * <p>Two rules decide what happens:
 * <ul>
 *   <li><b>Reject</b> only when the record cannot be trusted: no id, no usable
 *       date, no location, no species information, or a species that
 *       contradicts its Latin name.</li>
 *   <li><b>Repair</b> anything that is fixable without guessing: casing,
 *       whitespace, city aliases, alternative date formats, and a missing
 *       species or Latin name when the other one is known. Every repair is
 *       recorded so the data-quality report shows it.</li>
 * </ul>
 *
 * <p>The reader is tolerant: it also accepts {@code city} for
 * {@code location} and {@code latin_name} for {@code latinName}, and
 * ignores unknown fields.
 */
public final class SightingCleaner {

    /** The API documents dates as local times with no offset; all locations are in the UK. */
    public static final ZoneId UK = ZoneId.of("Europe/London");

    private static final LocalDateTime EARLIEST_PLAUSIBLE = LocalDateTime.of(1900, 1, 1, 0, 0);

    /** Alternative local date-time formats, tried in order after ISO-8601. UK day-first order is assumed. */
    private static final List<DateTimeFormatter> LOCAL_DATE_TIMES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            strict("uuuu-MM-dd HH:mm[:ss]"),
            strict("d/M/uuuu H:mm[:ss]"),
            strict("d-M-uuuu H:mm[:ss]"));

    private static final List<DateTimeFormatter> LOCAL_DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            strict("d/M/uuuu"),
            strict("d-M-uuuu"));

    private final Catalogue catalogue;
    private final Clock clock;

    public SightingCleaner(Catalogue catalogue, Clock clock) {
        this.catalogue = catalogue;
        this.clock = clock;
    }

    public CleanResult clean(JsonNode node, SightingSource source) {
        if (node == null || !node.isObject()) {
            return reject(RejectionReason.MALFORMED_RECORD, "Expected a JSON object but got " + describe(node), null);
        }

        String id = Text.clean(scalar(node, "id"));
        if (id == null) {
            return reject(RejectionReason.MISSING_ID, "Record has no usable 'id'", null);
        }
        EnumSet<Repair> repairs = EnumSet.noneOf(Repair.class);

        // --- date --------------------------------------------------------
        JsonNode dateNode = node.get("date");
        if (dateNode == null || dateNode.isNull() || (dateNode.isTextual() && dateNode.asText().isBlank())) {
            return reject(RejectionReason.MISSING_DATE, "Record has no 'date'", id);
        }
        LocalDateTime date = parseDate(dateNode, repairs);
        if (date == null) {
            return reject(RejectionReason.INVALID_DATE, "Unrecognised or impossible date '" + dateNode.asText() + "'", id);
        }
        if (date.isBefore(EARLIEST_PLAUSIBLE)) {
            return reject(RejectionReason.INVALID_DATE, "Date " + date + " is implausibly old", id);
        }
        if (date.isAfter(LocalDateTime.now(clock.withZone(UK)).plusDays(1))) {
            return reject(RejectionReason.FUTURE_DATE, "Date " + date + " is in the future", id);
        }

        // --- location ----------------------------------------------------
        String rawLocation = firstScalar(node, "location", "city");
        String location = Text.clean(rawLocation);
        if (location == null) {
            return reject(RejectionReason.MISSING_LOCATION, "Record has no 'location'", id);
        }
        String cityName;
        String region;
        Optional<Catalogue.City> knownCity = catalogue.findCity(location);
        if (knownCity.isPresent()) {
            cityName = knownCity.get().name();
            region = knownCity.get().region();
            if (!cityName.equals(rawLocation)) {
                repairs.add(Repair.CITY_NORMALISED);
            }
        } else {
            cityName = Text.titleCase(location);
            region = Catalogue.UNKNOWN_REGION;
            repairs.add(Repair.UNKNOWN_CITY);
        }

        // --- species -----------------------------------------------------
        String rawSpecies = firstScalar(node, "species");
        String rawLatin = firstScalar(node, "latinName", "latin_name");
        String speciesText = Text.clean(rawSpecies);
        String latinText = Text.clean(rawLatin);
        if (speciesText == null && latinText == null) {
            return reject(RejectionReason.MISSING_SPECIES, "Record has neither 'species' nor 'latinName'", id);
        }
        Optional<Catalogue.Species> byName = catalogue.findSpecies(speciesText);
        Optional<Catalogue.Species> byLatin = catalogue.findSpeciesByLatinName(latinText);
        if (byName.isPresent() && byLatin.isPresent() && !byName.get().equals(byLatin.get())) {
            return reject(RejectionReason.SPECIES_CONFLICT,
                    "'" + speciesText + "' is " + byName.get().latinName() + ", not '" + latinText + "'", id);
        }

        String species;
        String latinName;
        if (byName.isPresent()) {
            species = byName.get().name();
            latinName = byName.get().latinName();
            if (!species.equals(rawSpecies)) {
                repairs.add(Repair.SPECIES_NORMALISED);
            }
            if (byLatin.isEmpty()) {
                repairs.add(Repair.LATIN_NAME_INFERRED);
            } else if (!latinName.equals(rawLatin)) {
                repairs.add(Repair.LATIN_NAME_NORMALISED);
            }
        } else if (byLatin.isPresent()) {
            species = byLatin.get().name();
            latinName = byLatin.get().latinName();
            repairs.add(Repair.SPECIES_INFERRED);
            if (!latinName.equals(rawLatin)) {
                repairs.add(Repair.LATIN_NAME_NORMALISED);
            }
        } else {
            species = speciesText != null ? speciesText : latinText;
            latinName = latinText;
            repairs.add(Repair.UNKNOWN_SPECIES);
        }

        return new CleanResult.Accepted(new Sighting(id, date, cityName, region, species, latinName, source), repairs);
    }

    // ------------------------------------------------------------- helpers

    /** Parses the formats seen in real-world exports, returning null when none match. */
    static LocalDateTime parseDate(JsonNode node, EnumSet<Repair> repairs) {
        if (node.isNumber()) {
            long value = node.asLong();
            Instant instant = value > 100_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
            repairs.add(Repair.DATE_REFORMATTED);
            repairs.add(Repair.TIMEZONE_CONVERTED);
            return LocalDateTime.ofInstant(instant, UK);
        }
        if (!node.isTextual()) {
            return null;
        }
        String raw = node.asText().strip();

        try {
            OffsetDateTime withOffset = OffsetDateTime.parse(raw);
            repairs.add(Repair.TIMEZONE_CONVERTED);
            return withOffset.atZoneSameInstant(UK).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // not an offset date-time; try local formats
        }
        for (DateTimeFormatter format : LOCAL_DATE_TIMES) {
            try {
                LocalDateTime parsed = LocalDateTime.parse(raw, format);
                if (!Sighting.STORAGE_FORMAT.format(parsed.truncatedTo(ChronoUnit.SECONDS)).equals(raw)) {
                    repairs.add(Repair.DATE_REFORMATTED);
                }
                return parsed;
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        for (DateTimeFormatter format : LOCAL_DATES) {
            try {
                LocalDate parsed = LocalDate.parse(raw, format);
                repairs.add(Repair.DATE_WITHOUT_TIME);
                if (format != DateTimeFormatter.ISO_LOCAL_DATE) {
                    repairs.add(Repair.DATE_REFORMATTED);
                }
                return parsed.atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return null;
    }

    /** Text of a scalar field; null when missing, JSON null, or an object/array. */
    private static String scalar(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        return value.asText();
    }

    private static String firstScalar(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = scalar(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String describe(JsonNode node) {
        if (node == null) {
            return "nothing";
        }
        String text = node.toString();
        return node.getNodeType().name().toLowerCase() + " " + (text.length() > 60 ? text.substring(0, 60) + "..." : text);
    }

    private static CleanResult reject(RejectionReason reason, String detail, String recordId) {
        return new CleanResult.Rejected(reason, detail, recordId);
    }

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }
}
