package com.example.shortener.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Own context (tiny bucket, own database) so it cannot disturb the other integration tests.
 * One sequential scenario on purpose: all requests share the same client bucket, so splitting it
 * into several test methods would make the outcome depend on execution order.
 */
@SpringBootTest(properties = {
        "app.rate-limit.capacity=2",
        "app.rate-limit.refill-per-second=0.001",
        "spring.datasource.url=jdbc:h2:mem:ratelimit-test;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitIntegrationTest {

    private static final String BODY = "{\"url\":\"https://example.com/x\"}";

    @Autowired MockMvc mvc;

    @Test
    void writesAreThrottledPerClient_spoofedHeadersDoNotHelp_andReadsAreUnaffected() throws Exception {
        // Burst capacity (2) is available...
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isCreated());
        }

        // ...then the third write is rejected with a machine-readable retry hint.
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // X-Forwarded-For is client-controlled and untrusted by default: rotating it must not mint fresh buckets.
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(BODY)
                            .header("X-Forwarded-For", "198.51.100." + i))
                    .andExpect(status().isTooManyRequests());
        }

        // Reads and redirects stay available while writes are throttled.
        mvc.perform(get("/api/v1/links")).andExpect(status().isOk());
        mvc.perform(get("/nonexistent")).andExpect(status().isNotFound());
    }
}
