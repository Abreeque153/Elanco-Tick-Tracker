package com.ticktracker.api;

import com.ticktracker.analytics.InsightsService;
import com.ticktracker.query.QueryParser;
import com.ticktracker.store.SightingQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/insights")
@Tag(name = "3. Insights (extension)", description = "Statistical/ML insights: seasonality, trend, risk areas, anomalies and a forecast")
public class InsightsController {

    private final InsightsService insights;
    private final QueryParser queryParser;

    public InsightsController(InsightsService insights, QueryParser queryParser) {
        this.insights = insights;
        this.queryParser = queryParser;
    }

    @GetMapping
    @Operation(summary = "Insights and forecast",
            description = "Every insight is tested for statistical significance before it is claimed. Seasonality "
                    + "uses a chi-square test, the trend a t-test on a least-squares slope, and anomalies Poisson tails "
                    + "with a Bonferroni correction. The forecast uses trend and seasonality only when they are "
                    + "significant. Each section includes a plain-English interpretation.")
    public InsightsService.Insights insights(
            @Parameter(description = "Limit to a region, e.g. Scotland") @RequestParam(name = "region", required = false) List<String> region,
            @Parameter(description = "Limit to a city, e.g. London") @RequestParam(name = "city", required = false) List<String> city,
            @Parameter(description = "Limit to a species") @RequestParam(name = "species", required = false) List<String> species,
            @Parameter(description = "Analyse up to this date (default: latest sighting)")
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @Parameter(description = "Months to forecast, 1-24") @RequestParam(name = "forecastMonths", defaultValue = "6") int forecastMonths) {
        SightingQuery scope = queryParser.parse(null, null, city, region, species, null, null, null);
        SightingFilter label = new SightingFilter(null, null, city, region, species, null, null, null);
        return insights.insights(scope, asOf, forecastMonths, label.describe());
    }
}
