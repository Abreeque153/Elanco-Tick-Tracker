package com.ticktracker.analytics;

import com.ticktracker.domain.Catalogue;
import com.ticktracker.error.InvalidRequestException;
import com.ticktracker.store.Dimension;
import com.ticktracker.store.SightingQuery;
import com.ticktracker.store.SightingRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * The "AI/ML insights" extension, built on explainable statistics rather than
 * a black-box model. Each insight is tested for significance before it is
 * stated, because a tracker that cries wolf undermines public-health
 * messaging.
 *
 * <ol>
 *   <li><b>Seasonality:</b> are some months genuinely busier? Chi-square test
 *       against a uniform rate, weighted by days per month.</li>
 *   <li><b>Long-term trend:</b> are sightings rising? Least-squares slope with
 *       a t-test.</li>
 *   <li><b>Risk areas:</b> which cities had the most activity in the last 12
 *       months compared with the national average per city, and how that
 *       changed on the previous 12 months.</li>
 *   <li><b>Anomalies:</b> unusual city-month spikes, using Poisson tail
 *       probabilities with a Bonferroni correction for the many comparisons.</li>
 *   <li><b>Forecast:</b> expected sightings for the next months, with 95%
 *       intervals. Trend and seasonality are used only if they were
 *       significant.</li>
 * </ol>
 */
public final class InsightsService {

    public record Seasonality(
            Map<String, Long> sightingsByMonth,
            Map<String, Double> seasonalIndex,
            List<String> peakMonths,
            List<String> quietestMonths,
            double chiSquare,
            int degreesOfFreedom,
            double pValue,
            boolean significant,
            String interpretation) {
    }

    public record LongTermTrend(
            Map<String, Long> sightingsByYear,
            double changeInMonthlySightingsPerYear,
            double percentChangePerYear,
            double rSquared,
            double tStatistic,
            boolean significant,
            String direction,
            String interpretation) {
    }

    public record RiskArea(String city, String region, long last12Months, long previous12Months,
                           Double changePercent, double relativeRisk, String riskLevel) {
    }

    public record Anomaly(String city, String month, long sightings, double expected, double pValue, boolean flagged) {
    }

    public record AnomalyScan(int testsRun, double significanceThreshold, long flaggedCount,
                              List<Anomaly> mostUnusual, String interpretation) {
    }

    public record ForecastPoint(String month, double expected, double low, double high) {
    }

    public record Forecast(String method, List<ForecastPoint> months) {
    }

    public record Insights(
            LocalDate asOf,
            String scope,
            long sightingsAnalysed,
            int monthsAnalysed,
            String note,
            Seasonality seasonality,
            LongTermTrend trend,
            List<RiskArea> riskAreas,
            AnomalyScan anomalies,
            Forecast forecast) {
    }

    private static final double ALPHA = 0.05;
    private static final double HIGH_RISK = 1.5;
    private static final double LOW_RISK = 0.5;

    private final SightingRepository repository;
    private final TrendService trends;
    private final Catalogue catalogue;

    public InsightsService(SightingRepository repository, TrendService trends, Catalogue catalogue) {
        this.repository = repository;
        this.trends = trends;
        this.catalogue = catalogue;
    }

