package com.example.shortener.repo;

import java.time.LocalDate;

/** Clicks for one UTC day (query projection). */
public record DayCount(LocalDate day, Long clicks) {
}
