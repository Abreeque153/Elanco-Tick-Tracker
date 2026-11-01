package com.ticktracker.domain;

/** Where a sighting came from. */
public enum SightingSource {
    /** Pulled from the Elanco tick sightings API. */
    ELANCO_API,
    /** Reported by a member of the public through POST /api/v1/sightings. */
    USER_REPORT,
    /** Loaded from a JSON file through POST /api/v1/sync/import. */
    IMPORT
}
