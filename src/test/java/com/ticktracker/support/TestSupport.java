package com.ticktracker.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticktracker.domain.Catalogue;
import com.ticktracker.domain.Sighting;
import com.ticktracker.domain.SightingSource;
import com.ticktracker.ingest.CircuitBreaker;
import com.ticktracker.ingest.ElancoApiClient;
import com.ticktracker.ingest.JsonArrayReader;
import com.ticktracker.ingest.RetryPolicy;
import com.ticktracker.store.Database;
import com.ticktracker.store.SightingRepository;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Shared builders for tests. */
public final class TestSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final Catalogue CATALOGUE = Catalogue.ukDefault();
    /** 1 Nov 2026, 12:00 UK time. */
    public static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private TestSupport() {
    }

    public static MutableClock clock() {
        return new MutableClock(NOW, ZoneId.of("Europe/London"));
    }

    /** A fresh, migrated SQLite database in a temp folder. */
    public static Database database(Path folder) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("test.db"));
        Database db = new Database(dataSource);
        db.migrate();
        return db;
    }

    public static ElancoApiClient client(FakeUpstream upstream, int maxAttempts, CircuitBreaker breaker,
                                         List<Duration> sleeps, Clock clock) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        RetryPolicy retry = new RetryPolicy(maxAttempts, Duration.ofSeconds(1), Duration.ofSeconds(60), 0, new Random(1));
        return new ElancoApiClient(http, upstream.baseUri(), Duration.ofSeconds(2), retry, breaker,
                new JsonArrayReader(MAPPER), sleeps::add, clock);
    }

    public static String fixture(String name) {
        try (InputStream in = TestSupport.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Sighting sighting(String id, String isoDate, String city, String species) {
        Catalogue.City c = CATALOGUE.findCity(city).orElseThrow();
        Catalogue.Species s = CATALOGUE.findSpecies(species).orElseThrow();
        return new Sighting(id, LocalDateTime.parse(isoDate), c.name(), c.region(), s.name(), s.latinName(),
                SightingSource.ELANCO_API);
    }

    /** Stores sightings directly, bypassing the pipeline. */
    public static void store(Database db, List<Sighting> sightings) {
        SightingRepository repository = new SightingRepository(db);
        db.inTransaction(connection -> {
            repository.applyChanges(connection, new ArrayList<>(sightings), List.of(), List.of(), NOW);
            return null;
        });
    }
}
