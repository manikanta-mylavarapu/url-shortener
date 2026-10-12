package com.example.shortener.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Change a link's expiry. Exactly one of the two fields must be provided:
 * {@code expiresInSeconds} sets the expiry to "now + N seconds" (same bounds as on create);
 * {@code neverExpires = true} removes the expiry.
 */
public record UpdateLinkRequest(
        @Min(value = 60, message = "expiresInSeconds must be at least 60")
        @Max(value = 31_536_000, message = "expiresInSeconds must be at most one year") Long expiresInSeconds,
        Boolean neverExpires) {

    @AssertTrue(message = "provide exactly one of expiresInSeconds or neverExpires=true")
    public boolean isExactlyOneChange() {
        boolean ttl = expiresInSeconds != null;
        boolean never = Boolean.TRUE.equals(neverExpires);
        return ttl != never;
    }
}
