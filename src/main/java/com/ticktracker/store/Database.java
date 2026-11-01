package com.ticktracker.store;

import com.ticktracker.error.StorageException;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Thin wrapper around a JDBC {@link DataSource}: applies the schema and
 * provides small query and transaction helpers.
 *
 * <p>Plain JDBC rather than an ORM keeps every SQL statement visible, and it
 * suits a small schema whose main job is aggregation queries.
 */
public final class Database {

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final DataSource dataSource;

    public Database(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Applies {@code schema.sql}. Every statement is idempotent, so this is safe on each start-up. */
    public void migrate() {
        List<String> statements = splitStatements(readClasspath("/schema.sql"));
        inTransaction(connection -> {
            try (Statement st = connection.createStatement()) {
                for (String sql : statements) {
                    st.execute(sql);
                }
            }
            return null;
        });
    }

    /** Runs the work in one transaction: commits on success, rolls back on any exception. */
    public <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new StorageException("Database operation failed", e);
        }
    }

    public <T> T withConnection(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new StorageException("Database operation failed", e);
        }
    }

    public <T> List<T> query(String sql, List<?> params, RowMapper<T> mapper) {
        return withConnection(connection -> query(connection, sql, params, mapper));
    }

    public static <T> List<T> query(Connection connection, String sql, List<?> params, RowMapper<T> mapper)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        }
    }

    public <T> Optional<T> queryOne(String sql, List<?> params, RowMapper<T> mapper) {
        List<T> rows = query(sql, params, mapper);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public long queryLong(String sql, List<?> params) {
        return queryOne(sql, params, rs -> rs.getLong(1)).orElse(0L);
    }

    public int update(String sql, List<?> params) {
        return withConnection(connection -> update(connection, sql, params));
    }

    public static int update(Connection connection, String sql, List<?> params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    public static void bind(PreparedStatement ps, List<?> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /** "?, ?, ?" for an IN clause. */
    public static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    /** Splits a SQL script on semicolons after removing "--" comments. */
    static List<String> splitStatements(String script) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : script.split("\\R")) {
            int comment = line.indexOf("--");
            withoutComments.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return Arrays.stream(withoutComments.toString().split(";"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String readClasspath(String path) {
        try (InputStream in = Database.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
