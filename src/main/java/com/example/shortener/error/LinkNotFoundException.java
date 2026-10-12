package com.example.shortener.error;

/** No link exists for the given code (HTTP 404). */
public class LinkNotFoundException extends RuntimeException {
    public LinkNotFoundException(String code) {
        super("no link found for code '" + code + "'");
    }
}
