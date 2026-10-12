# URL Shortener – AI-Assisted Engineering Prototype

A production-style URL shortener (Java 17, Spring Boot 3.3, H2) with analytics, link expiry, custom aliases,
SSRF-safe URL intake and per-client rate limiting. It doubles as the artifact for an *AI-assisted engineering
execution* exercise: requirements → decomposition → implementation → validation → documentation, with
engineer-owned quality gates.

## Quick start

**Prerequisites:** JDK 17+ and Maven 3.9+. No database or Docker needed.

> **Java version.** Spring Boot 3.3 officially targets up to Java 23. Prefer an LTS JDK (**17, 21 or 25**). On newer JDKs (e.g. 26) Mockito's Byte Buddy fails with *"Java 26 (70) is not supported by the current version of Byte Buddy"*; the pom therefore overrides `byte-buddy.version` (1.17.8) and JaCoCo (0.8.15) and passes `-Dnet.bytebuddy.experimental=true` to the tests. If you hit that error again on an even newer JDK, raise `byte-buddy.version` in `pom.xml` or switch to an LTS JDK.

```bash
mvn verify                 # compile + all tests + JaCoCo report (target/site/jacoco/index.html)
mvn spring-boot:run        # http://localhost:8080 (in-memory H2)
# persistent data across restarts (schema managed by Flyway migrations, Hibernate only validates):
mvn spring-boot:run -Dspring-boot.run.profiles=file
```

Production-relevant environment variables: `APP_BASE_URL` (used to build `shortUrl`),
`APP_VISITOR_SALT` (**must** be set; salts the visitor hash).

## Quality gates

| Gate | Command | In CI |
|---|---|---|
| Unit + integration + performance tests, coverage, **lint (Checkstyle), static analysis (SpotBugs)** | `mvn verify` (JaCoCo report in `target/site/jacoco`; SpotBugs auto-skips on JDK 24+) | yes (JDK 17 and 21) |
| End-to-end smoke test (45 checks) | `./scripts/smoke.sh` against a running app | yes, against the packaged jar |
| Dependency vulnerabilities | `NVD_API_KEY=... mvn -Psecurity verify` (slow NVD download, so not in the default build); weekly Dependabot PRs | `security` job, when the `NVD_API_KEY` secret is set |

## Try it

Automated version of everything below: `scripts/smoke.sh` (writes `docs/evidence/smoke-transcript.txt`).

```bash
# 1. shorten
curl -s -X POST localhost:8080/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/docs/getting-started"}'
# 201 {"code":"aB3xY7z","shortUrl":"http://localhost:8080/aB3xY7z","status":"ACTIVE",...}

# 2. custom alias + expiry (seconds, 60 .. 31536000)
curl -s -X POST localhost:8080/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/promo","alias":"spring-promo","expiresInSeconds":3600}'

# 2b. retry-safe create: repeat with the same Idempotency-Key -> 200 + same link, no duplicate
curl -si -X POST localhost:8080/api/v1/links -H 'Content-Type: application/json' -H 'Idempotency-Key: order-42' \
  -d '{"url":"https://example.com/once"}'          # 201 first time, then 200 + Idempotent-Replayed: true

# 3. follow it: 302 + Cache-Control: no-store, click recorded
curl -si localhost:8080/spring-promo | head -5

# 4. analytics
curl -s localhost:8080/api/v1/links/spring-promo/analytics
# {"code":"spring-promo","totalClicks":1,"uniqueVisitors":1,"clicksPerDay":[{"day":"2026-10-12","clicks":1}],
#  "topReferrers":[{"referrer":"direct","clicks":1}]}

# 4b. extend (or remove) the expiry; also revives an expired link
curl -s -X PATCH localhost:8080/api/v1/links/spring-promo -H 'Content-Type: application/json' -d '{"expiresInSeconds":86400}'
curl -s -X PATCH localhost:8080/api/v1/links/spring-promo -H 'Content-Type: application/json' -d '{"neverExpires":true}'

# 5. manage
curl -s "localhost:8080/api/v1/links?page=0&size=20"
curl -s -X DELETE localhost:8080/api/v1/links/spring-promo -i     # 204; link now answers 410

# 6. guardrails
curl -s -X POST localhost:8080/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"http://169.254.169.254/latest/meta-data/"}'          # 400 problem+json (SSRF guard)
curl -s localhost:8080/actuator/health                              # {"status":"UP"}
```

