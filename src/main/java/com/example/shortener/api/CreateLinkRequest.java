package com.example.shortener.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLinkRequest(
        @NotBlank @Size(max = 2048) String url,
        @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$",
                message = "alias must be 3-32 characters of letters, digits, '-' or '_'") String alias,
        @Min(value = 60, message = "expiresInSeconds must be at least 60")
        @Max(value = 31_536_000, message = "expiresInSeconds must be at most one year") Long expiresInSeconds) {
}
