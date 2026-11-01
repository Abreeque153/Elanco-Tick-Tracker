package com.ticktracker.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scriptable stand-in for the Elanco API, built on the JDK's HTTP server.
 * Queue up responses (errors, rate limits, slow or broken bodies) to test
 * how the client and pipeline behave, with no network needed.
 */
public final class FakeUpstream implements AutoCloseable {

    public record Response(int status, String body, Map<String, String> headers, long delayMillis) {
        public static Response ok(String body) {
            return new Response(200, body, Map.of(), 0);
        }

        public static Response status(int status, String body) {
            return new Response(status, body, Map.of(), 0);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentLinkedDeque<Response> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile Response fallback = Response.ok("[]");

    public FakeUpstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/data/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/data");
    }

    /** Responses are served in order; once the queue is empty the fallback is used. */
    public FakeUpstream enqueue(Response response) {
        queue.add(response);
        return this;
    }

    public FakeUpstream respondAlways(Response response) {
        queue.clear();
        fallback = response;
        return this;
    }

    public int requestCount() {
        return requests.get();
    }

    public void resetCount() {
        requests.set(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        Response response = queue.isEmpty() ? fallback : queue.poll();
        if (response == null) {
            response = fallback;
        }
        try {
            if (response.delayMillis() > 0) {
                Thread.sleep(response.delayMillis());
            }
            byte[] body = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // client gave up (e.g. timeout test); nothing to do
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
