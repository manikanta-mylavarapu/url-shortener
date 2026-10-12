package com.example.shortener.api;

import com.example.shortener.config.AppProperties;
import com.example.shortener.domain.ShortLink;
import com.example.shortener.service.AnalyticsService;
import com.example.shortener.service.CreateResult;
import com.example.shortener.service.LinkAnalytics;
import com.example.shortener.service.LinkService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private static final int MAX_PAGE_SIZE = 100;

    private final LinkService links;
    private final AnalyticsService analytics;
    private final Clock clock;
    private final String baseUrl;

    public LinkController(LinkService links, AnalyticsService analytics, Clock clock, AppProperties props) {
        this.links = links;
        this.analytics = analytics;
        this.clock = clock;
        this.baseUrl = props.baseUrl();
    }

    /**
     * 201 for a new link. With an {@code Idempotency-Key}, a retry of the same request returns 200 with the
     * original link and {@code Idempotent-Replayed: true} instead of creating a duplicate.
     */
    @PostMapping
    public ResponseEntity<LinkResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateLinkRequest request) {
        Duration ttl = request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds());
        CreateResult result = links.createIdempotent(idempotencyKey, request.url(), request.alias(), ttl);
        ShortLink link = result.link();
        URI location = URI.create("/api/v1/links/" + link.getCode());
        if (result.replayed()) {
            return ResponseEntity.ok()
                    .location(location)
                    .header("Idempotent-Replayed", "true")
                    .body(toResponse(link));
        }
        return ResponseEntity.created(location).body(toResponse(link));
    }

    @GetMapping("/{code}")
    public LinkResponse get(@PathVariable String code) {
        return toResponse(links.get(code));
    }

    @GetMapping("/{code}/analytics")
    public LinkAnalytics analytics(@PathVariable String code) {
        return analytics.analytics(code);
    }

    @GetMapping
    public PageResponse<LinkResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var result = links.list(PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return PageResponse.of(result.map(this::toResponse));
    }

    /** Set a new expiry ({@code expiresInSeconds}, from now) or remove it ({@code neverExpires: true}). */
    @PatchMapping("/{code}")
    public LinkResponse update(@PathVariable String code, @Valid @RequestBody UpdateLinkRequest request) {
        Duration ttl = request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds());
        return toResponse(links.updateExpiry(code, ttl));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> deactivate(@PathVariable String code) {
        links.deactivate(code);
        return ResponseEntity.noContent().build();
    }

    private LinkResponse toResponse(ShortLink link) {
        return LinkResponse.from(link, baseUrl, clock.instant());
    }
}
