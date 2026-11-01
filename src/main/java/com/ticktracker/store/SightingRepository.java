package com.ticktracker.store;

import com.ticktracker.domain.Sighting;
import com.ticktracker.domain.SightingSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** All SQL for the {@code sightings} table. */
public final class SightingRepository {

    public record GroupCount(String name, long count) {
    }

    public record DailyCount(LocalDate day, String series, long count) {
    }

    public record DatasetSummary(
            long totalSightings,
            LocalDateTime firstSighting,
            LocalDateTime lastSighting,
            long cities,
            long regions,
            long species,
            Map<String, Long> bySource) {
    }

    /** Series name used when trends are not split by a dimension. */
    public static final String ALL_SERIES = "All sightings";

    private static final String COLUMNS = "id, sighted_at, city, region, species, latin_name, source";
    /** Keeps IN (...) lists well below SQLite's bound-parameter limit. */
    private static final int IN_CLAUSE_CHUNK = 500;

    private static final Database.RowMapper<Sighting> SIGHTING = rs -> new Sighting(
            rs.getString("id"),
            parseTime(rs.getString("sighted_at")),
            rs.getString("city"),
            rs.getString("region"),
            rs.getString("species"),
            rs.getString("latin_name"),
            SightingSource.valueOf(rs.getString("source")));

    private final Database db;

    public SightingRepository(Database db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- reads

    public Optional<Sighting> findById(String id) {
        return db.queryOne("SELECT " + COLUMNS + " FROM sightings WHERE id = ?", List.of(id), SIGHTING);
    }

    /** Existing sightings for the given ids, keyed by id. */
    public Map<String, Sighting> findByIds(Collection<String> ids) {
        Map<String, Sighting> result = new HashMap<>();
        for (List<String> chunk : chunks(ids)) {
            String sql = "SELECT " + COLUMNS + " FROM sightings WHERE id IN (" + Database.placeholders(chunk.size()) + ")";
            db.query(sql, chunk, SIGHTING).forEach(s -> result.put(s.id(), s));
        }
        return result;
    }

    /** For each content key already stored, the id of the sighting holding it. */
    public Map<String, String> findIdsByContentKeys(Collection<String> contentKeys) {
        Map<String, String> result = new HashMap<>();
        for (List<String> chunk : chunks(contentKeys)) {
            String sql = "SELECT content_key, id FROM sightings WHERE content_key IN ("
                    + Database.placeholders(chunk.size()) + ")";
            db.query(sql, chunk, rs -> Map.entry(rs.getString(1), rs.getString(2)))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
        }
        return result;
    }

    public PageResult<Sighting> search(SightingQuery query, int page, int size, boolean ascending) {
        Where where = Where.of(query);
        long total = db.queryLong("SELECT COUNT(*) FROM sightings" + where.sql(), where.params());
        String direction = ascending ? "ASC" : "DESC";
        List<Object> params = new ArrayList<>(where.params());
        params.add(size);
        params.add((long) page * size);
        List<Sighting> items = db.query(
                "SELECT " + COLUMNS + " FROM sightings" + where.sql()
                        + " ORDER BY sighted_at " + direction + ", id " + direction + " LIMIT ? OFFSET ?",
                params, SIGHTING);
        return PageResult.of(items, page, size, total);
    }

    public long count(SightingQuery query) {
        Where where = Where.of(query);
        return db.queryLong("SELECT COUNT(*) FROM sightings" + where.sql(), where.params());
    }

    /** Counts per city, region or species, largest first. */
    public List<GroupCount> countBy(Dimension dimension, SightingQuery query) {
        Where where = Where.of(query);
        String column = dimension.column();
        String sql = "SELECT " + column + " AS name, COUNT(*) AS n FROM sightings" + where.sql()
                + " GROUP BY " + column + " ORDER BY n DESC, name ASC";
        return db.query(sql, where.params(), rs -> new GroupCount(rs.getString("name"), rs.getLong("n")));
    }

    /** Counts per calendar day, optionally per city, region or species. Trends are built from these. */
    public List<DailyCount> dailyCounts(SightingQuery query, Dimension splitBy) {
        Where where = Where.of(query);
        String series = splitBy == null ? "'" + ALL_SERIES + "'" : splitBy.column();
        String sql = "SELECT substr(sighted_at, 1, 10) AS day, " + series + " AS series, COUNT(*) AS n"
                + " FROM sightings" + where.sql() + " GROUP BY day, series ORDER BY day";
        return db.query(sql, where.params(),
                rs -> new DailyCount(LocalDate.parse(rs.getString("day")), rs.getString("series"), rs.getLong("n")));
    }

    public Optional<LocalDateTime> latestSighting(SightingQuery query) {
        Where where = Where.of(query);
        return db.queryOne("SELECT MAX(sighted_at) FROM sightings" + where.sql(), where.params(),
                        rs -> rs.getString(1))
                .map(SightingRepository::parseTime);
    }

    public DatasetSummary summary() {
        Map<String, Long> bySource = new LinkedHashMap<>();
        db.query("SELECT source, COUNT(*) FROM sightings GROUP BY source ORDER BY source", List.of(),
                rs -> Map.entry(rs.getString(1), rs.getLong(2))).forEach(e -> bySource.put(e.getKey(), e.getValue()));
        return db.queryOne("SELECT COUNT(*), MIN(sighted_at), MAX(sighted_at), COUNT(DISTINCT city),"
                        + " COUNT(DISTINCT region), COUNT(DISTINCT species) FROM sightings", List.of(),
                rs -> new DatasetSummary(rs.getLong(1), parseTime(rs.getString(2)), parseTime(rs.getString(3)),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), bySource)).orElseThrow();
    }

