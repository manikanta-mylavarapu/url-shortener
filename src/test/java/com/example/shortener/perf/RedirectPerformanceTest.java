package com.example.shortener.perf;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Performance gate for the hot path (redirect = 1 read + 1 insert + 1 atomic counter update).
 *
 * <p>Thresholds are intentionally generous (an order of magnitude above what a laptop measures) so the test
 * is stable on slow CI runners; its job is to catch pathological regressions - an accidental N+1, a full table
 * scan, a lock held across I/O - not to benchmark. Real capacity planning needs a load test against the
 * production database; see docs/05-TESTING_AND_RISKS.md.
 */
@Tag("performance")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:perf-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedirectPerformanceTest {

    private static final int SEQUENTIAL_REDIRECTS = 1_000;
    private static final int THREADS = 4;
    private static final int PER_THREAD = 100;
    private static final long P95_BUDGET_MILLIS = 100;
    private static final long CONCURRENT_BUDGET_MILLIS = 30_000;

    @Autowired MockMvc mvc;

    @Test
    void redirectLatencyStaysWithinBudget_andConcurrentClicksAreAllCounted() throws Exception {
        String body = mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/perf\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.code");

        for (int i = 0; i < 100; i++) { // warm-up: class loading, JIT, connection pool
            mvc.perform(get("/" + code)).andExpect(status().isFound());
        }

        long[] nanos = new long[SEQUENTIAL_REDIRECTS];
        for (int i = 0; i < SEQUENTIAL_REDIRECTS; i++) {
            long start = System.nanoTime();
            mvc.perform(get("/" + code)).andExpect(status().isFound());
            nanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        long p95Millis = nanos[(int) (SEQUENTIAL_REDIRECTS * 0.95) - 1] / 1_000_000;
        assertThat(p95Millis).as("p95 redirect latency in ms").isLessThanOrEqualTo(P95_BUDGET_MILLIS);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            Callable<Integer> worker = () -> {
                go.await();
                int ok = 0;
                for (int i = 0; i < PER_THREAD; i++) {
                    if (mvc.perform(get("/" + code)).andReturn().getResponse().getStatus() == 302) {
                        ok++;
                    }
                }
                return ok;
            };
            results.add(pool.submit(worker));
        }
        long start = System.nanoTime();
        go.countDown();
        int served = 0;
        for (Future<Integer> f : results) {
            served += f.get();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();

        assertThat(served).as("redirects served concurrently").isEqualTo(THREADS * PER_THREAD);
        assertThat(elapsedMillis).as("concurrent run duration in ms").isLessThanOrEqualTo(CONCURRENT_BUDGET_MILLIS);

        // Correctness under load: warm-up + sequential + concurrent, none lost (atomic counter, no swallowed failures).
        int expected = 100 + SEQUENTIAL_REDIRECTS + THREADS * PER_THREAD;
        String link = mvc.perform(get("/api/v1/links/" + code)).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(link, "$.totalClicks")).isEqualTo(expected);
    }
}
