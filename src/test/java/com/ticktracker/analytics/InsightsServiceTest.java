package com.ticktracker.analytics;

import com.ticktracker.domain.Sighting;
import com.ticktracker.error.InvalidRequestException;
import com.ticktracker.store.Database;
import com.ticktracker.store.SightingQuery;
import com.ticktracker.store.SightingRepository;
import com.ticktracker.support.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsServiceTest {

    @TempDir
    Path tempDir;

    private static final String[] CITIES = {"London", "Leeds", "Glasgow", "Cardiff", "Bristol"};

    private InsightsService service(List<Sighting> data) {
        Database db = TestSupport.database(tempDir);
        TestSupport.store(db, data);
        SightingRepository repository = new SightingRepository(db);
        return new InsightsService(repository, new TrendService(repository), TestSupport.CATALOGUE);
    }

    /** Six years of sightings with the given relative weight per calendar month. */
    private static List<Sighting> generate(int[] weightsByMonth, long seed) {
        Random random = new Random(seed);
        List<Sighting> data = new ArrayList<>();
        int n = 0;
        for (int year = 2019; year <= 2024; year++) {
            for (int month = 1; month <= 12; month++) {
                for (int i = 0; i < weightsByMonth[month - 1]; i++) {
                    int day = 1 + random.nextInt(28);
                    String city = CITIES[random.nextInt(CITIES.length)];
                    String time = String.format("%04d-%02d-%02dT%02d:%02d:%02d", year, month, day,
                            random.nextInt(24), random.nextInt(60), random.nextInt(60));
                    data.add(TestSupport.sighting("s" + (n++), time, city, "Sheep tick"));
                }
            }
        }
        return data;
    }

    @Test
    void detectsGenuineSeasonality() {
        int[] summerPeak = {2, 2, 4, 8, 14, 18, 20, 16, 10, 5, 3, 2};
        InsightsService.Insights insights = service(generate(summerPeak, 1))
                .insights(SightingQuery.all(), null, 6, "test");

        assertTrue(insights.seasonality().significant(), insights.seasonality().interpretation());
        assertTrue(insights.seasonality().pValue() < 0.001);
        assertTrue(insights.seasonality().peakMonths().contains("July"), insights.seasonality().peakMonths().toString());
        assertTrue(insights.forecast().method().contains("seasonal index"));

        // Forecast for July 2025 should be well above January 2025
        InsightsService.ForecastPoint january = insights.forecast().months().get(0);
        InsightsService.ForecastPoint june = insights.forecast().months().get(5);
        assertEquals("2025-01", january.month());
        assertEquals("2025-06", june.month());
        assertTrue(june.expected() > 4 * january.expected(), january + " vs " + june);
        assertTrue(june.low() <= june.expected() && june.expected() <= june.high());
    }

    @Test
    void doesNotInventSeasonalityInUniformData() {
        int[] flat = {8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8};
        InsightsService.Insights insights = service(generate(flat, 2)).insights(SightingQuery.all(), null, 3, "test");

        assertFalse(insights.seasonality().significant(), insights.seasonality().interpretation());
        assertFalse(insights.trend().significant(), insights.trend().interpretation());
        assertEquals("STABLE", insights.trend().direction());
        assertEquals(3, insights.forecast().months().size());
        assertTrue(insights.forecast().method().contains("not significant"));
    }

    @Test
    void anchorsToTheLatestSightingAndRanksRiskAreas() {
        InsightsService.Insights insights = service(generate(new int[]{5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5}, 3))
                .insights(SightingQuery.all(), null, 1, "test");

        assertTrue(insights.asOf().getYear() == 2024 && insights.asOf().getMonthValue() == 12);
        assertNotNull(insights.note());
        assertEquals(72, insights.monthsAnalysed());
        assertEquals(TestSupport.CATALOGUE.cities().size(), insights.riskAreas().size(), "every city is ranked");
        long last12 = insights.riskAreas().stream().mapToLong(InsightsService.RiskArea::last12Months).sum();
        assertTrue(last12 >= 60 && last12 <= 65, "about 12 months x 5 sightings, got " + last12);
        assertTrue(insights.riskAreas().get(0).last12Months() >= insights.riskAreas().get(1).last12Months());
        assertEquals("LOW", insights.riskAreas().get(insights.riskAreas().size() - 1).riskLevel(),
                "cities with no sightings are low risk");
        assertTrue(insights.anomalies().testsRun() > 0);
    }

    @Test
    void flagsAGenuineSpike() {
        List<Sighting> data = new ArrayList<>(generate(new int[]{3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3}, 4));
        for (int i = 0; i < 25; i++) {
            data.add(TestSupport.sighting("spike" + i, String.format("2022-07-%02dT12:00:00", 1 + i), "Leeds", "Marsh tick"));
        }
        InsightsService.Insights insights = service(data).insights(SightingQuery.all(), null, 1, "test");

        InsightsService.Anomaly top = insights.anomalies().mostUnusual().get(0);
        assertEquals("Leeds", top.city());
        assertEquals("2022-07", top.month());
        assertTrue(top.flagged());
        assertTrue(insights.anomalies().flaggedCount() >= 1);
    }

    @Test
    void handlesAnEmptyScopeAndValidatesInput() {
        InsightsService service = service(List.of());
        InsightsService.Insights empty = service.insights(SightingQuery.all(), LocalDate.of(2024, 1, 1), 3, "test");
        assertEquals(0, empty.sightingsAnalysed());
        assertThrows(InvalidRequestException.class, () -> service.insights(SightingQuery.all(), null, 0, "test"));
    }
}
