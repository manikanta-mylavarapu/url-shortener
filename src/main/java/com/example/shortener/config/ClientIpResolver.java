package com.example.shortener.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Single place that decides "who is the client". X-Forwarded-For is client-controlled unless a
 * trusted proxy overwrites it, so it is ignored unless explicitly enabled.
 */
@Component
public class ClientIpResolver {

    private final boolean trustForwardedFor;

    public ClientIpResolver(AppProperties props) {
        this.trustForwardedFor = props.rateLimit().trustForwardedFor();
    }

    public String resolve(HttpServletRequest request) {
        if (trustForwardedFor) {
            String header = request.getHeader("X-Forwarded-For");
            if (header != null && !header.isBlank()) {
                return header.split(",")[0].strip();
            }
        }
        return request.getRemoteAddr();
    }
}