    /**
     * @param scope          region, city and species filters (time bounds are ignored)
     * @param requestedAsOf  the date to analyse up to; defaults to the latest sighting
     * @param forecastMonths how many months ahead to forecast (1-24)
     */
    public Insights insights(SightingQuery scope, LocalDate requestedAsOf, int forecastMonths, String scopeLabel) {
        if (forecastMonths < 1 || forecastMonths > 24) {
            throw new InvalidRequestException("'forecastMonths' must be between 1 and 24");
        }
        SightingQuery base = scope.withTimeRange(null, null);
        Optional<LocalDateTime> latest = repository.latestSighting(base);
        if (latest.isEmpty()) {
            return empty(requestedAsOf, scopeLabel, "No sightings match this scope yet.");
        }
        LocalDate asOf = requestedAsOf != null ? requestedAsOf : latest.get().toLocalDate();
        String note = requestedAsOf != null ? null
                : "Anchored to the most recent sighting (" + asOf + ") rather than today, because the dataset ends"
                + " there. Pass asOf=YYYY-MM-DD to change this.";
        SightingQuery upToAsOf = base.withTimeRange(null, asOf.plusDays(1).atStartOfDay());

        TrendService.Trend monthly = trends.trend(upToAsOf, TrendService.Interval.MONTH, null);
        if (monthly.series().isEmpty() || monthly.total() == 0) {
            return empty(asOf, scopeLabel, "No sightings on or before " + asOf + ".");
        }
        List<TrendService.Point> points = monthly.series().get(0).points();
        List<YearMonth> months = points.stream().map(p -> YearMonth.parse(p.period())).toList();
        double[] counts = points.stream().mapToDouble(TrendService.Point::count).toArray();

        double[] seasonalIndex = new double[12];
        Seasonality seasonality = seasonality(months, counts, seasonalIndex);
        Stats.Regression regression = Stats.linearRegression(counts);
        LongTermTrend trend = longTermTrend(months, counts, regression);

        return new Insights(
                asOf,
                scopeLabel,
                monthly.total(),
                months.size(),
                note,
                seasonality,
                trend,
                riskAreas(scope, asOf),
                anomalies(upToAsOf),
                forecast(months, counts, seasonality, seasonalIndex, trend, regression, forecastMonths));
    }

    // ---------------------------------------------------------- seasonality

    private Seasonality seasonality(List<YearMonth> months, double[] counts, double[] indexOut) {
        double[] observed = new double[12];
        double[] exposureDays = new double[12];
        for (int i = 0; i < months.size(); i++) {
            int m = months.get(i).getMonthValue() - 1;
            observed[m] += counts[i];
            exposureDays[m] += months.get(i).lengthOfMonth();
        }
        double total = java.util.Arrays.stream(observed).sum();
        double totalExposure = java.util.Arrays.stream(exposureDays).sum();

        double chiSquare = 0;
        int categories = 0;
        double minExpected = Double.POSITIVE_INFINITY;
        Map<String, Long> byMonth = new LinkedHashMap<>();
        Map<String, Double> index = new LinkedHashMap<>();
        List<Integer> covered = new ArrayList<>();
        for (int m = 0; m < 12; m++) {
            if (exposureDays[m] == 0) {
                indexOut[m] = 1.0;
                continue;
            }
            double expected = total * exposureDays[m] / totalExposure;
            chiSquare += expected == 0 ? 0 : Math.pow(observed[m] - expected, 2) / expected;
            categories++;
            minExpected = Math.min(minExpected, expected);
            indexOut[m] = expected == 0 ? 1.0 : observed[m] / expected;
            byMonth.put(monthName(m), Math.round(observed[m]));
            index.put(monthName(m), round(indexOut[m], 2));
            covered.add(m);
        }
        int df = categories - 1;
        double pValue = df >= 1 ? Stats.chiSquareSurvival(chiSquare, df) : 1.0;
        boolean enoughData = df >= 1 && minExpected >= 5;
        boolean significant = enoughData && pValue < ALPHA;

        List<String> peak = covered.stream().sorted(Comparator.comparingDouble((Integer m) -> indexOut[m]).reversed())
                .limit(3).map(InsightsService::monthName).toList();
        List<String> quiet = covered.stream().sorted(Comparator.comparingDouble((Integer m) -> indexOut[m]))
                .limit(3).map(InsightsService::monthName).toList();

        String interpretation;
        if (!enoughData) {
            interpretation = "Too few sightings to test for seasonality reliably (each month needs at least 5"
                    + " expected sightings).";
        } else if (significant) {
            interpretation = String.format(Locale.UK,
                    "Sightings are seasonal (chi-square p = %.2g): activity is highest in %s and lowest in %s.",
                    pValue, String.join(", ", peak), String.join(", ", quiet));
        } else {
            interpretation = String.format(Locale.UK,
                    "No statistically significant seasonal pattern (chi-square p = %.2f). Month-to-month"
                            + " differences are within what random variation produces, so no month stands out as a"
                            + " peak season in this data.", pValue);
        }
        return new Seasonality(byMonth, index, peak, quiet, round(chiSquare, 2), Math.max(df, 0), roundSig(pValue),
                significant, interpretation);
    }

