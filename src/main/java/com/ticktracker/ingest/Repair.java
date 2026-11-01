package com.ticktracker.ingest;

/**
 * A fix applied to an otherwise valid record. Each repair is counted per run,
 * so data-quality problems in the source stay visible even though the record
 * is kept.
 */
public enum Repair {
    /** Case, whitespace or alias fixed, e.g. " london " or "Newcastle upon Tyne". */
    CITY_NORMALISED,
    /** City not in the catalogue. Kept with region "Unknown". */
    UNKNOWN_CITY,
    SPECIES_NORMALISED,
    /** Species name missing or unrecognised, so taken from the Latin name. */
    SPECIES_INFERRED,
    LATIN_NAME_NORMALISED,
    /** Latin name missing or wrong for a known species, so taken from the catalogue. */
    LATIN_NAME_INFERRED,
    /** Neither name matches the catalogue. Kept as supplied. */
    UNKNOWN_SPECIES,
    /** Date given in another format (e.g. 14/08/2024 or with a space) and converted to ISO-8601. */
    DATE_REFORMATTED,
    /** Date had no time part, so midnight was assumed. */
    DATE_WITHOUT_TIME,
    /** Date carried a UTC offset and was converted to UK local time. */
    TIMEZONE_CONVERTED
}
