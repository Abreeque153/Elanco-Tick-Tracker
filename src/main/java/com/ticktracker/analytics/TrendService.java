package com.ticktracker.analytics;

import com.ticktracker.error.InvalidRequestException;
import com.ticktracker.store.Dimension;
import com.ticktracker.store.SightingQuery;
import com.ticktracker.store.SightingRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds weekly, monthly or yearly time series.
 *
 * <p>SQL returns counts per day. Bucketing happens in Java so that weeks are
 * proper ISO weeks (Monday start; 30 Dec 2024 belongs to 2025-W01), and so
 * empty periods are filled with zero. Without zero-filling a chart would draw
 * a straight line over months with no sightings, which misleads.
 */
public final class TrendService {

    public enum Interval {
        WEEK, MONTH, YEAR;

        public static Interval parse(String value) {
            if (value == null || value.isBlank()) {
                return MONTH;
            }
            String v = value.strip().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "week", "weekly" -> WEEK;
                case "month", "monthly" -> MONTH;
                case "year", "yearly", "annual" -> YEAR;
                default -> throw new InvalidRequestException("'interval' must be week, month or year (got '" + value + "')");
            };
        }
    }

    public record Point(String period, LocalDate start, long count) {
    }

    public record Series(String name, long total, List<Point> points) {
    }

    public record Trend(Interval interval, String splitBy, String firstPeriod, String lastPeriod, long total,
                        List<Series> series) {
    }

    /** Guards against e.g. weekly buckets over a 200-year range. */
    public static final int MAX_POINTS = 2_000;

    private final SightingRepository repository;

    public TrendService(SightingRepository repository) {
        this.repository = repository;
    }

    public Trend trend(SightingQuery query, Interval interval, Dimension splitBy) {
        List<SightingRepository.DailyCount> daily = repository.dailyCounts(query, splitBy);
        String splitName = splitBy == null ? null : splitBy.column();

        LocalDate first = query.from() != null ? query.from().toLocalDate()
                : daily.isEmpty() ? null : daily.get(0).day();
        LocalDate last = query.toExclusive() != null ? query.toExclusive().minusSeconds(1).toLocalDate()
                : daily.isEmpty() ? null : daily.get(daily.size() - 1).day();
        if (first == null || last == null || last.isBefore(first)) {
            return new Trend(interval, splitName, null, null, 0, List.of());
        }

        LocalDate start = bucketStart(first, interval);
        LocalDate end = bucketStart(last, interval);
        long points = countBuckets(start, end, interval);
        if (points > MAX_POINTS) {
            throw new InvalidRequestException("That range has " + points + " " + interval.name().toLowerCase(Locale.ROOT)
                    + " periods (max " + MAX_POINTS + "). Narrow 'from'/'to' or use a longer interval.");
        }

        Map<String, Map<LocalDate, Long>> bySeries = new TreeMap<>();
        for (SightingRepository.DailyCount day : daily) {
            bySeries.computeIfAbsent(day.series(), k -> new HashMap<>())
                    .merge(bucketStart(day.day(), interval), day.count(), Long::sum);
        }
        if (bySeries.isEmpty() && splitBy == null) {
            bySeries.put(SightingRepository.ALL_SERIES, Map.of());
        }

        List<Series> series = new ArrayList<>();
        long grandTotal = 0;
        for (Map.Entry<String, Map<LocalDate, Long>> entry : bySeries.entrySet()) {
            List<Point> pointList = new ArrayList<>();
            long total = 0;
            for (LocalDate bucket = start; !bucket.isAfter(end); bucket = next(bucket, interval)) {
                long count = entry.getValue().getOrDefault(bucket, 0L);
                pointList.add(new Point(label(bucket, interval), bucket, count));
                total += count;
            }
            series.add(new Series(entry.getKey(), total, pointList));
            grandTotal += total;
        }
        series.sort(Comparator.comparingLong(Series::total).reversed().thenComparing(Series::name));
        return new Trend(interval, splitName, label(start, interval), label(end, interval), grandTotal, series);
    }

    public static LocalDate bucketStart(LocalDate day, Interval interval) {
        return switch (interval) {
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
            case YEAR -> day.withDayOfYear(1);
        };
    }

    public static String label(LocalDate bucketStart, Interval interval) {
        return switch (interval) {
            case WEEK -> String.format(Locale.ROOT, "%d-W%02d",
                    bucketStart.get(IsoFields.WEEK_BASED_YEAR), bucketStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case MONTH -> YearMonth.from(bucketStart).toString();
            case YEAR -> String.valueOf(bucketStart.getYear());
        };
    }

    private static LocalDate next(LocalDate bucket, Interval interval) {
        return switch (interval) {
            case WEEK -> bucket.plusWeeks(1);
            case MONTH -> bucket.plusMonths(1);
            case YEAR -> bucket.plusYears(1);
        };
    }

    private static long countBuckets(LocalDate start, LocalDate end, Interval interval) {
        return switch (interval) {
            case WEEK -> java.time.temporal.ChronoUnit.WEEKS.between(start, end) + 1;
            case MONTH -> java.time.temporal.ChronoUnit.MONTHS.between(start, end) + 1;
            case YEAR -> java.time.temporal.ChronoUnit.YEARS.between(start, end) + 1;
        };
    }
}
