package com.ticktracker.ingest;

public enum SyncStatus {
    RUNNING,
    /** Every record was read and processed. */
    SUCCESS,
    /** Some records were processed before the source failed. Those are kept, and the next run completes the rest. */
    PARTIAL,
    /** Nothing could be read (upstream down, circuit open, invalid payload). Existing data is untouched. */
    FAILED
}
