package com.ticktracker.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * HTTP client for the Elanco tick sightings API
 * ({@code https://dev-task.elancoapps.com/data}).
 *
 * <p>Behaviours, checked against the live API on 1 Nov 2026:
 * <ul>
 *   <li>{@code GET /tick-sightings} returns every record in one JSON array with
 *       no pagination (about 140 KB for 1,000 records). It is streamed rather than
 *       buffered.</li>
 *   <li>The API documents {@code 429} (rate limit, "wait a minute") and
 *       {@code 500}. Both, plus timeouts and connection errors, are retried with
 *       exponential backoff, and {@code Retry-After} is honoured.</li>
 *   <li>Latency reached about 5 s under concurrent load, so every request has
 *       explicit connect and response timeouts.</li>
 *   <li>Repeated failures open a {@link CircuitBreaker}, so a broken upstream
 *       is not hammered.</li>
 * </ul>
 */
public final class ElancoApiClient {

    private static final Logger log = LoggerFactory.getLogger(ElancoApiClient.class);

    /** Pause before a retry. Swapped out in tests so they don't actually sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;

        static Sleeper real() {
            return duration -> Thread.sleep(duration.toMillis());
        }
    }

    private final HttpClient http;
    private final URI baseUri;
    private final Duration requestTimeout;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker circuitBreaker;
    private final JsonArrayReader arrayReader;
    private final Sleeper sleeper;
    private final Clock clock;

    public ElancoApiClient(HttpClient http, URI baseUri, Duration requestTimeout, RetryPolicy retryPolicy,
                           CircuitBreaker circuitBreaker, JsonArrayReader arrayReader, Sleeper sleeper, Clock clock) {
        this.http = http;
        this.baseUri = withTrailingSlash(baseUri);
        this.requestTimeout = requestTimeout;
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = circuitBreaker;
        this.arrayReader = arrayReader;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    public URI baseUri() {
        return baseUri;
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Streams every sighting from {@code GET /tick-sightings} into {@code sink}.
     *
     * <p>Getting a successful response is retried. Once records start flowing
     * into the sink, a failure is <em>not</em> retried here (that would replay
     * records already processed). It is raised so the sync can be marked
     * partial, and the next scheduled run picks up the rest idempotently.
     *
     * @return the number of records delivered
     */
    public long streamAllSightings(Consumer<JsonNode> sink) {
        if (!circuitBreaker.allowRequest()) {
            throw new UpstreamException(UpstreamException.Kind.CIRCUIT_OPEN,
                    "Upstream calls are paused after repeated failures; next attempt allowed at "
                            + circuitBreaker.openUntil().map(Object::toString).orElse("any moment"));
        }
        try {
            HttpResponse<InputStream> response = sendWithRetry(baseUri.resolve("tick-sightings"));
            long delivered;
            try (InputStream body = response.body()) {
                delivered = arrayReader.forEach(body, sink);
            }
            circuitBreaker.recordSuccess();
            return delivered;
        } catch (UpstreamException e) {
            if (e.kind() != UpstreamException.Kind.INTERRUPTED) {
                circuitBreaker.recordFailure();
            }
            throw e;
        } catch (IOException e) {
            circuitBreaker.recordFailure();
            throw new UpstreamException(UpstreamException.Kind.CONNECTION_FAILED,
                    "Connection dropped while reading sightings: " + e.getMessage(), e);
        }
    }

    /** Light-weight check of {@code GET /health}; never throws. */
    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("health"))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private HttpResponse<InputStream> sendWithRetry(URI uri) {
        UpstreamException last = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            if (attempt > 1) {
                Duration delay = retryPolicy.delayBefore(attempt, last.retryAfter());
                log.warn("Upstream call failed ({}). Retry {}/{} in {} ms", last.getMessage(), attempt - 1,
                        retryPolicy.maxAttempts() - 1, delay.toMillis());
                pause(delay);
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(requestTimeout)
                        .header("Accept", "application/json")
                        .header("User-Agent", "uk-tick-tracker/1.0")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 200) {
                    return response;
                }
                last = classify(response);
            } catch (HttpTimeoutException e) {
                last = new UpstreamException(UpstreamException.Kind.TIMEOUT,
                        "No response within " + requestTimeout.toSeconds() + "s", e);
            } catch (IOException e) {
                last = new UpstreamException(UpstreamException.Kind.CONNECTION_FAILED,
                        "Could not connect to " + uri.getHost() + ": " + e, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException(UpstreamException.Kind.INTERRUPTED, "Interrupted while calling upstream", e);
            }
            if (!last.retryable()) {
                throw last;
            }
        }
        throw new UpstreamException(last.kind(),
                "Gave up after " + retryPolicy.maxAttempts() + " attempts: " + last.getMessage(),
                last.httpStatus(), last.retryAfter(), last);
    }

    private UpstreamException classify(HttpResponse<InputStream> response) {
        int status = response.statusCode();
        String snippet = readSnippet(response.body());
        Duration retryAfter = parseRetryAfter(response.headers()).orElse(null);
        UpstreamException.Kind kind;
        if (status == 429) {
            kind = UpstreamException.Kind.RATE_LIMITED;
        } else if (status >= 500) {
            kind = UpstreamException.Kind.SERVER_ERROR;
        } else {
            kind = UpstreamException.Kind.CLIENT_ERROR;
        }
        return new UpstreamException(kind, "HTTP " + status + (snippet.isEmpty() ? "" : " " + snippet),
                status, retryAfter, null);
    }

    /** Retry-After may be seconds ("60") or an HTTP date. */
    Optional<Duration> parseRetryAfter(HttpHeaders headers) {
        return headers.firstValue("Retry-After").map(String::strip).flatMap(value -> {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
            } catch (NumberFormatException notSeconds) {
                try {
                    ZonedDateTime at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                    Duration wait = Duration.between(clock.instant(), at.toInstant());
                    return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
                } catch (DateTimeParseException notADate) {
                    return Optional.empty();
                }
            }
        });
    }

    private void pause(Duration delay) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(UpstreamException.Kind.INTERRUPTED, "Interrupted while waiting to retry", e);
        }
    }

    private static String readSnippet(InputStream body) {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(200);
            return new String(bytes, StandardCharsets.UTF_8).strip().replaceAll("\\s+", " ");
        } catch (IOException e) {
            return "";
        }
    }

    private static URI withTrailingSlash(URI uri) {
        String text = uri.toString();
        return text.endsWith("/") ? uri : URI.create(text + "/");
    }
}
