package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One redirect. Privacy by design: no raw IP or full referrer URL is stored, only a salted hash
 * of (IP, user agent) for approximate unique-visitor counting and the referrer's host name.
 */
@Entity
@Table(name = "click_events", indexes = {
        @Index(name = "idx_click_code_day", columnList = "code,click_day"),
        @Index(name = "idx_click_code_visitor", columnList = "code,visitor_hash")
})
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "clicked_at", nullable = false)
    private Instant clickedAt;

    /** UTC day bucket, stored so per-day aggregation is a plain indexed GROUP BY. */
    @Column(name = "click_day", nullable = false)
    private LocalDate clickDay;

    @Column(name = "referrer_host", nullable = false, length = 255)
    private String referrerHost;

    @Column(name = "visitor_hash", nullable = false, length = 32)
    private String visitorHash;

    protected ClickEvent() {
        // for JPA
    }

    public ClickEvent(String code, Instant clickedAt, LocalDate clickDay, String referrerHost, String visitorHash) {
        this.code = code;
        this.clickedAt = clickedAt;
        this.clickDay = clickDay;
        this.referrerHost = referrerHost;
        this.visitorHash = visitorHash;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public Instant getClickedAt() { return clickedAt; }
    public LocalDate getClickDay() { return clickDay; }
    public String getReferrerHost() { return referrerHost; }
    public String getVisitorHash() { return visitorHash; }
}
