# 05 – Testing Approach, Risks, Limitations, Trade-offs

## 1. Test strategy (pyramid)

| Level | Scope | Files | Why |
|---|---|---|---|
| Unit – pure logic (no Spring) | `CodeGenerator`, `UrlValidator`, `TokenBucketLimiter`, referrer parsing | `service/*Test` | Fast, hermetic (stub DNS, injected clock/RNG) |
| Unit – service with mocks | `LinkService` rules: collision retry, exhaustion, alias races, expiry boundary, analytics isolation | `LinkServiceTest` | Failure paths that are hard to trigger for real |
| Integration – HTTP + real H2 | Every status code, JSON contract, analytics math, expiry via test clock, paging, health | `LinkApiIntegrationTest` | Verifies wiring, JPQL, serialization |
| Concurrency | 16-thread alias race; 40 parallel clicks | in `LinkApiIntegrationTest` | Proves DB constraint + atomic counter, not just code review |
| Abuse control | 429, `Retry-After`, spoofed `X-Forwarded-For`, path-spelling bypass attempts | `RateLimitIntegrationTest`, `RateLimitFilterTest`, `ClientIpResolverTest` | Own context so it cannot affect other tests |
| Performance | p95 redirect latency budget (1,000 sequential), 4-thread burst, all clicks counted | `RedirectPerformanceTest` (`@Tag("performance")`) | Catches pathological regressions (N+1, scans, lock contention); budgets are deliberately loose - not a capacity test |
| Static analysis / security scan | Checkstyle (dead code, defects), SpotBugs, OWASP dependency-check, Dependabot | `mvn verify` (Checkstyle + SpotBugs), `mvn -Psecurity verify` / CI job `security` | Lint and SpotBugs gate every build; the vulnerability scan is CI/opt-in because of the NVD download; see ADR-009 |
| Operability | stable error codes, request ids, readiness, HEAD semantics, Flyway schema == entities | `OperabilityIntegrationTest`, `RedirectIntegrationTest`, `FlywayMigrationIntegrationTest`, `RequestIdFilterTest` | Contract-level checks of the production-readiness features |

Determinism: no `Thread.sleep`, no real DNS, no wall-clock dependence (`MutableClock`).

**Historical note - what was executed when this was first generated (before the Maven run described below):** the framework-free core (code generator, SSRF validator,
token bucket) was compiled with `javac` and exercised by a 39-check harness – all passed. The idempotent-create logic of `LinkService` was additionally compiled against minimal framework stubs and exercised with an in-memory repository that enforces the same unique constraints (17 checks, including 100 rounds × 16 concurrent threads using one key). `scripts/smoke.sh` was checked against a throwaway mock server (script mechanics only, not the product). The Maven suites (Mockito,
Spring, JPA, MockMvc) could **not** be run in the generation sandbox because Maven Central was blocked by network
policy. Run `mvn verify` first; treat any failure there as a real finding to triage (likely candidates are listed in §4).

## 2. Risk register

