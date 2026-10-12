package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "short_links")
public class ShortLink {

    public enum Status { ACTIVE, EXPIRED, DEACTIVATED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique at the database level: the constraint, not an existence check, is the source of truth. */
    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Denormalized counter, incremented atomically in SQL so concurrent clicks are not lost. */
    @Column(name = "click_count", nullable = false)
    private long clickCount;

    /** Client-supplied Idempotency-Key. Nullable + unique: many rows may have none, each key maps to one link. */
    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;

    /** Hash of the request that created the link, used to detect a key being reused for a different request. */
    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    protected ShortLink() {
        // for JPA
    }

    public static ShortLink create(String code, String originalUrl, Instant createdAt, Instant expiresAt) {
        return create(code, originalUrl, createdAt, expiresAt, null, null);
    }

    public static ShortLink create(String code, String originalUrl, Instant createdAt, Instant expiresAt,
                                   String idempotencyKey, String requestFingerprint) {
        ShortLink link = new ShortLink();
        link.code = code;
        link.originalUrl = originalUrl;
        link.createdAt = createdAt;
        link.expiresAt = expiresAt;
        link.idempotencyKey = idempotencyKey;
        link.requestFingerprint = requestFingerprint;
        return link;
    }

    public Status statusAt(Instant now) {
        if (!active) {
            return Status.DEACTIVATED;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return Status.EXPIRED;
        }
        return Status.ACTIVE;
    }

    public boolean isServable(Instant now) {
        return statusAt(now) == Status.ACTIVE;
    }

    public void deactivate() {
        this.active = false;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getOriginalUrl() { return originalUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isActive() { return active; }
    public long getClickCount() { return clickCount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
}
