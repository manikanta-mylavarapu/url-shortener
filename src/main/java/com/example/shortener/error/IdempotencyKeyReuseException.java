package com.example.shortener.error;

/** The same Idempotency-Key was sent with a different request body (HTTP 422). */
public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException() {
        super("this Idempotency-Key was already used with a different request");
    }
}
