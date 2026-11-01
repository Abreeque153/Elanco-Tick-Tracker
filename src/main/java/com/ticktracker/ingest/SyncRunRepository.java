package com.ticktracker.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticktracker.store.Database;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persists one row per ingestion run in {@code sync_runs}. */
public final class SyncRunRepository {

    private static final String COLUMNS = "id, trigger_type, status, started_at, finished_at, received, inserted,"
            + " updated, unchanged, rejected, rejected_by_reason, repairs, error";

    private final Database db;
    private final ObjectMapper mapper;

    public SyncRunRepository(Database db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    /** Inserts a RUNNING row and returns its id. */
    public long start(String trigger, Instant startedAt) {
        return db.inTransaction(connection -> {
            Database.update(connection, "INSERT INTO sync_runs (trigger_type, status, started_at) VALUES (?, ?, ?)",
                    List.of(trigger, SyncStatus.RUNNING.name(), startedAt.toString()));
            return Database.query(connection, "SELECT last_insert_rowid()", List.of(), rs -> rs.getLong(1)).get(0);
        });
    }

    public void finish(SyncReport report) {
        List<Object> params = new ArrayList<>();
        params.add(report.status().name());
        params.add(report.finishedAt() == null ? null : report.finishedAt().toString());
        params.add(report.received());
        params.add(report.inserted());
        params.add(report.updated());
        params.add(report.unchanged());
        params.add(report.rejected());
        params.add(toJson(report.rejectedByReason()));
        params.add(toJson(report.repairs()));
        params.add(report.error());
        params.add(report.runId());
        db.update("UPDATE sync_runs SET status = ?, finished_at = ?, received = ?, inserted = ?, updated = ?,"
                + " unchanged = ?, rejected = ?, rejected_by_reason = ?, repairs = ?, error = ? WHERE id = ?", params);
    }

    /** Runs still marked RUNNING at start-up were cut off by a shutdown. Marks them FAILED. */
    public int markInterruptedRuns(Instant now) {
        return db.update("UPDATE sync_runs SET status = ?, finished_at = ?, error = ? WHERE status = ?",
                List.of(SyncStatus.FAILED.name(), now.toString(), "Interrupted: the application stopped during this run",
                        SyncStatus.RUNNING.name()));
    }

    public Optional<SyncReport> latest() {
        return db.queryOne("SELECT " + COLUMNS + " FROM sync_runs ORDER BY id DESC LIMIT 1", List.of(), this::map);
    }

    public Optional<SyncReport> latestUpstream(SyncStatus status) {
        return db.queryOne("SELECT " + COLUMNS + " FROM sync_runs WHERE status = ? AND trigger_type <> 'IMPORT'"
                + " ORDER BY id DESC LIMIT 1", List.of(status.name()), this::map);
    }

    public List<SyncReport> recent(int limit) {
        return db.query("SELECT " + COLUMNS + " FROM sync_runs ORDER BY id DESC LIMIT ?", List.of(limit), this::map);
    }

    private SyncReport map(ResultSet rs) throws SQLException {
        Instant started = parseInstant(rs.getString("started_at"));
        Instant finished = parseInstant(rs.getString("finished_at"));
        return new SyncReport(
                rs.getLong("id"),
                rs.getString("trigger_type"),
                SyncStatus.valueOf(rs.getString("status")),
                false,
                started,
                finished,
                SyncReport.duration(started, finished),
                rs.getLong("received"),
                rs.getLong("inserted"),
                rs.getLong("updated"),
                rs.getLong("unchanged"),
                rs.getLong("rejected"),
                fromJson(rs.getString("rejected_by_reason"), RejectionReason.class),
                fromJson(rs.getString("repairs"), Repair.class),
                List.of(),
                rs.getString("error"));
    }

    private String toJson(Map<?, Long> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <E extends Enum<E>> Map<E, Long> fromJson(String json, Class<E> type) {
        Map<E, Long> result = new EnumMap<>(type);
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            Map<String, Long> raw = mapper.readValue(json, new TypeReference<Map<String, Long>>() { });
            raw.forEach((key, value) -> {
                try {
                    result.put(Enum.valueOf(type, key), value);
                } catch (IllegalArgumentException ignored) {
                    // a reason name from an older version; skip it
                }
            });
        } catch (JsonProcessingException e) {
            // corrupt audit JSON should never break the status endpoint
        }
        return result;
    }

    private static Instant parseInstant(String text) {
        return text == null ? null : Instant.parse(text);
    }
}
