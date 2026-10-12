package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import com.example.shortener.domain.ShortLink;
import com.example.shortener.error.AliasTakenException;
import com.example.shortener.error.CodeAllocationException;
import com.example.shortener.error.IdempotencyKeyReuseException;
import com.example.shortener.error.InvalidAliasException;
import com.example.shortener.error.InvalidIdempotencyKeyException;
import com.example.shortener.error.LinkGoneException;
import com.example.shortener.error.LinkNotFoundException;
import com.example.shortener.repo.ShortLinkRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Core use cases. Intentionally NOT class-level @Transactional: a unique-constraint violation
 * inside an outer transaction would mark it rollback-only and make the retry impossible. Each
 * repository call is its own short transaction.
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    static final Pattern ALIAS_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");
    static final Pattern IDEMPOTENCY_KEY_FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    /** Paths served by the application itself; an alias equal to one would be unreachable or confusing. */
    static final Set<String> RESERVED = Set.of("api", "actuator", "error", "h2-console", "favicon.ico", "robots.txt");

    private final ShortLinkRepository links;
    private final AnalyticsService analytics;
    private final CodeGenerator codes;
    private final UrlValidator urlValidator;
    private final Clock clock;
    private final int maxCodeAttempts;

    private final Counter linksCreated;
    private final Counter codeCollisions;
    private final Counter analyticsFailures;

    public LinkService(ShortLinkRepository links, AnalyticsService analytics, CodeGenerator codes,
                       UrlValidator urlValidator, Clock clock, AppProperties props, MeterRegistry meters) {
        this.links = links;
        this.analytics = analytics;
        this.codes = codes;
        this.urlValidator = urlValidator;
        this.clock = clock;
        this.maxCodeAttempts = props.maxCodeAttempts();
        this.linksCreated = meters.counter("shortener.links.created");
        this.codeCollisions = meters.counter("shortener.code.collisions");
        // Analytics failures are swallowed on purpose (redirects must survive); this counter is how they stay visible.
        this.analyticsFailures = meters.counter("shortener.analytics.failures");
    }

    /**
     * @param alias optional caller-chosen code
     * @param ttl   optional time-to-live; null means the link never expires
     */
    public ShortLink create(String rawUrl, String alias, Duration ttl) {
        return doCreate(rawUrl, alias, ttl, null, null);
    }

    /**
     * Retry-safe create. With an {@code Idempotency-Key}, repeating the same request returns the original
     * link ({@code replayed = true}) instead of creating a duplicate; reusing the key for a different request
     * is rejected. Without a key this behaves exactly like {@link #create}.
     *
     * <p>Concurrency: the unique constraint on the key decides the winner. A loser's insert fails, it
     * re-reads by key and replays the winner's result.
     */
    public CreateResult createIdempotent(String idempotencyKey, String rawUrl, String alias, Duration ttl) {
        if (idempotencyKey == null) {
            return new CreateResult(create(rawUrl, alias, ttl), false);
        }
        if (!IDEMPOTENCY_KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must be 1-64 characters of letters, digits, '.', '_', ':' or '-'");
        }
        String fingerprint = fingerprint(rawUrl, alias, ttl);

        Optional<ShortLink> existing = links.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        try {
            return new CreateResult(doCreate(rawUrl, alias, ttl, idempotencyKey, fingerprint), false);
        } catch (AliasTakenException | CodeAllocationException | IdempotencyRaceLost failure) {
            // Possibly lost a race with a concurrent request that carried the same key.
            return links.findByIdempotencyKey(idempotencyKey)
                    .map(winner -> replay(winner, fingerprint))
                    .orElseThrow(() -> failure);
        }
    }

    private CreateResult replay(ShortLink existing, String fingerprint) {
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            throw new IdempotencyKeyReuseException();
        }
        return new CreateResult(existing, true);
    }

    private ShortLink doCreate(String rawUrl, String alias, Duration ttl, String idempotencyKey, String fingerprint) {
        String url = urlValidator.validate(rawUrl);
        Instant now = clock.instant();
        Instant expiresAt = ttl == null ? null : now.plus(ttl);

        if (alias != null) {
            return createWithAlias(url, alias, now, expiresAt, idempotencyKey, fingerprint);
        }
        for (int attempt = 1; attempt <= maxCodeAttempts; attempt++) {
            String code = codes.generate();
            try {
                ShortLink saved = links.saveAndFlush(
                        ShortLink.create(code, url, now, expiresAt, idempotencyKey, fingerprint));
                linksCreated.increment();
                return saved;
            } catch (DataIntegrityViolationException collision) {
                if (idempotencyKey != null && links.findByIdempotencyKey(idempotencyKey).isPresent()) {
                    // The violated constraint was the idempotency key, not the code: a concurrent twin won.
                    // Retrying would fail identically and inflate the collision metric; hand over to the replay path.
                    throw new IdempotencyRaceLost();
                }
                codeCollisions.increment();
                log.warn("short code collision on attempt {}/{}", attempt, maxCodeAttempts);
            }
        }
        throw new CodeAllocationException(maxCodeAttempts);
    }

    private ShortLink createWithAlias(String url, String alias, Instant now, Instant expiresAt,
                                      String idempotencyKey, String fingerprint) {
        if (!ALIAS_FORMAT.matcher(alias).matches()) {
            throw new InvalidAliasException("alias must be 3-32 characters of letters, digits, '-' or '_'");
        }
        if (RESERVED.contains(alias.toLowerCase(Locale.ROOT)) || links.existsByCode(alias)) {
            throw new AliasTakenException(alias);
        }
        try {
            ShortLink saved = links.saveAndFlush(
                    ShortLink.create(alias, url, now, expiresAt, idempotencyKey, fingerprint));
            linksCreated.increment();
            return saved;
        } catch (DataIntegrityViolationException race) {
            // Lost a race with a concurrent request; the DB constraint is the arbiter.
            throw new AliasTakenException(alias);
        }
    }

    /**
     * Resolves a code for redirection and records the click. Analytics is best-effort: a failure
     * there is logged and counted but must never turn a valid redirect into an error.
     */
    public String resolveForRedirect(String code, ClickContext context) {
        ShortLink link = servable(code);
        try {
            analytics.recordClick(code, context);
        } catch (RuntimeException e) {
            analyticsFailures.increment();
            log.error("failed to record click for code {}; redirect still served", code, e);
        }
        return link.getOriginalUrl();
    }

    /**
     * Same lookup and 404/410 rules as {@link #resolveForRedirect} but records nothing. Used for HEAD requests:
     * link-preview crawlers and uptime probes use HEAD and are not visitors, so they must not inflate analytics.
     */
    public String resolveWithoutClick(String code) {
        return servable(code).getOriginalUrl();
    }

    private ShortLink servable(String code) {
        ShortLink link = find(code);
        if (!link.isServable(clock.instant())) {
            throw new LinkGoneException(code);
        }
        return link;
    }

    public ShortLink get(String code) {
        return find(code);
    }

    public Page<ShortLink> list(Pageable pageable) {
        return links.findAll(pageable);
    }

    /** Idempotent soft delete: the row and its analytics are kept, the link answers 410. */
    public void deactivate(String code) {
        find(code); // 404 for unknown codes
        links.deactivateByCode(code);
    }

    /**
     * Changes when a link expires. Allowed on active and on expired links (extending an expired link brings it back);
     * a deactivated link is a deliberate operator decision and answers 410 - it cannot be revived through this call.
     *
     * @param ttl new time-to-live counted from now, or null to remove the expiry
     */
    public ShortLink updateExpiry(String code, Duration ttl) {
        ShortLink link = find(code);
        if (!link.isActive()) {
            throw new LinkGoneException(code);
        }
        Instant newExpiry = ttl == null ? null : clock.instant().plus(ttl);
        if (links.updateExpiry(code, newExpiry) == 0) {
            throw new LinkGoneException(code); // deactivated between our read and the update
        }
        return find(code);
    }

    /** Internal signal: our insert lost to a concurrent request with the same Idempotency-Key. */
    private static final class IdempotencyRaceLost extends RuntimeException {
        IdempotencyRaceLost() {
            super("lost idempotency-key race", null, false, false);
        }
    }

    private ShortLink find(String code) {
        return links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }

    /** Stable hash of the request's meaningful inputs; lets us tell a true retry from key reuse. */
    static String fingerprint(String rawUrl, String alias, Duration ttl) {
        String material = (rawUrl == null ? "" : rawUrl.strip())
                + '\u001f' + (alias == null ? "" : alias)
                + '\u001f' + (ttl == null ? "" : Long.toString(ttl.toSeconds()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
