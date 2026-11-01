package com.ticktracker.api;

import com.ticktracker.ingest.SyncReport;
import com.ticktracker.ingest.SyncService;
import com.ticktracker.ingest.SyncStatus;
import com.ticktracker.support.FakeUpstream;
import com.ticktracker.support.TestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the whole application against a fake Elanco API and a temporary
 * SQLite file, then exercises the HTTP endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    private static final FakeUpstream UPSTREAM = new FakeUpstream();
    private static final Path DB_DIR = tempDir();
    private static boolean loaded;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ticks.upstream.base-url", () -> UPSTREAM.baseUri().toString());
        registry.add("ticks.storage.path", () -> DB_DIR.resolve("api-test.db").toString());
        registry.add("ticks.sync.on-startup", () -> "false");
        registry.add("ticks.sync.interval", () -> "0s");
        registry.add("ticks.upstream.retry.max-attempts", () -> "1");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SyncService syncService;

    @BeforeEach
    void loadFixtureOnce() {
        if (!loaded) {
            UPSTREAM.respondAlways(FakeUpstream.Response.ok(TestSupport.fixture("upstream-dirty.json")));
            SyncReport report = syncService.syncFromUpstream("TEST");
            assertEquals(SyncStatus.SUCCESS, report.status());
            assertEquals(19, report.inserted());
            loaded = true;
        }
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @Test
    void searchIsCaseInsensitivePagedAndStampedWithFreshness() throws Exception {
        mvc.perform(get("/api/v1/sightings").param("city", "LONDON").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string(WebConfig.STALE_HEADER, "false"))
                .andExpect(header().exists(WebConfig.LAST_SYNCED_HEADER))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("0mgV5wo4mQ9i0t8Hpfdw"))
                .andExpect(jsonPath("$.items[0].region").value("London"))
                .andExpect(jsonPath("$.items[0].latitude").value(51.5072));
    }

    @Test
    void filtersByTimeRangeAndRadius() throws Exception {
        mvc.perform(get("/api/v1/sightings").param("from", "2024-08-01").param("to", "2024-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(4));
        mvc.perform(get("/api/v1/sightings").param("lat", "51.546").param("lon", "-0.479").param("radiusKm", "40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3));
    }

    @Test
    void returnsProblemDetailsForBadInput() throws Exception {
        mvc.perform(get("/api/v1/sightings").param("from", "2024-06-01").param("to", "2024-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value(containsString("'from' must be earlier than 'to'")));
        mvc.perform(get("/api/v1/sightings").param("lat", "abc").param("lon", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/sightings/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(get("/api/v1/stats/by-region").param("region", "Narnia"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsSightingsPerRegionAndTrends() throws Exception {
        mvc.perform(get("/api/v1/stats/by-region"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBy").value("region"))
                .andExpect(jsonPath("$.total").value(19));
        mvc.perform(get("/api/v1/stats/trends").param("interval", "year").param("splitBy", "region"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interval").value("YEAR"))
                .andExpect(jsonPath("$.firstPeriod").value("2012"))
                .andExpect(jsonPath("$.lastPeriod").value("2024"))
                .andExpect(jsonPath("$.total").value(19));
        mvc.perform(get("/api/v1/stats/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataset.totalSightings").value(19));
    }

    @Test
    void producesInsights() throws Exception {
        mvc.perform(get("/api/v1/insights").param("forecastMonths", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasonality.interpretation").exists())
                .andExpect(jsonPath("$.forecast.months.length()").value(2))
                .andExpect(jsonPath("$.riskAreas.length()").value(15));
    }

    @Test
    @Order(Integer.MAX_VALUE) // runs last because it adds a sighting that would change other tests' counts
    void acceptsValidReportsAndRejectsInvalidOnes() throws Exception {
        mvc.perform(post("/api/v1/sightings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2025-06-01T14:00:00\",\"city\":\"cardiff\",\"species\":\"Hedgehog tick\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/sightings/usr-")))
                .andExpect(jsonPath("$.city").value("Cardiff"))
                .andExpect(jsonPath("$.latinName").value("Ixodes hexagonus"))
                .andExpect(jsonPath("$.source").value("USER_REPORT"));

        mvc.perform(post("/api/v1/sightings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2025-06-01T14:00:00\",\"city\":\"Cardiff\",\"species\":\"hedgehog tick\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/sightings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2025-06-01T14:00:00\",\"species\":\"Sheep tick\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("city"));

        mvc.perform(post("/api/v1/sightings").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exposesDataQualityAndDryRunImports() throws Exception {
        mvc.perform(get("/api/v1/data-quality"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distinctRejectedRecords").value(10))
                .andExpect(jsonPath("$.rejectedByReason.DUPLICATE_CONTENT").value(1));
        mvc.perform(get("/api/v1/data-quality/rejected").param("reason", "invalid_date"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recordId").value("bad-date-1"));

        mvc.perform(post("/api/v1/sync/import").param("dryRun", "true").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"x1\",\"date\":\"14/08/2024 09:30\",\"location\":\" leeds \",\"species\":\"marsh tick\"},"
                                + "{\"id\":\"x2\",\"location\":\"Leeds\",\"species\":\"Marsh tick\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.inserted").value(1))
                .andExpect(jsonPath("$.rejectedByReason.MISSING_DATE").value(1))
                .andExpect(jsonPath("$.repairs.CITY_NORMALISED").value(1));
        mvc.perform(get("/api/v1/sightings/x1")).andExpect(status().isNotFound());
    }

    @Test
    void exposesSyncStatusHealthAndApiDocs() throws Exception {
        mvc.perform(get("/api/v1/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuitBreaker").value("CLOSED"))
                .andExpect(jsonPath("$.lastRun.status").value("SUCCESS"));
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.upstream.status").value("UP"));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("UK Tick Sightings Tracker API"));
        mvc.perform(get("/")).andExpect(status().isFound());
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("tick-tracker-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