    // ---------------------------------------------------------------- trend

    private LongTermTrend longTermTrend(List<YearMonth> months, double[] counts, Stats.Regression regression) {
        Map<String, Long> byYear = new TreeMap<>();
        for (int i = 0; i < months.size(); i++) {
            byYear.merge(String.valueOf(months.get(i).getYear()), Math.round(counts[i]), Long::sum);
        }
        double changePerYear = regression.slope() * 12;
        double meanMonthly = Stats.mean(counts);
        double percentPerYear = meanMonthly == 0 ? 0 : changePerYear / meanMonthly * 100;
        boolean significant = regression.n() >= 24 && Math.abs(regression.tStatistic()) >= 1.96;
        String direction = !significant ? "STABLE" : regression.slope() > 0 ? "RISING" : "FALLING";
        String interpretation = significant
                ? String.format(Locale.UK, "Monthly sightings are %s by about %.1f%% per year (t = %.1f).",
                direction.equals("RISING") ? "rising" : "falling", Math.abs(percentPerYear), regression.tStatistic())
                : String.format(Locale.UK, "No significant long-term trend: the fitted change of %+.1f%% per year is"
                + " indistinguishable from noise (t = %.2f; |t| >= 1.96 needed).", percentPerYear, regression.tStatistic());
        return new LongTermTrend(byYear, round(changePerYear, 3), round(percentPerYear, 1), round(regression.rSquared(), 3),
                round(regression.tStatistic(), 2), significant, direction, interpretation);
    }

    // ----------------------------------------------------------- risk areas

    private List<RiskArea> riskAreas(SightingQuery scope, LocalDate asOf) {
        // Always national (species filter kept) so cities can be compared fairly.
        SightingQuery national = SightingQuery.all().withSpecies(scope.species());
        LocalDateTime windowEnd = asOf.plusDays(1).atStartOfDay();
        LocalDateTime windowStart = windowEnd.minusMonths(12);
        LocalDateTime previousStart = windowStart.minusMonths(12);
        Map<String, Long> recent = toMap(repository.countBy(Dimension.CITY, national.withTimeRange(windowStart, windowEnd)));
        Map<String, Long> previous = toMap(repository.countBy(Dimension.CITY, national.withTimeRange(previousStart, windowStart)));

        Set<String> cities = new LinkedHashSet<>();
        catalogue.cities().forEach(c -> cities.add(c.name()));
        cities.addAll(recent.keySet());
        cities.addAll(previous.keySet());
        double totalRecent = recent.values().stream().mapToLong(Long::longValue).sum();
        double meanPerCity = totalRecent / cities.size();

        List<RiskArea> areas = new ArrayList<>();
        for (String city : cities) {
            long r = recent.getOrDefault(city, 0L);
            long p = previous.getOrDefault(city, 0L);
            Double change = p == 0 ? null : round((r - p) * 100.0 / p, 1);
            double relative = meanPerCity == 0 ? 0 : round(r / meanPerCity, 2);
            String level = relative >= HIGH_RISK ? "HIGH" : relative <= LOW_RISK ? "LOW" : "MODERATE";
            String region = catalogue.findCity(city).map(Catalogue.City::region).orElse(Catalogue.UNKNOWN_REGION);
            areas.add(new RiskArea(city, region, r, p, change, relative, level));
        }
        areas.sort(Comparator.comparingLong(RiskArea::last12Months).reversed().thenComparing(RiskArea::city));
        return areas;
    }

