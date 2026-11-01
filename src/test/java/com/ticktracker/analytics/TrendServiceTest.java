package com.ticktracker.analytics;

import com.ticktracker.error.InvalidRequestException;
import com.ticktracker.query.QueryParser;
import com.ticktracker.store.Database;
import com.ticktracker.store.Dimension;
import com.ticktracker.store.SightingQuery;
import com.ticktracker.store.SightingRepository;
import com.ticktracker.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendServiceTest {

    @TempDir
    Path tempDir;

    private TrendService trends;
    private final QueryParser parser = new QueryParser(TestSupport.CATALOGUE);

    @BeforeEach
    void setUp() {
        Database db = TestSupport.database(tempDir);
        TestSupport.store(db, List.of(
                TestSupport.sighting("a", "2024-01-03T10:00:00", "London", "Marsh tick"),
                TestSupport.sighting("b", "2024-01-20T10:00:00", "Leeds", "Marsh tick"),
                TestSupport.sighting("c", "2024-04-02T10:00:00", "London", "Sheep tick"),
                TestSupport.sighting("d", "2024-12-30T10:00:00", "London", "Marsh tick")));
        trends = new TrendService(new SightingRepository(db));
    }

    @Test
    void monthlyTrendFillsEmptyMonthsWithZero() {
        TrendService.Trend trend = trends.trend(SightingQuery.all(), TrendService.Interval.MONTH, null);
        List<TrendService.Point> points = trend.series().get(0).points();
        assertEquals(12, points.size(), "Jan to Dec 2024");
        assertEquals("2024-01", points.get(0).period());
        assertEquals(2, points.get(0).count());
        assertEquals(0, points.get(1).count(), "February has no sightings but still appears");
        assertEquals(1, points.get(3).count());
        assertEquals(4, trend.total());
    }

    @Test
    void weeklyTrendUsesIsoWeeks() {
        TrendService.Trend trend = trends.trend(SightingQuery.all(), TrendService.Interval.WEEK, null);
        List<TrendService.Point> points = trend.series().get(0).points();
        assertEquals("2024-W01", points.get(0).period());
        assertEquals(LocalDate.of(2024, 1, 1), points.get(0).start(), "ISO weeks start on Monday");
        TrendService.Point last = points.get(points.size() - 1);
        assertEquals("2025-W01", last.period(), "30 Dec 2024 is in ISO week 1 of 2025");
        assertEquals(1, last.count());
    }

    @Test
    void requestedRangeIsZeroFilledEvenBeyondTheData() {
        SightingQuery q = parser.parse("2023-11-01", "2024-02-29", null, null, null, null, null, null);
        TrendService.Trend trend = trends.trend(q, TrendService.Interval.MONTH, null);
        assertEquals(List.of("2023-11", "2023-12", "2024-01", "2024-02"),
                trend.series().get(0).points().stream().map(TrendService.Point::period).toList());
        assertEquals(2, trend.total());
    }

    @Test
    void canSplitIntoOneSeriesPerDimension() {
        TrendService.Trend trend = trends.trend(SightingQuery.all(), TrendService.Interval.YEAR, Dimension.SPECIES);
        assertEquals("species", trend.splitBy());
        assertEquals(2, trend.series().size());
        assertEquals("Marsh tick", trend.series().get(0).name(), "largest series first");
        assertEquals(3, trend.series().get(0).total());
    }

    @Test
    void refusesAbsurdlyLongWeeklyRanges() {
        SightingQuery q = parser.parse("1900-01-01", "2024-12-31", null, null, null, null, null, null);
        assertThrows(InvalidRequestException.class, () -> trends.trend(q, TrendService.Interval.WEEK, null));
    }

    @Test
    void parsesIntervalNamesLeniently() {
        assertEquals(TrendService.Interval.WEEK, TrendService.Interval.parse("Weekly"));
        assertEquals(TrendService.Interval.MONTH, TrendService.Interval.parse(null));
        assertThrows(InvalidRequestException.class, () -> TrendService.Interval.parse("fortnight"));
    }

    @Test
    void emptyResultHasNoSeriesForSplits() {
        SightingQuery nowhere = parser.parse(null, null, List.of("Cardiff"), null, null, null, null, null);
        assertTrue(trends.trend(nowhere, TrendService.Interval.MONTH, Dimension.CITY).series().isEmpty());
    }
}
