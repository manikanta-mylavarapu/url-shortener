package com.example.shortener.config;

import com.example.shortener.error.ErrorCode;
import com.example.shortener.service.TokenBucketLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Rate limits state-changing requests (POST/PUT/PATCH/DELETE) per client. Redirects are deliberately not limited here:
 * they are the hot, read-only path and are better protected at the edge (CDN / gateway).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final TokenBucketLimiter limiter;
    private final ClientIpResolver clientIps;
    private final boolean enabled;

    public RateLimitFilter(TokenBucketLimiter limiter, ClientIpResolver clientIps, AppProperties props) {
        this.limiter = limiter;
        this.clientIps = clientIps;
        this.enabled = props.rateLimit().enabled();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Deliberately NOT keyed on the path: matching getRequestURI().startsWith("/api/") can be bypassed with
        // path parameters ("/api;x=1/v1/links"), duplicate slashes or other spellings the router still accepts.
        // Every state-changing method is limited; the only such handlers live under /api anyway.
        return !enabled || !LIMITED_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TokenBucketLimiter.Decision decision = limiter.tryAcquire(clientIps.resolve(request));
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String requestId = MDC.get(RequestIdFilter.MDC_KEY); // set by RequestIdFilter, which runs first; charset-safe by construction
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Rate limit exceeded. Retry after " + decision.retryAfterSeconds() + " seconds.\","
                + "\"code\":\"" + ErrorCode.RATE_LIMITED + "\""
                + (requestId == null ? "" : ",\"requestId\":\"" + requestId + "\"")
                + "}");
    }
}
