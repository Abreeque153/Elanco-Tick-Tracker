package com.ticktracker.error;

/**
 * The request conflicts with the current state, for example a sync is already
 * running or the same sighting was already reported. Mapped to HTTP 409.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
