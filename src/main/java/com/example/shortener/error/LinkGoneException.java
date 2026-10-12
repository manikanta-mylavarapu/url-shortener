package com.example.shortener.error;

/** The link existed but has expired or was deactivated (HTTP 410). */
public class LinkGoneException extends RuntimeException {
    public LinkGoneException(String code) {
        super("link '" + code + "' has expired or was deactivated");
    }
}
