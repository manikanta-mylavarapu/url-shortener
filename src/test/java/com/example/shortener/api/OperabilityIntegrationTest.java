package com.example.shortener.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract tests for the operability features: stable error codes, request correlation, readiness. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:operability-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperabilityIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void everyResponseCarriesARequestId_andACallerSuppliedSafeOneIsEchoed() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Request-Id", not(hasLength(0))));

        mvc.perform(get("/api/v1/links/nope").header("X-Request-Id", "trace-123"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "trace-123"))
                .andExpect(jsonPath("$.requestId").value("trace-123"));
    }

    @Test
    void unsafeCallerRequestIdIsReplaced() throws Exception {
        mvc.perform(get("/api/v1/links/nope").header("X-Request-Id", "bad id <x>"))
                .andExpect(header().string("X-Request-Id", not("bad id <x>")))
                .andExpect(jsonPath("$.requestId").value(not("bad id <x>")));
    }

    @Test
    void errorsCarryStableMachineReadableCodes() throws Exception {
        mvc.perform(get("/api/v1/links/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://example.com/x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_URL"));

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/x\",\"alias\":\"api\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALIAS_UNAVAILABLE"));
    }

    @Test
    void frameworkErrorsUseTheSameEnvelope() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("HTTP_ERROR"));

        mvc.perform(delete("/api/v1/links"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("HTTP_ERROR"));
    }

    @Test
    void readinessReflectsDatabaseAndLivenessIsIndependent() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
