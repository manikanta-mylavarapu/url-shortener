package com.example.shortener.api;

import com.jayway.jsonpath.JsonPath;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the versioned migrations match the JPA entities: the schema is built ONLY by Flyway and Hibernate
 * runs in {@code validate} mode (the `file` profile's configuration), then the whole create/redirect/analytics
 * path is exercised against it. If an entity changes without a new migration, this test fails.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:flyway-test;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlywayMigrationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired Flyway flyway;

    @Test
    void migrationsApplyAndTheApplicationWorksOnTheMigratedSchema() throws Exception {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");

        String body = mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "flyway-1")
                        .content("{\"url\":\"https://example.com/migrated\",\"expiresInSeconds\":3600}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.code");

        mvc.perform(get("/" + code).header("Referer", "https://news.example.org/a?token=secret"))
                .andExpect(status().isFound());

        mvc.perform(get("/api/v1/links/" + code + "/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1))
                .andExpect(jsonPath("$.uniqueVisitors").value(1))
                .andExpect(jsonPath("$.topReferrers[0].referrer").value("news.example.org"));
    }
}
