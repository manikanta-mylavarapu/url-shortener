package com.example.shortener.error;

/** Could not allocate a unique code within the retry budget (HTTP 503, retryable by the client). */
public class CodeAllocationException extends RuntimeException {
    public CodeAllocationException(int attempts) {
        super("could not allocate a unique short code after " + attempts + " attempts");
    }
}
