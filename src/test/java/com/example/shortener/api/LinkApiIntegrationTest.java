package com.example.shortener.api;

import com.example.shortener.error.AliasTakenException;
import com.example.shortener.repo.ClickEventRepository;
import com.example.shortener.repo.ShortLinkRepository;
import com.example.shortener.service.ClickContext;
import com.example.shortener.service.LinkService;
import com.example.shortener.support.MutableClock;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LinkApiIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired LinkService linkService;
    @Autowired ShortLinkRepository linkRepo;
    @Autowired ClickEventRepository clickRepo;

    @BeforeEach
    void reset() {
        clickRepo.deleteAll();
        linkRepo.deleteAll();
        clock.set(T0);
    }

    private String create(String json) throws Exception {
        String body = mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    // ---- create --------------------------------------------------------------------------

    @Test
    void createReturns201WithShortUrlLocationAndMetadata() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/some/long/path?x=1\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/links/")))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.shortUrl").value(startsWith("http://localhost:8080/")))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/some/long/path?x=1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.totalClicks").value(0));
    }

    @Test
    void sameUrlTwiceYieldsTwoDistinctLinks() throws Exception {
        String a = create("{\"url\":\"https://example.com/same\"}");
        String b = create("{\"url\":\"https://example.com/same\"}");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void invalidInputsAreRejectedAsProblemJson() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"ftp://example.com/x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("http")));

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.url").exists());

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"alias\":\"a!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.alias").exists());

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"expiresInSeconds\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expiresInSeconds").exists());

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    // ---- redirect ------------------------------------------------------------------------

    @Test
    void redirectIs302WithNoStoreAndCountsTheClick() throws Exception {
        String code = create("{\"url\":\"https://example.com/target\"}");

        mvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        mvc.perform(get("/api/v1/links/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1));
    }

    @Test
    void unknownCodeIs404ProblemJson() throws Exception {
        mvc.perform(get("/doesNotExist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    // ---- aliases -------------------------------------------------------------------------

    @Test
    void customAliasWorksAndDuplicatesConflict() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/1\",\"alias\":\"my-docs\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("my-docs"));

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/2\",\"alias\":\"my-docs\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mvc.perform(get("/my-docs")).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/1"));
    }

    @Test
    void reservedAliasIsRefused() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"alias\":\"api\"}"))
                .andExpect(status().isConflict());
    }

    // ---- expiry & deactivation -----------------------------------------------------------

    @Test
    void linkExpiresAtTheConfiguredInstant() throws Exception {
        String code = create("{\"url\":\"https://example.com/t\",\"expiresInSeconds\":60}");

        clock.advance(Duration.ofSeconds(59));
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        clock.advance(Duration.ofSeconds(1));
        mvc.perform(get("/" + code)).andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/v1/links/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.totalClicks").value(1)); // the 410 hit was not counted
    }

    @Test
    void deactivateIsIdempotentAndMakesTheLinkGone() throws Exception {
        String code = create("{\"url\":\"https://example.com/t\"}");

        mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNoContent());
        mvc.perform(get("/" + code)).andExpect(status().isGone());
        mvc.perform(get("/api/v1/links/" + code)).andExpect(jsonPath("$.status").value("DEACTIVATED"));
        mvc.perform(delete("/api/v1/links/missing1")).andExpect(status().isNotFound());
    }

    // ---- changing the expiry (brownfield enhancement BF-2) --------------------------------

    private void expectPatchStatus(String code, String json, int expectedStatus) throws Exception {
        mvc.perform(patch("/api/v1/links/" + code).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void extendingAnExpiredLinkRevivesItFromNow_andKeepsItsClicks() throws Exception {
        String code = create("{\"url\":\"https://example.com/t\",\"expiresInSeconds\":60}");
        mvc.perform(get("/" + code)).andExpect(status().isFound());   // 1 click while active
        clock.advance(Duration.ofSeconds(61));
        mvc.perform(get("/" + code)).andExpect(status().isGone());    // expired, not counted

        mvc.perform(patch("/api/v1/links/" + code).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInSeconds\":3600}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value("2026-03-01T01:01:01Z")) // T0 + 61 s + 1 h: counted from "now"
                .andExpect(jsonPath("$.totalClicks").value(1));

        mvc.perform(get("/" + code)).andExpect(status().isFound());
        mvc.perform(get("/api/v1/links/" + code)).andExpect(jsonPath("$.totalClicks").value(2));
    }

    @Test
    void neverExpiresRemovesTheExpiry() throws Exception {
        String code = create("{\"url\":\"https://example.com/t\",\"expiresInSeconds\":60}");

        mvc.perform(patch("/api/v1/links/" + code).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"neverExpires\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value(org.hamcrest.Matchers.nullValue()));

        clock.advance(Duration.ofDays(800));
        mvc.perform(get("/" + code)).andExpect(status().isFound());
    }

    @Test
    void aDeactivatedLinkCannotBeRevivedThroughPatch() throws Exception {
        String code = create("{\"url\":\"https://example.com/t\"}");
        mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNoContent());

        mvc.perform(patch("/api/v1/links/" + code).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInSeconds\":3600}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LINK_GONE"));
        mvc.perform(get("/" + code)).andExpect(status().isGone());
    }

    @Test
    void patchRejectsInvalidBodiesAndUnknownCodes() throws Exception {
        String code = create("{\"url\":\"https://example.com/t\"}");

        expectPatchStatus(code, "{}", 400);                                                  // nothing to change
        expectPatchStatus(code, "{\"expiresInSeconds\":3600,\"neverExpires\":true}", 400);     // contradictory
        expectPatchStatus(code, "{\"neverExpires\":false}", 400);                              // not a change
        expectPatchStatus(code, "{\"expiresInSeconds\":5}", 400);                              // below the 60 s floor
        expectPatchStatus(code, "{\"expiresInSeconds\":99999999999}", 400);                    // above one year
        expectPatchStatus(code, "{ not json", 400);
        expectPatchStatus("missing1", "{\"expiresInSeconds\":3600}", 404);

        mvc.perform(patch("/api/v1/links/" + code).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/links/" + code)).andExpect(jsonPath("$.expiresAt").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---- analytics -----------------------------------------------------------------------

    @Test
    void analyticsReportTotalsUniqueVisitorsDaysAndReferrerHostsOnly() throws Exception {
        String code = create("{\"url\":\"https://example.com/t\"}");

        mvc.perform(get("/" + code).header("User-Agent", "A").header("Referer", "https://news.example.org/story?token=secret"));
        mvc.perform(get("/" + code).header("User-Agent", "A").header("Referer", "https://news.example.org/other"));
        mvc.perform(get("/" + code).header("User-Agent", "B"));

        String body = mvc.perform(get("/api/v1/links/" + code + "/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(3))
                .andExpect(jsonPath("$.uniqueVisitors").value(2))
                .andExpect(jsonPath("$.clicksPerDay", hasSize(1)))
                .andExpect(jsonPath("$.clicksPerDay[0].day").value("2026-03-01"))
                .andExpect(jsonPath("$.clicksPerDay[0].clicks").value(3))
                .andExpect(jsonPath("$.topReferrers[0].referrer").value("news.example.org"))
                .andExpect(jsonPath("$.topReferrers[0].clicks").value(2))
                .andExpect(jsonPath("$.topReferrers[1].referrer").value("direct"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret").doesNotContain("127.0.0.1");
    }

    @Test
    void clicksAreBucketedPerUtcDay() throws Exception {
        String code = create("{\"url\":\"https://example.com/t\"}");
        mvc.perform(get("/" + code));
        clock.advance(Duration.ofDays(1));
        mvc.perform(get("/" + code));
        mvc.perform(get("/" + code));

        mvc.perform(get("/api/v1/links/" + code + "/analytics"))
                .andExpect(jsonPath("$.clicksPerDay", hasSize(2)))
                .andExpect(jsonPath("$.clicksPerDay[0].day").value("2026-03-01"))
                .andExpect(jsonPath("$.clicksPerDay[0].clicks").value(1))
                .andExpect(jsonPath("$.clicksPerDay[1].day").value("2026-03-02"))
                .andExpect(jsonPath("$.clicksPerDay[1].clicks").value(2));
    }

    @Test
    void analyticsForUnknownCodeIs404() throws Exception {
        mvc.perform(get("/api/v1/links/nope1234/analytics")).andExpect(status().isNotFound());
    }

    // ---- listing -------------------------------------------------------------------------

    @Test
    void listIsPagedNewestFirstAndPageSizeIsCapped() throws Exception {
        for (int i = 0; i < 3; i++) {
            create("{\"url\":\"https://example.com/" + i + "\"}");
            clock.advance(Duration.ofSeconds(1));
        }
        mvc.perform(get("/api/v1/links?page=0&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].originalUrl").value("https://example.com/2"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(get("/api/v1/links?size=100000"))
                .andExpect(jsonPath("$.size").value(100));
    }

    // ---- concurrency ---------------------------------------------------------------------

    @Test
    void concurrentCreatesOfTheSameAliasYieldExactlyOneWinner() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Boolean> task = () -> {
                start.await();
                try {
                    linkService.create("https://example.com/race", "race-alias", null);
                    return true;
                } catch (AliasTakenException e) {
                    return false;
                }
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int winners = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                winners++;
            }
        }
        pool.shutdown();

        assertThat(winners).isEqualTo(1);
        assertThat(linkRepo.count()).isEqualTo(1);
    }

    @Test
    void concurrentRedirectsDoNotLoseClicks() throws Exception {
        String code = create("{\"url\":\"https://example.com/t\"}");
        int clicks = 40;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < clicks; i++) {
            final int n = i;
            futures.add(pool.submit(() ->
                    linkService.resolveForRedirect(code, new ClickContext("203.0.113." + n, "UA-" + n, null))));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(linkRepo.findByCode(code).orElseThrow().getClickCount()).isEqualTo(clicks);
        assertThat(clickRepo.countUniqueVisitors(code)).isEqualTo(clicks);
    }

    // ---- idempotency ---------------------------------------------------------------------

    @Test
    void retryWithSameIdempotencyKeyReturnsTheOriginalLinkAndCreatesNoDuplicate() throws Exception {
        String json = "{\"url\":\"https://example.com/idem\"}";
        String first = mvc.perform(post("/api/v1/links").header("Idempotency-Key", "order-42")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(first, "$.code");

        mvc.perform(post("/api/v1/links").header("Idempotency-Key", "order-42")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.code").value(code));

        assertThat(linkRepo.count()).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIs422() throws Exception {
        mvc.perform(post("/api/v1/links").header("Idempotency-Key", "k-reuse")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com/one\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/links").header("Idempotency-Key", "k-reuse")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com/two\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(linkRepo.count()).isEqualTo(1);
    }

    @Test
    void malformedIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/api/v1/links").header("Idempotency-Key", "bad key!")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRequestsWithTheSameKeyAllResolveToOneLink() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<com.example.shortener.service.CreateResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return linkService.createIdempotent("race-key", "https://example.com/race", null, null);
            }));
        }
        start.countDown();

        int created = 0;
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (Future<com.example.shortener.service.CreateResult> f : results) {
            var result = f.get();
            codes.add(result.link().getCode());
            if (!result.replayed()) {
                created++;
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(codes).hasSize(1);
        assertThat(linkRepo.count()).isEqualTo(1);
    }

    // ---- contract & metrics --------------------------------------------------------------

    @Test
    void apiContractIsServedFromTheSingleSourceOfTruth() throws Exception {
        mvc.perform(get("/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("openapi: 3.0.3")))
                .andExpect(content().string(containsString("Idempotency-Key")));
    }

    @Test
    void reliabilityCountersAreExposedAsMetrics() throws Exception {
        create("{\"url\":\"https://example.com/m\"}");
        for (String name : new String[]{"shortener.links.created", "shortener.code.collisions", "shortener.analytics.failures"}) {
            mvc.perform(get("/actuator/metrics/" + name))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(name));
        }
    }

    // ---- ops -----------------------------------------------------------------------------

    @Test
    void healthEndpointIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void errorResponsesNeverLeakStackTraces() throws Exception {
        mvc.perform(get("/api/v1/links/nope1234"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("at com.example"))));
    }
}
