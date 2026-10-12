package com.example.shortener.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:redirect-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedirectIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void headRequestsRedirectLikeGetButAreNotCountedAsVisits_andDeactivationKeepsTheCount() throws Exception {
        String body = mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/head-test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.code");

        mvc.perform(head("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/head-test"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/links/" + code + "/analytics")).andExpect(jsonPath("$.totalClicks").value(0));

        mvc.perform(get("/" + code)).andExpect(status().isFound());
        mvc.perform(get("/api/v1/links/" + code + "/analytics")).andExpect(jsonPath("$.totalClicks").value(1));

        // Deactivation is a targeted UPDATE: it must not disturb the counter, and HEAD must see the 410 too.
        mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNoContent());
        mvc.perform(head("/" + code)).andExpect(status().isGone());
        mvc.perform(get("/api/v1/links/" + code)).andExpect(jsonPath("$.totalClicks").value(1))
                .andExpect(jsonPath("$.status").value("DEACTIVATED"));
    }

    @Test
    void headOnAnUnknownCodeIsNotFound() throws Exception {
        mvc.perform(head("/does-not-exist")).andExpect(status().isNotFound());
    }
}
