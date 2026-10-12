package com.example.shortener.error;

/**
 * Stable, machine-readable error identifiers returned in the {@code code} member of every
 * problem+json body. Clients branch on these, never on the human-readable {@code title}/{@code detail}
 * (which may be reworded) and not only on the HTTP status (several causes share one status).
 * Adding a value is backwards compatible; renaming or removing one is a breaking API change.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    INVALID_URL,
    INVALID_ALIAS,
    INVALID_IDEMPOTENCY_KEY,
    IDEMPOTENCY_KEY_REUSED,
    LINK_NOT_FOUND,
    ALIAS_UNAVAILABLE,
    LINK_GONE,
    CODE_ALLOCATION_FAILED,
    RATE_LIMITED,
    INTERNAL_ERROR,
    /** Framework-level failures (malformed JSON, unsupported method/media type, ...): {@code HTTP_<status>}. */
    HTTP_ERROR
}
