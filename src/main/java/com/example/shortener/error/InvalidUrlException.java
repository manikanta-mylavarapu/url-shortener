package com.example.shortener.error;

/** The submitted URL or request field failed validation (HTTP 400). */
public class InvalidUrlException extends RuntimeException {
    public InvalidUrlException(String message) {
        super(message);
    }
}
