-- Tick tracker schema. Applied on every start-up; every statement is idempotent.
-- Timestamps are stored as ISO-8601 text ('YYYY-MM-DDTHH:MM:SS'), which sorts
-- correctly as text and works with SQLite's date functions.

-- Clean, de-duplicated sightings that the API serves.
CREATE TABLE IF NOT EXISTS sightings (
    id            TEXT PRIMARY KEY,
    sighted_at    TEXT NOT NULL,
    city          TEXT NOT NULL,
    region        TEXT NOT NULL,
    species       TEXT NOT NULL,
    latin_name    TEXT,
    source        TEXT NOT NULL,
    -- date|city|species, used to catch the same sighting submitted under two ids
    content_key   TEXT NOT NULL UNIQUE,
    first_seen_at TEXT NOT NULL,
    last_seen_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sightings_time        ON sightings (sighted_at);
CREATE INDEX IF NOT EXISTS idx_sightings_city_time   ON sightings (city, sighted_at);
CREATE INDEX IF NOT EXISTS idx_sightings_region_time ON sightings (region, sighted_at);
CREATE INDEX IF NOT EXISTS idx_sightings_species     ON sightings (species, sighted_at);

-- Records that failed validation or were duplicates, kept for auditing.
-- The fingerprint makes re-syncs idempotent: the same bad record is stored once
-- and its times_seen counter goes up.
CREATE TABLE IF NOT EXISTS rejected_records (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    fingerprint   TEXT NOT NULL UNIQUE,
    sync_run_id   INTEGER,
    reason        TEXT NOT NULL,
    detail        TEXT,
    record_id     TEXT,
    raw_json      TEXT NOT NULL,
    first_seen_at TEXT NOT NULL,
    last_seen_at  TEXT NOT NULL,
    times_seen    INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_rejected_reason ON rejected_records (reason);

-- One row per ingestion run (scheduled, manual or file import).
CREATE TABLE IF NOT EXISTS sync_runs (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    trigger_type       TEXT NOT NULL,
    status             TEXT NOT NULL,
    started_at         TEXT NOT NULL,
    finished_at        TEXT,
    received           INTEGER NOT NULL DEFAULT 0,
    inserted           INTEGER NOT NULL DEFAULT 0,
    updated            INTEGER NOT NULL DEFAULT 0,
    unchanged          INTEGER NOT NULL DEFAULT 0,
    rejected           INTEGER NOT NULL DEFAULT 0,
    rejected_by_reason TEXT,
    repairs            TEXT,
    error              TEXT
);
