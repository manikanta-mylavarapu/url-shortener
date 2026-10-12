package com.example.shortener.error;

/** The custom alias is malformed (HTTP 400). */
public class InvalidAliasException extends RuntimeException {
    public InvalidAliasException(String message) {
        super(message);
    }
}
