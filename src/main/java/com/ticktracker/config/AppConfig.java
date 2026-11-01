package com.ticktracker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticktracker.analytics.InsightsService;
import com.ticktracker.analytics.TrendService;
import com.ticktracker.domain.Catalogue;
import com.ticktracker.ingest.CircuitBreaker;
import com.ticktracker.ingest.ElancoApiClient;
import com.ticktracker.ingest.JsonArrayReader;
import com.ticktracker.ingest.RejectedRecordRepository;
import com.ticktracker.ingest.RetryPolicy;
import com.ticktracker.ingest.SightingCleaner;
import com.ticktracker.ingest.SyncRunRepository;
import com.ticktracker.ingest.SyncScheduler;
import com.ticktracker.ingest.SyncService;
import com.ticktracker.query.QueryParser;
import com.ticktracker.reports.ReportService;
import com.ticktracker.store.Database;
import com.ticktracker.store.SightingRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;

/**
 * Wires the application together. Everything except the web layer is plain
 * Java with no Spring annotations, so the core logic can be unit tested
 * without starting Spring, and the framework stays at the edges.
 */
@Configuration
@EnableConfigurationProperties(TicksProperties.class)
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.system(SightingCleaner.UK);
    }

    @Bean
    public Catalogue catalogue() {
        return Catalogue.ukDefault();
    }

    /**
     * SQLite tuned for a small web service: WAL lets readers proceed while a
     * sync writes, and busy_timeout makes a writer wait briefly for the lock
     * instead of failing with SQLITE_BUSY.
     */
    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(TicksProperties properties) {
        Path file = Path.of(properties.storage().path()).toAbsolutePath();
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create database folder " + file.getParent(), e);
        }
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqlite.setBusyTimeout(5_000);

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("sqlite");
        hikari.setJdbcUrl("jdbc:sqlite:" + file);
        hikari.setDataSourceProperties(sqlite.toProperties());
        hikari.setMaximumPoolSize(4);
        return new HikariDataSource(hikari);
    }

    @Bean
    public Database database(DataSource dataSource) {
        Database database = new Database(dataSource);
        database.migrate();
        return database;
    }

    @Bean
    public SightingRepository sightingRepository(Database database) {
        return new SightingRepository(database);
    }

    @Bean
    public RejectedRecordRepository rejectedRecordRepository(Database database) {
        return new RejectedRecordRepository(database);
    }

    @Bean
    public SyncRunRepository syncRunRepository(Database database, ObjectMapper objectMapper) {
        return new SyncRunRepository(database, objectMapper);
    }

    @Bean
    public CircuitBreaker circuitBreaker(TicksProperties properties, Clock clock) {
        TicksProperties.CircuitBreaker cb = properties.upstream().circuitBreaker();
        return new CircuitBreaker(cb.failureThreshold(), cb.openDuration(), clock);
    }

    @Bean
    public JsonArrayReader jsonArrayReader(ObjectMapper objectMapper) {
        return new JsonArrayReader(objectMapper);
    }

    @Bean
    public ElancoApiClient elancoApiClient(TicksProperties properties, CircuitBreaker circuitBreaker,
                                           JsonArrayReader reader, Clock clock) {
        TicksProperties.Upstream upstream = properties.upstream();
        TicksProperties.Retry retry = upstream.retry();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(upstream.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        RetryPolicy retryPolicy = new RetryPolicy(retry.maxAttempts(), retry.initialDelay(), retry.maxDelay(),
                retry.jitter(), new SecureRandom());
        return new ElancoApiClient(http, upstream.baseUrl(), upstream.requestTimeout(), retryPolicy, circuitBreaker,
                reader, ElancoApiClient.Sleeper.real(), clock);
    }

    @Bean
    public SightingCleaner sightingCleaner(Catalogue catalogue, Clock clock) {
        return new SightingCleaner(catalogue, clock);
    }

    @Bean
    public SyncService syncService(Database database, SightingRepository sightings, RejectedRecordRepository rejected,
                                   SyncRunRepository runs, SightingCleaner cleaner, ElancoApiClient client,
                                   JsonArrayReader reader, Clock clock, TicksProperties properties) {
        TicksProperties.Sync sync = properties.sync();
        return new SyncService(database, sightings, rejected, runs, cleaner, client, reader, clock,
                sync.chunkSize(), sync.staleAfter());
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SyncScheduler syncScheduler(SyncService syncService, TicksProperties properties) {
        return new SyncScheduler(syncService, properties.sync().onStartup(), properties.sync().interval());
    }

    @Bean
    public QueryParser queryParser(Catalogue catalogue) {
        return new QueryParser(catalogue);
    }

    @Bean
    public TrendService trendService(SightingRepository sightings) {
        return new TrendService(sightings);
    }

    @Bean
    public InsightsService insightsService(SightingRepository sightings, TrendService trends, Catalogue catalogue) {
        return new InsightsService(sightings, trends, catalogue);
    }

    @Bean
    public ReportService reportService(Catalogue catalogue, SightingRepository sightings, Clock clock) {
        return new ReportService(catalogue, sightings, clock);
    }
}
