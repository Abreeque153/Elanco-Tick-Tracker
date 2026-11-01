package com.ticktracker.store;

import com.ticktracker.domain.Sighting;
import com.ticktracker.domain.SightingSource;
import com.ticktracker.query.QueryParser;
import com.ticktracker.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SightingRepositoryTest {

    @TempDir
    Path tempDir;

    private Database db;
    private SightingRepository repository;
    private final QueryParser parser = new QueryParser(TestSupport.CATALOGUE);

    @BeforeEach
    void setUp() {
        db = TestSupport.database(tempDir);
        repository = new SightingRepository(db);
        TestSupport.store(db, List.of(
                TestSupport.sighting("a", "2024-01-15T10:00:00", "London", "Marsh tick"),
                TestSupport.sighting("b", "2024-01-31T23:59:59", "London", "Sheep tick"),
                TestSupport.sighting("c", "2024-02-01T00:00:00", "Leeds", "Marsh tick"),
                TestSupport.sighting("d", "2024-03-10T08:00:00", "Glasgow", "Tree-hole tick"),
                TestSupport.sighting("e", "2024-03-11T08:00:00", "Edinburgh", "Tree-hole tick")));
    }

    private SightingQuery query(String from, String to, String city, String region, String species) {
        return parser.parse(from, to, city == null ? null : List.of(city), region == null ? null : List.of(region),
                species == null ? null : List.of(species), null, null, null);
    }

    @Test
    void filtersByInclusiveDateRange() {
        PageResult<Sighting> january = repository.search(query("2024-01-01", "2024-01-31", null, null, null), 0, 50, true);
        assertEquals(List.of("a", "b"), january.items().stream().map(Sighting::id).toList());
    }

    @Test
    void filtersByCityRegionAndSpeciesCaseInsensitively() {
        assertEquals(2, repository.count(query(null, null, "LONDON", null, null)));
        assertEquals(2, repository.count(query(null, null, null, "scotland", null)));
        assertEquals(2, repository.count(query(null, null, null, null, "marsh tick")));
        assertEquals(1, repository.count(query(null, null, "london", null, "Ixodes apronophorus")));
    }

    @Test
    void pagesAndSortsNewestFirstByDefault() {
        PageResult<Sighting> page0 = repository.search(SightingQuery.all(), 0, 2, false);
        PageResult<Sighting> page2 = repository.search(SightingQuery.all(), 2, 2, false);
        assertEquals(List.of("e", "d"), page0.items().stream().map(Sighting::id).toList());
        assertEquals(List.of("a"), page2.items().stream().map(Sighting::id).toList());
        assertEquals(5, page0.totalItems());
        assertEquals(3, page0.totalPages());
    }

    @Test
    void anEmptyCitySetMatchesNothing() {
        SightingQuery nowhere = new SightingQuery(null, null, Set.of(), null, null);
        assertEquals(0, repository.count(nowhere));
    }

    @Test
    void countsPerDimensionLargestFirst() {
        List<SightingRepository.GroupCount> byRegion = repository.countBy(Dimension.REGION, SightingQuery.all());
        assertEquals("London", byRegion.get(0).name());
        assertEquals(2, byRegion.get(0).count());
        assertEquals(2, byRegion.get(1).count());
        assertEquals("Scotland", byRegion.get(1).name());
    }

    @Test
    void dailyCountsCanBeSplitByADimension() {
        List<SightingRepository.DailyCount> daily = repository.dailyCounts(SightingQuery.all(), Dimension.SPECIES);
        assertEquals(5, daily.size());
        assertEquals(LocalDate.of(2024, 1, 15), daily.get(0).day());
        assertEquals("Marsh tick", daily.get(0).series());
    }

    @Test
    void summaryDescribesTheDataset() {
        SightingRepository.DatasetSummary summary = repository.summary();
        assertEquals(5, summary.totalSightings());
        assertEquals(LocalDateTime.of(2024, 1, 15, 10, 0), summary.firstSighting());
        assertEquals(4, summary.cities());
        assertEquals(3, summary.regions());
        assertEquals(5L, summary.bySource().get("ELANCO_API"));
    }

    @Test
    void insertIfAbsentRefusesTheSameSightingTwice() {
        Sighting report = new Sighting("usr-1", LocalDateTime.of(2024, 1, 15, 10, 0), "London", "London",
                "Marsh tick", "Ixodes apronophorus", SightingSource.USER_REPORT);
        assertEquals("a", repository.insertIfAbsent(report, TestSupport.NOW).orElseThrow());

        Sighting fresh = new Sighting("usr-2", LocalDateTime.of(2024, 6, 1, 12, 0), "Leeds", "Yorkshire and the Humber",
                "Sheep tick", "Ixodes ricinus", SightingSource.USER_REPORT);
        assertTrue(repository.insertIfAbsent(fresh, TestSupport.NOW).isEmpty());
        assertEquals(SightingSource.USER_REPORT, repository.findById("usr-2").orElseThrow().source());
    }

    @Test
    void schemaScriptsSplitCorrectlyAroundComments() {
        List<String> statements = Database.splitStatements("""
                -- a comment; with a semicolon
                CREATE TABLE t (a TEXT); -- trailing
                CREATE INDEX i ON t (a);
                """);
        assertEquals(List.of("CREATE TABLE t (a TEXT)", "CREATE INDEX i ON t (a)"), statements);
    }
}
