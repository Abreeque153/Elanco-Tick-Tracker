package com.ticktracker.ingest;

import java.time.Duration;

/** A failure talking to the Elanco API, classified so the caller can decide whether to retry. */
public class UpstreamException extends RuntimeException {

    public enum Kind {
        TIMEOUT(true),
        CONNECTION_FAILED(true),
        /** HTTP 429. The API's docs say to wait about a minute. */
        RATE_LIMITED(true),
        /** HTTP 5xx. */
        SERVER_ERROR(true),
        /** Any other 4xx: retrying the same request won't help. */
        CLIENT_ERROR(false),
        /** The body was not the JSON array the API documents. */
        MALFORMED_RESPONSE(false),
        /** The circuit breaker is open, so no call was made. */
        CIRCUIT_OPEN(false),
        INTERRUPTED(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final Duration retryAfter;

    public UpstreamException(Kind kind, String message) {
        this(kind, message, null, null, null);
    }

    public UpstreamException(Kind kind, String message, Throwable cause) {
        this(kind, message, null, null, cause);
    }

    public UpstreamException(Kind kind, String message, Integer httpStatus, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return kind.retryable();
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    /** Wait the server asked for (Retry-After header), or null. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
