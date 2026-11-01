package com.ticktracker.error;

/** A client sent parameters we cannot act on. Mapped to HTTP 400. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
