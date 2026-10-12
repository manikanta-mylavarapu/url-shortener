package com.example.shortener.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    @Test
    void keepsAWellFormedCallerId() {
        assertThat(RequestIdFilter.resolve("order-42.retry_1")).isEqualTo("order-42.retry_1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "has space",
            "line\nbreak",              // log forging
            "carriage\rreturn",
            "<script>alert(1)</script>",
            "semi;colon",
            "unicode-é",
    })
    void replacesMissingOrUnsafeIds(String inbound) {
        String id = RequestIdFilter.resolve(inbound);
        assertThat(id).isNotEqualTo(inbound).matches("[0-9a-f-]{36}"); // a random UUID
    }

    @Test
    void rejectsOverlongIds() {
        String tooLong = "a".repeat(65);
        assertThat(RequestIdFilter.resolve(tooLong)).isNotEqualTo(tooLong);
        assertThat(RequestIdFilter.resolve("a".repeat(64))).hasSize(64);
    }

    @Test
    void generatedIdsAreUnique() {
        assertThat(RequestIdFilter.resolve(null)).isNotEqualTo(RequestIdFilter.resolve(null));
    }
}
