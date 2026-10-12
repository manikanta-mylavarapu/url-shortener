package com.example.shortener.service;

import com.example.shortener.domain.ShortLink;

/** Outcome of a create call: {@code replayed} is true when an earlier result for the same Idempotency-Key was returned. */
public record CreateResult(ShortLink link, boolean replayed) {
}
