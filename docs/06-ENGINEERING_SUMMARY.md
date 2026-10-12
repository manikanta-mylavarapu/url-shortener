# 06 – Final Engineering Summary

## Plan & rationale
Interpret "URL shortener with core APIs, analytics and reliability" as a single-node, correctness-first service,
decompose it into 13 dependency-ordered tasks (`01-…`), push risky logic into small framework-free classes that are cheap
to test exhaustively, and let the database (not application code) arbitrate uniqueness and counting.

## Artifacts
| Artifact | Location |
|---|---|
| Runnable service | `src/main/java/**`, `pom.xml`, `application.yml` |
| API contract | `docs/openapi.yaml` |
| Schema | `domain/ShortLink`, `domain/ClickEvent` (+ indexes) |
| Tests | `src/test/java/**` – 16 test classes (+ 9 brownfield tests in existing classes) + `MutableClock` helper |
| Architecture, scenarios, AI log, risks | `docs/02…05` |
| CI | `.github/workflows/ci.yml` |
| Decision records | `docs/adr/ADR-001 … 010` |
| Smoke script (evidence generator) | `scripts/smoke.sh` |
| Submission checklist + reference comparison | `docs/SUBMISSION.md` |

## What was built
Shorten (random Base62, custom alias, TTL) · redirect (302/no-store, 404/410) · list/get/deactivate ·
analytics (total, unique, per-day, top referrer hosts) · SSRF-safe URL intake · per-client rate limiting ·
RFC 7807 errors · health probes · graceful shutdown · idempotent create (`Idempotency-Key`) · reliability counters · contract served at `/openapi.yaml`.

## Risks / trade-offs / validation
See `05-TESTING_AND_RISKS.md`. Highest-impact calls: 302 vs 301, fail-open rate limiter, best-effort analytics,
approximate visitor identity, DNS-rebinding limitation of the SSRF guard.

## Assumptions
A1–A8 in `01-…`: always-new links, 302, hashed visitor identity, single-node reliability, H2, random codes,
no auth, untrusted `X-Forwarded-For`.

## Limitations
No authentication/ownership, no cache, no retention/rollups for click data, single-node rate limiting, no HA,
prototype schema management, no static analysis configured.

## Validation status (be precise when you present this)
| Item | Status |
|---|---|
| Core logic (code generator, SSRF validator, token bucket) | ✅ compiled and 39/39 checks executed with `javac` in the generation environment |
| Referrer-host parsing edge cases | ✅ verified against the JDK `URI` behaviour |
| Idempotent create + counters (`LinkService`) | ✅ stub-compiled; 17 checks incl. 100 rounds × 16 threads on an in-memory repo with real unique-constraint semantics |
| `scripts/smoke.sh` mechanics | ✅ 33/33 against a mock server; non-zero exit on a dead server |
| Maven build, Mockito/Spring/JPA/MockMvc suites, JaCoCo | ✅ `mvn verify` green on the engineer's machine: 105 tests, 93.5 % instruction / 83.5 % branch coverage (before the final review round, which adds ~15 tests - re-run once) |
| Flyway migration vs entities (`validate`) | ✅ `FlywayMigrationIntegrationTest` passed |
| End-to-end smoke test | ✅ 33/33 against the running service (transcript in `docs/evidence/`); script since extended to 45 checks (request id, HEAD, PATCH) - re-run for a fresh transcript |
| Engineer review & sign-off | ☐ yours (see `04-AI_USAGE_LOG.md`) |

## Ownership
AI accelerated drafting, test generation and documentation within clearly specified tasks. The engineer owns
correctness, maintainability and production readiness, and approves every high-impact item before release.
