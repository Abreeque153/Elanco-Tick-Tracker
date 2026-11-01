package com.ticktracker.analytics;

/**
 * The statistics behind the insights endpoint, implemented directly so every
 * number can be explained:
 * <ul>
 *   <li>chi-square goodness-of-fit, to test whether seasonality is real;</li>
 *   <li>Poisson tail probabilities, to test whether a month is a genuine spike;</li>
 *   <li>ordinary least squares, to test for a long-term trend.</li>
 * </ul>
 */
public final class Stats {

    private Stats() {
    }

    /** Linear fit of y against x = 0, 1, 2, ... */
    public record Regression(double slope, double intercept, double rSquared, double slopeStandardError,
                             double tStatistic, int n) {
    }

    /** P(X >= x) for a chi-square distribution with {@code df} degrees of freedom. */
    public static double chiSquareSurvival(double x, int degreesOfFreedom) {
        if (x <= 0) {
            return 1.0;
        }
        return regularizedGammaQ(degreesOfFreedom / 2.0, x / 2.0);
    }

    /** P(X >= k) for X ~ Poisson(lambda). Uses the identity P(X >= k) = P(k, lambda). */
    public static double poissonUpperTail(long k, double lambda) {
        if (k <= 0) {
            return 1.0;
        }
        if (lambda <= 0) {
            return 0.0;
        }
        return regularizedGammaP(k, lambda);
    }

    public static Regression linearRegression(double[] y) {
        int n = y.length;
        if (n < 3) {
            return new Regression(0, n == 0 ? 0 : mean(y), 0, Double.POSITIVE_INFINITY, 0, n);
        }
        double xMean = (n - 1) / 2.0;
        double yMean = mean(y);
        double sxx = 0;
        double sxy = 0;
        for (int i = 0; i < n; i++) {
            sxx += (i - xMean) * (i - xMean);
            sxy += (i - xMean) * (y[i] - yMean);
        }
        double slope = sxy / sxx;
        double intercept = yMean - slope * xMean;
        double residual = 0;
        double total = 0;
        for (int i = 0; i < n; i++) {
            double fitted = intercept + slope * i;
            residual += (y[i] - fitted) * (y[i] - fitted);
            total += (y[i] - yMean) * (y[i] - yMean);
        }
        double rSquared = total == 0 ? 0 : 1 - residual / total;
        double standardError = Math.sqrt((residual / (n - 2)) / sxx);
        double t = standardError == 0 ? (slope == 0 ? 0 : Double.POSITIVE_INFINITY) : slope / standardError;
        return new Regression(slope, intercept, rSquared, standardError, t, n);
    }

    public static double mean(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    // ----------------------------------------------- incomplete gamma function

    /** Regularised lower incomplete gamma P(a, x). */
    static double regularizedGammaP(double a, double x) {
        if (x <= 0) {
            return 0.0;
        }
        return x < a + 1 ? gammaSeries(a, x) : 1.0 - gammaContinuedFraction(a, x);
    }

    /** Regularised upper incomplete gamma Q(a, x) = 1 - P(a, x). */
    static double regularizedGammaQ(double a, double x) {
        if (x <= 0) {
            return 1.0;
        }
        return x < a + 1 ? 1.0 - gammaSeries(a, x) : gammaContinuedFraction(a, x);
    }

    private static double gammaSeries(double a, double x) {
        double ap = a;
        double term = 1.0 / a;
        double sum = term;
        for (int n = 0; n < 10_000; n++) {
            ap += 1;
            term *= x / ap;
            sum += term;
            if (Math.abs(term) < Math.abs(sum) * 1e-15) {
                break;
            }
        }
        return sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
    }

    /** Modified Lentz continued fraction for Q(a, x). */
    private static double gammaContinuedFraction(double a, double x) {
        double tiny = 1e-300;
        double b = x + 1 - a;
        double c = 1 / tiny;
        double d = 1 / b;
        double h = d;
        for (int i = 1; i < 10_000; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = b + an / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1) < 1e-15) {
                break;
            }
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    /** ln Γ(x) via the Lanczos approximation (g = 7). */
    static double logGamma(double x) {
        double[] c = {
                0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
                1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1 - x);
        }
        x -= 1;
        double a = c[0];
        double t = x + 7.5;
        for (int i = 1; i < c.length; i++) {
            a += c[i] / (x + i);
        }
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }
}
