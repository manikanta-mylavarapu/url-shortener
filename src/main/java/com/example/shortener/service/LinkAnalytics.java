package com.example.shortener.service;

import com.example.shortener.repo.DayCount;
import com.example.shortener.repo.ReferrerCount;

import java.util.List;

public record LinkAnalytics(
        String code,
        long totalClicks,
        long uniqueVisitors,
        List<DayCount> clicksPerDay,
        List<ReferrerCount> topReferrers) {
}
