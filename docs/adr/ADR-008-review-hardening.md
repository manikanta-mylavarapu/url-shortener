# ADR-008: Hardening found in the full-codebase review

**Status:** accepted · follows a green `mvn verify` (105 tests) and a 33/33 smoke run

Reading every class against the real JaCoCo report surfaced these issues. Each has a regression test.

| # | Finding | Fix | Test |
|---|---|---|---|
| 1 | **Lost update on deactivate.** `deactivate` loaded the entity and called `save`; Hibernate rewrites all columns, so a click counted in between was overwritten with a stale `click_count`. | Targeted `UPDATE ... SET active=false WHERE code=? AND active=true` (`deactivateByCode`). Also idempotent in SQL. | `LinkServiceTest.deactivateUsesAnAtomicUpdate...`, `RedirectIntegrationTest` (count survives deactivation) |
| 2 | **Idempotency-key race burned retries.** When a concurrent twin won the key, our insert failed on the *key* constraint, was mistaken for a code collision, retried N times and inflated `shortener.code.collisions`. | On a violation with a key present, check whether the key now exists and go straight to the replay path. | `LinkServiceTest.losingARaceOnTheKey...` (one insert, zero collisions) |
| 3 | **Rate limiter bypass.** The filter only limited URIs starting with `/api/`; `POST /api;x=1/v1/links` (path parameter) or other spellings the router still accepts skipped it. | Limit every POST/PUT/PATCH/DELETE regardless of path. | `RateLimitFilterTest` |
| 4 | **O(n) scan per request under a distinct-IP flood.** Once the table was saturated, every request re-scanned all buckets. | Eviction scan runs at most once per second (CAS-guarded). | `TokenBucketLimiterTest.evictionScanIsThrottled...` |
| 5 | **HEAD counted as a visit.** HEAD shares the GET handler, so link-preview crawlers and uptime probes inflated analytics. | HEAD resolves (same 404/410 rules) without recording a click. | `RedirectIntegrationTest`, smoke section 9 |
| 6 | **SSRF range gaps.** 198.18.0.0/15, 192.0.0.0/24 and 240.0.0.0/4 (incl. broadcast) were treated as public. | Added to `UrlValidator.isNonPublic`. Legacy numeric spellings and IPv4-mapped IPv6 are pinned by tests. | `UrlValidatorTest` |
| 7 | `ClientIpResolver` at 47 % coverage: the trusted-proxy branch was untested. | Unit tests for default, trusted, blank and missing header. | `ClientIpResolverTest` |
| 8 | CI built one JDK and never ran the app. | JDK 17 + 21 matrix; e2e job boots the jar, runs `smoke.sh`, uploads the transcript and JaCoCo report. | CI |

**Documented, not changed** (see risk register): no DNS resolve timeout (R14), no request-body cap in the app (R15), NAT64/6to4 embedding of private IPv4 not unwrapped, no authentication.
