package com.example.shortener.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation id: echoed in the {@code X-Request-Id} response header, put in the
 * logging MDC (so each log line of the request carries it) and included in error bodies.
 *
 * <p>A caller-supplied id is honoured only if it is short and made of safe characters; anything else is
 * replaced. Otherwise a client could inject newlines/markup into our logs (log forging) or flood them.
 * Runs first, before the rate limiter, so even 429 responses are correlatable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Returns the inbound id if acceptable, else a fresh random one. Pure function: unit-testable. */
    static String resolve(String inbound) {
        if (inbound != null && SAFE.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // servlet threads are pooled: never leak an id into the next request
        }
    }
}
