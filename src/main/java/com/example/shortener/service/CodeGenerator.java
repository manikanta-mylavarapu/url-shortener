package com.example.shortener.service;

import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Generates random Base62 short codes.
 *
 * <p>Framework-free on purpose: the random source is injected so tests can be deterministic,
 * while production wiring supplies a {@link java.security.SecureRandom} (codes must not be
 * predictable, otherwise links can be enumerated).
 */
public final class CodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final int length;
    private final RandomGenerator random;

    public CodeGenerator(int length, RandomGenerator random) {
        if (length < 4 || length > 32) {
            throw new IllegalArgumentException("code length must be between 4 and 32, was " + length);
        }
        this.length = length;
        this.random = Objects.requireNonNull(random, "random");
    }

    public String generate() {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(out);
    }

    public int length() {
        return length;
    }
}
