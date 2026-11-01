package com.ticktracker.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the hand-written statistics against textbook values. */
class StatsTest {

    @Test
    void logGammaMatchesFactorials() {
        assertEquals(Math.log(24), Stats.logGamma(5), 1e-10);       // Γ(5) = 4!
        assertEquals(Math.log(Math.sqrt(Math.PI)), Stats.logGamma(0.5), 1e-10);
    }

    @Test
    void chiSquareSurvivalMatchesCriticalValueTables() {
        // Standard 5% critical values for 1, 5 and 11 degrees of freedom
        assertEquals(0.05, Stats.chiSquareSurvival(3.841459, 1), 1e-5);
        assertEquals(0.05, Stats.chiSquareSurvival(11.070498, 5), 1e-5);
        assertEquals(0.05, Stats.chiSquareSurvival(19.675138, 11), 1e-5);
        assertEquals(0.01, Stats.chiSquareSurvival(24.724970, 11), 1e-5);
        assertEquals(1.0, Stats.chiSquareSurvival(0, 11), 0);
    }

    @Test
    void poissonTailMatchesDirectSummation() {
        // P(X >= 3 | lambda = 1) = 1 - e^-1 (1 + 1 + 1/2)
        assertEquals(1 - 2.5 / Math.E, Stats.poissonUpperTail(3, 1.0), 1e-12);
        // P(X >= 1 | lambda = 2) = 1 - e^-2
        assertEquals(1 - Math.exp(-2), Stats.poissonUpperTail(1, 2.0), 1e-12);
        assertEquals(1.0, Stats.poissonUpperTail(0, 5.0), 0);
        assertTrue(Stats.poissonUpperTail(40, 2.0) < 1e-20, "extreme tails stay accurate, not 0 from cancellation");
    }

    @Test
    void linearRegressionRecoversAKnownLine() {
        double[] y = new double[50];
        for (int i = 0; i < y.length; i++) {
            y[i] = 3 + 0.5 * i + ((i % 2 == 0) ? 0.1 : -0.1);
        }
        Stats.Regression fit = Stats.linearRegression(y);
        assertEquals(0.5, fit.slope(), 1e-3);
        assertEquals(3.0, fit.intercept(), 0.05);
        assertTrue(fit.rSquared() > 0.999);
        assertTrue(fit.tStatistic() > 100);
    }

    @Test
    void aFlatSeriesHasNoTrend() {
        Stats.Regression fit = Stats.linearRegression(new double[]{5, 5, 5, 5, 5, 5});
        assertEquals(0, fit.slope(), 1e-12);
        assertEquals(0, fit.tStatistic(), 1e-12);
    }
}
