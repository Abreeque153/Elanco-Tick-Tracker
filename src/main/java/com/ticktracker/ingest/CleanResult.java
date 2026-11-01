package com.ticktracker.ingest;

import com.ticktracker.domain.Sighting;

import java.util.Set;

/** The outcome of cleaning one raw record: a sighting (possibly repaired) or a rejection. */
public sealed interface CleanResult {

    record Accepted(Sighting sighting, Set<Repair> repairs) implements CleanResult {
    }

    record Rejected(RejectionReason reason, String detail, String recordId) implements CleanResult {
    }
}
