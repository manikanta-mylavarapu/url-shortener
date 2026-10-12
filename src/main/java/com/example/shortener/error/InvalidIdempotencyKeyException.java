package com.example.shortener.error;

/** The Idempotency-Key header is malformed (HTTP 400). */
public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
