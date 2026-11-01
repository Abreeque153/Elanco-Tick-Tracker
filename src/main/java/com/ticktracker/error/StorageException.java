package com.ticktracker.error;

/** Wraps an unexpected database failure. Mapped to HTTP 500 without leaking SQL details. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