    // --------------------------------------------------------------- writes

    /**
     * Writes one chunk of an ingestion run inside the caller's transaction:
     * new rows are inserted, changed rows updated, and unchanged rows only get
     * their {@code last_seen_at} refreshed.
     */
    public void applyChanges(Connection connection, List<Sighting> inserts, List<Sighting> updates,
                             List<String> unchangedIds, Instant now) throws SQLException {
        String seenAt = now.toString();
        if (!inserts.isEmpty()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sightings (" + COLUMNS + ", content_key, first_seen_at, last_seen_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Sighting s : inserts) {
                    bindSighting(ps, s);
                    ps.setString(8, s.contentKey());
                    ps.setString(9, seenAt);
                    ps.setString(10, seenAt);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        if (!updates.isEmpty()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE sightings SET id = ?, sighted_at = ?, city = ?, region = ?, species = ?, latin_name = ?,"
                            + " source = ?, content_key = ?, last_seen_at = ? WHERE id = ?")) {
                for (Sighting s : updates) {
                    bindSighting(ps, s);
                    ps.setString(8, s.contentKey());
                    ps.setString(9, seenAt);
                    ps.setString(10, s.id());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        if (!unchangedIds.isEmpty()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE sightings SET last_seen_at = ? WHERE id = ?")) {
                for (String id : unchangedIds) {
                    ps.setString(1, seenAt);
                    ps.setString(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    /**
     * Inserts a single sighting (used for public reports) unless the same
     * sighting is already stored.
     *
     * @return empty if inserted, otherwise the id of the existing duplicate
     */
    public Optional<String> insertIfAbsent(Sighting sighting, Instant now) {
        return db.inTransaction(connection -> {
            List<String> existing = Database.query(connection,
                    "SELECT id FROM sightings WHERE content_key = ? OR id = ?",
                    List.of(sighting.contentKey(), sighting.id()), rs -> rs.getString(1));
            if (!existing.isEmpty()) {
                return Optional.of(existing.get(0));
            }
            applyChanges(connection, List.of(sighting), List.of(), List.of(), now);
            return Optional.empty();
        });
    }

    // -------------------------------------------------------------- helpers

    private static void bindSighting(PreparedStatement ps, Sighting s) throws SQLException {
        ps.setString(1, s.id());
        ps.setString(2, Sighting.STORAGE_FORMAT.format(s.date()));
        ps.setString(3, s.city());
        ps.setString(4, s.region());
        ps.setString(5, s.species());
        ps.setString(6, s.latinName());
        ps.setString(7, s.source().name());
    }

    private static LocalDateTime parseTime(String text) {
        return text == null ? null : LocalDateTime.parse(text, Sighting.STORAGE_FORMAT);
    }

    private static List<List<String>> chunks(Collection<String> values) {
        List<String> all = List.copyOf(Set.copyOf(values));
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < all.size(); i += IN_CLAUSE_CHUNK) {
            chunks.add(all.subList(i, Math.min(all.size(), i + IN_CLAUSE_CHUNK)));
        }
        return chunks;
    }

    /** Builds the WHERE clause for a query. Values are always bound as parameters. */
    private record Where(String sql, List<Object> params) {

        static Where of(SightingQuery q) {
            StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
            List<Object> params = new ArrayList<>();
            if (q.from() != null) {
                sql.append(" AND sighted_at >= ?");
                params.add(Sighting.STORAGE_FORMAT.format(q.from()));
            }
            if (q.toExclusive() != null) {
                sql.append(" AND sighted_at < ?");
                params.add(Sighting.STORAGE_FORMAT.format(q.toExclusive()));
            }
            in(sql, params, "city", q.cities());
            in(sql, params, "region", q.regions());
            in(sql, params, "species", q.species());
            return new Where(sql.toString(), params);
        }

        private static void in(StringBuilder sql, List<Object> params, String column, Set<String> values) {
            if (values == null) {
                return;
            }
            if (values.isEmpty()) {
                sql.append(" AND 1 = 0");
                return;
            }
            sql.append(" AND ").append(column).append(" IN (").append(Database.placeholders(values.size())).append(')');
            params.addAll(values);
        }
    }
}
