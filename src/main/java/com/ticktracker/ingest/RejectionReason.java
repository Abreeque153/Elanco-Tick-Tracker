package com.ticktracker.ingest;

/** Why an incoming record was not stored. */
public enum RejectionReason {
    /** The element is not a JSON object, e.g. a bare string or number in the array. */
    MALFORMED_RECORD,
    MISSING_ID,
    MISSING_DATE,
    /** The date could not be parsed, does not exist (31/02) or is implausibly old. */
    INVALID_DATE,
    /** More than a day in the future. */
    FUTURE_DATE,
    MISSING_LOCATION,
    /** Neither a species nor a Latin name was supplied. */
    MISSING_SPECIES,
    /** The species and Latin name refer to two different known species. */
    SPECIES_CONFLICT,
    /** Another record in the same batch already used this id. */
    DUPLICATE_ID,
    /** Same time, city and species as another sighting stored under a different id. */
    DUPLICATE_CONTENT
}