## API summary (full contract: [`docs/openapi.yaml`](docs/openapi.yaml))

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/api/v1/links` (optional `Idempotency-Key` header) | 201 + `Location`; **200** + `Idempotent-Replayed: true` on a retry | 400 invalid url/alias/ttl/key · 409 alias taken/reserved · 422 key reused with a different request · 429 rate limited · 503 code allocation exhausted |
| GET | `/{code}` | 302 (`no-store`) | 404 unknown · 410 expired/deactivated |
| GET | `/api/v1/links/{code}` | 200 | 404 |
| PATCH | `/api/v1/links/{code}` (`{"expiresInSeconds":N}` or `{"neverExpires":true}`) | 200 + updated link | 400 · 404 · 410 deactivated · 429 |
| GET | `/api/v1/links/{code}/analytics` | 200 | 404 |
| GET | `/api/v1/links?page=&size=` | 200 (size capped at 100) | – |
| DELETE | `/api/v1/links/{code}` | 204 (idempotent) | 404 · 429 |
| GET | `/actuator/health` | 200 | – |
| GET | `/actuator/metrics/shortener.*` | 200 (`links.created`, `code.collisions`, `analytics.failures`) | – |
| GET | `/openapi.yaml` | 200 (the contract in `docs/openapi.yaml`) | – |

All errors are RFC 7807 `application/problem+json` with a stable `code` (e.g. `LINK_NOT_FOUND`, `ALIAS_UNAVAILABLE`, `RATE_LIMITED`) and a `requestId`. Every response carries `X-Request-Id` (a safe caller-supplied value is echoed; anything else is replaced) and the same id appears in the logs. `/actuator/health/readiness` includes the database; `/actuator/health/liveness` does not.

## Configuration (`src/main/resources/application.yml`)

| Key | Default | Meaning |
|---|---|---|
| `app.base-url` | `http://localhost:8080` | prefix for returned `shortUrl` |
| `app.block-private-networks` | `true` | SSRF guard on submitted URLs |
| `app.code-length` | `7` | Base62 length (62⁷ ≈ 3.5·10¹² codes) |
| `app.max-code-attempts` | `5` | bounded retry on code collision |
| `app.visitor-salt` | dev placeholder | salt for hashed visitor identity |
| `app.rate-limit.capacity` / `refill-per-second` | `30` / `0.5` | burst / sustained writes per client |
| `app.rate-limit.trust-forwarded-for` | `false` | only enable behind a proxy that overwrites `X-Forwarded-For` |

## Project layout

```
src/main/java/com/example/shortener
├── api/       controllers, DTO records, RFC 7807 exception handler
├── config/    typed properties, bean wiring, rate-limit filter, client-IP resolver
├── domain/    JPA entities (ShortLink, ClickEvent)
├── repo/      Spring Data repositories + query projections
├── service/   LinkService, AnalyticsService + framework-free CodeGenerator, UrlValidator, TokenBucketLimiter
└── error/     domain exceptions
docs/          requirements & plan, architecture, scenarios, AI usage log, testing & risks, summary, OpenAPI
```

## Documentation map (maps to the assignment deliverables)

| Deliverable | Document |
|---|---|
| Requirement understanding & task decomposition | [`docs/01-REQUIREMENTS_AND_PLAN.md`](docs/01-REQUIREMENTS_AND_PLAN.md) |
| Architecture overview | [`docs/02-ARCHITECTURE.md`](docs/02-ARCHITECTURE.md) |
| Greenfield / brownfield / ambiguous scenarios | [`docs/03-SCENARIOS.md`](docs/03-SCENARIOS.md) |
| AI-assisted execution, traceability, quality gates, sign-off | [`docs/04-AI_USAGE_LOG.md`](docs/04-AI_USAGE_LOG.md) |
| Testing approach, risks, limitations, trade-offs | [`docs/05-TESTING_AND_RISKS.md`](docs/05-TESTING_AND_RISKS.md) |
| Final engineering summary | [`docs/06-ENGINEERING_SUMMARY.md`](docs/06-ENGINEERING_SUMMARY.md) |
| API / schema definitions | [`docs/openapi.yaml`](docs/openapi.yaml) · entities in `domain/` |
| Decision records | [`docs/adr/`](docs/adr) (ADR-001 … 010) |
| Submission checklist & comparison with reference repos | [`docs/SUBMISSION.md`](docs/SUBMISSION.md) |