    // ------------------------------------------------------------ anomalies

    private AnomalyScan anomalies(SightingQuery upToAsOf) {
        TrendService.Trend byCity = trends.trend(upToAsOf, TrendService.Interval.MONTH, Dimension.CITY);
        int tests = 0;
        List<Anomaly> all = new ArrayList<>();
        for (TrendService.Series series : byCity.series()) {
            double lambda = (double) series.total() / series.points().size();
            for (TrendService.Point point : series.points()) {
                tests++;
                if (point.count() > 0) {
                    double p = Stats.poissonUpperTail(point.count(), lambda);
                    all.add(new Anomaly(series.name(), point.period(), point.count(), round(lambda, 2), p, false));
                }
            }
        }
        double threshold = ALPHA / Math.max(1, tests);
        all.sort(Comparator.comparingDouble(Anomaly::pValue).thenComparing(Anomaly::month));
        long flagged = all.stream().filter(a -> a.pValue() < threshold).count();
        List<Anomaly> top = all.stream().limit(5)
                .map(a -> new Anomaly(a.city(), a.month(), a.sightings(), a.expected(), roundSig(a.pValue()),
                        a.pValue() < threshold))
                .toList();
        String interpretation = flagged > 0
                ? String.format(Locale.UK, "%d city-month(s) had significantly more sightings than that city's"
                + " normal rate, after correcting for the %d comparisons made.", flagged, tests)
                : String.format(Locale.UK, "No city-month stands out once the %d comparisons are accounted for"
                + " (Bonferroni threshold p < %.1e). The months listed are the most unusual, but chance alone"
                + " explains them.", tests, threshold);
        return new AnomalyScan(tests, roundSig(threshold), flagged, top, interpretation);
    }

    // ------------------------------------------------------------- forecast

    private Forecast forecast(List<YearMonth> months, double[] counts, Seasonality seasonality, double[] seasonalIndex,
                              LongTermTrend trend, Stats.Regression regression, int horizon) {
        int n = counts.length;
        YearMonth lastMonth = months.get(n - 1);
        double recentMean = Stats.mean(java.util.Arrays.copyOfRange(counts, Math.max(0, n - 24), n));
        List<ForecastPoint> points = IntStream.rangeClosed(1, horizon).mapToObj(h -> {
            YearMonth month = lastMonth.plusMonths(h);
            double level = trend.significant() ? regression.intercept() + regression.slope() * (n - 1 + h) : recentMean;
            double factor = seasonality.significant() ? seasonalIndex[month.getMonthValue() - 1] : 1.0;
            double expected = Math.max(0, level * factor);
            double halfWidth = 1.96 * Math.sqrt(expected);
            return new ForecastPoint(month.toString(), round(expected, 1), round(Math.max(0, expected - halfWidth), 1),
                    round(expected + halfWidth, 1));
        }).toList();
        String method = (trend.significant() ? "Linear trend" : "Mean of the last 24 months")
                + (seasonality.significant() ? " x seasonal index" : " (no seasonal adjustment: not significant)")
                + "; 95% interval assumes Poisson-distributed monthly counts.";
        return new Forecast(method, points);
    }

    // -------------------------------------------------------------- helpers

    private Insights empty(LocalDate asOf, String scope, String note) {
        return new Insights(asOf, scope, 0, 0, note, null, null, List.of(), null, null);
    }

    private static Map<String, Long> toMap(List<SightingRepository.GroupCount> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        rows.forEach(r -> map.put(r.name(), r.count()));
        return map;
    }

    private static String monthName(int zeroBasedMonth) {
        return Month.of(zeroBasedMonth + 1).getDisplayName(TextStyle.FULL, Locale.UK);
    }

    static double round(double value, int places) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    /** Rounds to 3 significant figures, which suits p-values. */
    static double roundSig(double value) {
        if (value == 0 || Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return new java.math.BigDecimal(value).round(new java.math.MathContext(3)).doubleValue();
    }
}
