package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import com.example.shortener.domain.ClickEvent;
import com.example.shortener.domain.ShortLink;
import com.example.shortener.error.LinkNotFoundException;
import com.example.shortener.repo.ClickEventRepository;
import com.example.shortener.repo.ShortLinkRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class AnalyticsService {

    static final String DIRECT = "direct";
    private static final int TOP_REFERRERS = 5;

    private final ClickEventRepository clicks;
    private final ShortLinkRepository links;
    private final Clock clock;
    private final String salt;

    public AnalyticsService(ClickEventRepository clicks, ShortLinkRepository links, Clock clock, AppProperties props) {
        this.clicks = clicks;
        this.links = links;
        this.clock = clock;
        this.salt = props.visitorSalt();
    }

    /** Insert + counter increment commit or roll back together. */
    @Transactional
    public void recordClick(String code, ClickContext ctx) {
        Instant now = clock.instant();
        clicks.save(new ClickEvent(
                code,
                now,
                LocalDate.ofInstant(now, ZoneOffset.UTC),
                referrerHost(ctx.referer()),
                visitorHash(ctx.clientIp(), ctx.userAgent())));
        links.incrementClicks(code);
    }

    @Transactional(readOnly = true)
    public LinkAnalytics analytics(String code) {
        ShortLink link = links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        return new LinkAnalytics(
                code,
                link.getClickCount(),
                clicks.countUniqueVisitors(code),
                clicks.clicksPerDay(code),
                clicks.topReferrers(code, PageRequest.of(0, TOP_REFERRERS)));
    }

    /** Host only (never path/query, which can carry tokens); "direct" when absent, "unknown" when unparsable. */
    static String referrerHost(String referer) {
        if (referer == null || referer.isBlank()) {
            return DIRECT;
        }
        try {
            String host = new URI(referer.strip()).getHost();
            if (host == null || host.isBlank()) {
                return "unknown";
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return lower.length() > 255 ? lower.substring(0, 255) : lower;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Salted SHA-256 of IP + user agent, truncated to 128 bits. Approximate by design; irreversible. */
    String visitorHash(String ip, String userAgent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = salt + "|" + (ip == null ? "" : ip) + "|" + (userAgent == null ? "" : userAgent);
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
