package com.ticktracker.ingest;

import com.ticktracker.store.Database;
import com.ticktracker.store.PageResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit trail of records that were not stored. Each distinct bad record is
 * kept once; seeing it again on a later sync increments {@code times_seen}.
 */
public final class RejectedRecordRepository {

    public record RejectedRecord(
            long id,
            RejectionReason reason,
            String detail,
            String recordId,
            String raw,
            Long lastSyncRunId,
            Instant firstSeenAt,
            Instant lastSeenAt,
            int timesSeen) {
    }

    public record NewRejection(RejectionReason reason, String detail, String recordId, String raw) {
    }

    private static final String COLUMNS =
            "id, reason, detail, record_id, raw_json, sync_run_id, first_seen_at, last_seen_at, times_seen";

    private final Database db;

    public RejectedRecordRepository(Database db) {
        this.db = db;
    }

    public void saveAll(Connection connection, Long syncRunId, List<NewRejection> rejections, Instant now)
            throws SQLException {
        if (rejections.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO rejected_records (fingerprint, sync_run_id, reason, detail, record_id, raw_json,"
                        + " first_seen_at, last_seen_at, times_seen) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)"
                        + " ON CONFLICT (fingerprint) DO UPDATE SET sync_run_id = excluded.sync_run_id,"
                        + " detail = excluded.detail, last_seen_at = excluded.last_seen_at,"
                        + " times_seen = rejected_records.times_seen + 1")) {
            for (NewRejection r : rejections) {
                ps.setString(1, fingerprint(r));
                ps.setObject(2, syncRunId);
                ps.setString(3, r.reason().name());
                ps.setString(4, r.detail());
                ps.setString(5, r.recordId());
                ps.setString(6, r.raw());
                ps.setString(7, now.toString());
                ps.setString(8, now.toString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public PageResult<RejectedRecord> page(RejectionReason reason, int page, int size) {
        String where = reason == null ? "" : " WHERE reason = ?";
        List<Object> params = new ArrayList<>();
        if (reason != null) {
            params.add(reason.name());
        }
        long total = db.queryLong("SELECT COUNT(*) FROM rejected_records" + where, params);
        params.add(size);
        params.add((long) page * size);
        List<RejectedRecord> items = db.query(
                "SELECT " + COLUMNS + " FROM rejected_records" + where + " ORDER BY last_seen_at DESC, id DESC LIMIT ? OFFSET ?",
                params,
                rs -> new RejectedRecord(
                        rs.getLong("id"),
                        RejectionReason.valueOf(rs.getString("reason")),
                        rs.getString("detail"),
                        rs.getString("record_id"),
                        rs.getString("raw_json"),
                        rs.getObject("sync_run_id", Long.class),
                        Instant.parse(rs.getString("first_seen_at")),
                        Instant.parse(rs.getString("last_seen_at")),
                        rs.getInt("times_seen")));
        return PageResult.of(items, page, size, total);
    }

    public Map<RejectionReason, Long> countByReason() {
        Map<RejectionReason, Long> result = new LinkedHashMap<>();
        db.query("SELECT reason, COUNT(*) AS n FROM rejected_records GROUP BY reason ORDER BY n DESC, reason", List.of(),
                        rs -> Map.entry(RejectionReason.valueOf(rs.getString("reason")), rs.getLong("n")))
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    public long count() {
        return db.queryLong("SELECT COUNT(*) FROM rejected_records", List.of());
    }

    private static String fingerprint(NewRejection r) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest((r.reason().name() + "\n" + r.raw()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
