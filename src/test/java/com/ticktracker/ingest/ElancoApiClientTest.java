package com.ticktracker.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticktracker.support.FakeUpstream;
import com.ticktracker.support.MutableClock;
import com.ticktracker.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElancoApiClientTest {

    private final FakeUpstream upstream = new FakeUpstream();
    private final MutableClock clock = TestSupport.clock();
    private final List<Duration> sleeps = new ArrayList<>();
    private final CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofMinutes(2), clock);
    private final ElancoApiClient client = TestSupport.client(upstream, 4, breaker, sleeps, clock);
    private final List<JsonNode> received = new ArrayList<>();

    @AfterEach
    void stop() {
        upstream.close();
    }

    @Test
    void streamsEveryRecordFromTheArray() {
        upstream.respondAlways(FakeUpstream.Response.ok(TestSupport.fixture("upstream-clean.json")));
        long count = client.streamAllSightings(received::add);
        assertEquals(15, count);
        assertEquals(15, received.size());
        assertEquals("02WNholuSg6ndCk4c1dA", received.get(0).get("id").asText());
        assertTrue(sleeps.isEmpty(), "no retries needed");
    }

    @Test
    void retriesServerErrorsWithExponentialBackoff() {
        upstream.respondAlways(FakeUpstream.Response.ok("[{\"id\":\"x\"}]"))
                .enqueue(FakeUpstream.Response.status(500, "{\"error\":\"Internal server error\"}"))
                .enqueue(FakeUpstream.Response.status(503, ""));

        assertEquals(1, client.streamAllSightings(received::add));
        assertEquals(3, upstream.requestCount());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void honoursRetryAfterOnRateLimiting() {
        upstream.respondAlways(FakeUpstream.Response.ok("[]"));
        upstream.enqueue(new FakeUpstream.Response(429, "{\"error\":\"Rate limit exceeded, please try again later\"}",
                Map.of("Retry-After", "7"), 0));
        client.streamAllSightings(received::add);
        assertEquals(List.of(Duration.ofSeconds(7)), sleeps);
    }

    @Test
    void givesUpAfterMaxAttemptsAndReportsTheLastError() {
        upstream.respondAlways(FakeUpstream.Response.status(500, "{\"error\":\"Internal server error\"}"));
        UpstreamException e = assertThrows(UpstreamException.class, () -> client.streamAllSightings(received::add));
        assertEquals(UpstreamException.Kind.SERVER_ERROR, e.kind());
        assertEquals(500, e.httpStatus());
        assertTrue(e.getMessage().contains("Gave up after 4 attempts"), e.getMessage());
        assertEquals(4, upstream.requestCount());
    }

    @Test
    void doesNotRetryClientErrors() {
        upstream.respondAlways(FakeUpstream.Response.status(404, "{\"error\":\"Not found\"}"));
        UpstreamException e = assertThrows(UpstreamException.class, () -> client.streamAllSightings(received::add));
        assertEquals(UpstreamException.Kind.CLIENT_ERROR, e.kind());
        assertEquals(1, upstream.requestCount());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void rejectsAPayloadThatIsNotAJsonArray() {
        upstream.respondAlways(FakeUpstream.Response.ok("<html>Service Unavailable</html>"));
        UpstreamException e = assertThrows(UpstreamException.class, () -> client.streamAllSightings(received::add));
        assertEquals(UpstreamException.Kind.MALFORMED_RESPONSE, e.kind());
    }

    @Test
    void reportsATruncatedPayloadAfterDeliveringTheCompleteRecords() {
        upstream.respondAlways(FakeUpstream.Response.ok("[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"c\""));
        UpstreamException e = assertThrows(UpstreamException.class, () -> client.streamAllSightings(received::add));
        assertEquals(UpstreamException.Kind.MALFORMED_RESPONSE, e.kind());
        assertEquals(2, received.size(), "records before the break are still delivered");
    }

    @Test
    void timesOutASlowUpstream() {
        HttpClient http = HttpClient.newHttpClient();
        ElancoApiClient impatient = new ElancoApiClient(http, upstream.baseUri(), Duration.ofMillis(200),
                new RetryPolicy(2, Duration.ZERO, Duration.ZERO, 0, new Random(1)), breaker,
                new JsonArrayReader(TestSupport.MAPPER), sleeps::add, clock);
        upstream.respondAlways(new FakeUpstream.Response(200, "[]", Map.of(), 2_000));
        UpstreamException e = assertThrows(UpstreamException.class, () -> impatient.streamAllSightings(received::add));
        assertEquals(UpstreamException.Kind.TIMEOUT, e.kind());
    }

    @Test
    void reportsAnUnreachableHostAsAConnectionFailure() {
        upstream.close();
        UpstreamException e = assertThrows(UpstreamException.class, () -> client.streamAllSightings(received::add));
        assertEquals(UpstreamException.Kind.CONNECTION_FAILED, e.kind());
    }

    @Test
    void opensTheCircuitAfterRepeatedFailuresAndThenFailsFast() {
        upstream.respondAlways(FakeUpstream.Response.status(404, "{}"));
        for (int i = 0; i < 3; i++) {
            assertThrows(UpstreamException.class, () -> client.streamAllSightings(received::add));
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        upstream.resetCount();

        UpstreamException e = assertThrows(UpstreamException.class, () -> client.streamAllSightings(received::add));
        assertEquals(UpstreamException.Kind.CIRCUIT_OPEN, e.kind());
        assertEquals(0, upstream.requestCount(), "no call is made while the circuit is open");

        clock.advance(Duration.ofMinutes(2));
        upstream.respondAlways(FakeUpstream.Response.ok("[]"));
        client.streamAllSightings(received::add);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void parsesRetryAfterAsAnHttpDate() {
        java.net.http.HttpHeaders headers = java.net.http.HttpHeaders.of(
                Map.of("Retry-After", List.of("Sun, 01 Nov 2026 12:00:30 GMT")), (k, v) -> true);
        assertEquals(Duration.ofSeconds(30), client.parseRetryAfter(headers).orElseThrow());
    }

    @Test
    void healthCheckNeverThrows() {
        upstream.respondAlways(FakeUpstream.Response.ok("{\"status\":\"healthy\"}"));
        assertTrue(client.isHealthy());
        ElancoApiClient nowhere = new ElancoApiClient(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/data"),
                Duration.ofSeconds(1), RetryPolicy.noRetries(), breaker, new JsonArrayReader(TestSupport.MAPPER),
                sleeps::add, clock);
        assertFalse(nowhere.isHealthy());
    }
}
