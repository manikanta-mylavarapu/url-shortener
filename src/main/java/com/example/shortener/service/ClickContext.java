package com.example.shortener.service;

/** The request attributes analytics needs, decoupled from the servlet API. All fields may be null. */
public record ClickContext(String clientIp, String userAgent, String referer) {
}
