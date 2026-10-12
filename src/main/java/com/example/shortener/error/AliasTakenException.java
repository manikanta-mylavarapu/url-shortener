package com.example.shortener.error;

/** A custom alias is already in use or reserved (HTTP 409). */
public class AliasTakenException extends RuntimeException {
    public AliasTakenException(String alias) {
        super("alias '" + alias + "' is not available");
    }
}