| # | Risk / failure scenario | Likelihood | Impact | Mitigation in code | Residual |
|---|---|---|---|---|---|
| R1 | Random code collision | Low (3.5·10¹² space) | Failed create | DB unique constraint + ≤5 retries → 503 + `Retry-After` | Rises with table size; lengthen code if > ~10⁹ links |
| R2 | Alias race | Medium | Duplicate alias | Constraint is the arbiter; loser gets 409 | None |
| R3 | Analytics store failure | Medium | Lost click | Isolated, logged; redirect still served | Undercounted clicks (accepted trade-off) |
| R4 | SSRF / internal target submitted | Medium | Abuse of shortener | Resolve + reject non-public, scheme/userinfo checks | **DNS rebinding** after creation; re-validate on any future server-side fetch |
| R5 | Write-API flood | Medium | Resource exhaustion | Per-client token bucket, bounded map | Distributed floods / spoofed source IPs; single-node only |
| R6 | `X-Forwarded-For` misconfiguration | Medium | Limit bypass or global lockout | Untrusted by default, one resolver class | Operator must set it correctly behind proxies |
| R7 | Unbounded `click_events` growth | High over time | Disk, slow aggregates | Indexes on `(code, day)` | Needs retention + daily rollups for real scale |
| R8 | Hot link / redirect throughput | Medium | Latency | Single indexed lookup + one insert | No cache; add Caffeine/Redis (see architecture §6) |
| R9 | Default visitor salt left in production | Medium | Weaker anonymization | Documented; env override | Add startup check that fails on the default salt in a `prod` profile |
| R10 | Schema drift / unreviewed DDL | Medium | Data loss or outage on deploy | Flyway `V1` + Hibernate `validate` under the `file` profile; `FlywayMigrationIntegrationTest` fails if entities and SQL diverge (ADR-007) | In-memory default still uses `ddl-auto: update`; migration test not yet executed by the author; add `V2+` for every entity change; Postgres module not added |
| R16 | Any caller can change any link's expiry (and extend an expired link) | Medium | Unauthorised revival / early expiry | Rate limited; deactivated links cannot be revived; targeted UPDATE cannot touch clicks (ADR-010) | No authentication or ownership (limitation 3) |
| R14 | Slow/hanging DNS during URL validation | Medium | Request threads blocked in `InetAddress.getAllByName` (JDK default resolver timeouts are long) | Writes are rate limited per client; only creation resolves DNS, never redirects | No explicit resolve timeout; put the validator behind a bounded executor with a timeout before exposing publicly |
| R15 | Oversized request bodies | Low | Memory pressure while parsing huge JSON | `@Size` limits apply after parsing; Tomcat defaults | No body-size cap in the app (a `Content-Length` filter would miss chunked uploads) - enforce at the gateway/reverse proxy |
| R13 | Untraceable failures / brittle clients | Low | Slow incident response | `X-Request-Id` in header, logs and error bodies; stable `code` in every problem+json | Ids are per service, not propagated to downstream calls (there are none yet) |
| R11 | Idempotency keys never expire | Medium over time | Row-level storage growth, keys unusable for new requests forever | Unique key per link; documented | Add a retention window / purge job if keys are client-generated per request |
| R12 | Key reuse across different payloads | Low | Client bug | Fingerprint of URL+alias+TTL; 422 on mismatch | Fingerprint ignores future request fields – extend it when the API grows |

## 3. Trade-offs made deliberately
- **Fail-open** when the rate-limiter table is saturated: availability over strictness.
- **Best-effort analytics** over exactly-once counting: simplicity and redirect availability.
- **302 + no-store** over 301: correctness of analytics/expiry over raw redirect performance.
- **Approximate unique visitors** over storing IPs: privacy over accuracy.
- **H2 / single node** over distributed design: runnable in one command; scaling path documented.
- **Always-new link per request** (no dedupe): avoids leaking that a URL was shortened before and keeps per-link TTL/analytics independent.

## 4. Known limitations & things to check first when you run it
1. The Spring/JPA/MockMvc tests could not be executed in the generation environment (see §1); they have since been run by the engineer: **105 tests green, 93.5 % instruction / 83.5 % branch coverage**. The first real run, on **JDK 26**, failed inside Mockito ("Java 26 (70) is not supported by the current version of Byte Buddy"): a toolchain mismatch, not a code bug. The pom now overrides Byte Buddy (1.17.8) and JaCoCo (0.8.15) and sets `-Dnet.bytebuddy.experimental=true`; this fix itself is unverified until you re-run `mvn clean verify`. Using an LTS JDK (17/21/25) avoids the issue.
2. `concurrentRedirectsDoNotLoseClicks`: 40 parallel writers update one row on H2; if you see lock-timeout flakiness,
   lower the parallelism or raise the H2 lock timeout – a failure here would show up as *fewer* counted clicks because
   analytics errors are swallowed (by design) and logged.
3. No authentication or per-user ownership: anyone can create or deactivate any link by code.
4. Checkstyle, SpotBugs and the OWASP scan are configured (ADR-009) but had not been executed when this was written - expect to triage the first findings. SpotBugs is skipped automatically on JDK 24+. There is no real load test: `RedirectPerformanceTest` only guards against gross regressions on H2.
5. No CI-run evidence yet; the workflow (`.github/workflows/ci.yml`: JDK 17 + 21 matrix, e2e smoke job, evidence upload) has not been executed on GitHub.
6. H2 file mode is not a durability story (no backups/replication).
7. Case-sensitive aliases: `Docs` and `docs` are different links (confusable); normalize to lower case if undesirable.
8. IDN/punycode and exotic IP encodings (octal/hex) are only handled to the extent the JDK parser normalizes them.

## 5. Safe change management
- All behaviour changes ship with tests first-class in the same change.
- Schema changes additive/nullable (see brownfield scenario) → trivial rollback.
- High-impact areas have an explicit sign-off checklist (`04-AI_USAGE_LOG.md` §5).
- Configuration, not code, controls guard rails (`app.*`), so mitigations can be tightened without a deploy of new logic.
