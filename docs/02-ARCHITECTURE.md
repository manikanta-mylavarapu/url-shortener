# 02 – Architecture Overview

## 1. Components

```
                         ┌──────────────────────────── Spring Boot app (single node) ───────────────────────────┐
 client ── HTTP ──►      │  RateLimitFilter ──► LinkController   (/api/v1/links…)  ──┐                         │
                         │   (POST/PUT/PATCH/     RedirectController (GET /{code})  ──┤                         │
                         │    DELETE)                                                 ▼                         │
                         │                                                       LinkService ──► UrlValidator   │
                         │   GlobalExceptionHandler (RFC 7807)                       │  │   ──► CodeGenerator  │
                         │                                                           │  └──► AnalyticsService  │
                         │                                                           ▼             │            │
                         │                                                  ShortLinkRepository    ▼            │
                         │                                                           │      ClickEventRepository │
                         └───────────────────────────────────────────────────────────┼─────────────┼────────────┘
                                                                                     ▼             ▼
                                                                         H2 (mem | file)  ── tables: short_links, click_events
```

| Layer | Responsibility | Notes |
|---|---|---|
| `api` | HTTP mapping, DTO validation, error translation | Thin; no business rules |
| `config` | Typed properties, bean wiring, rate-limit filter, client-IP policy | Core classes stay framework-free and are wired here |
| `service` | Use cases + pure logic | `CodeGenerator`, `UrlValidator`, `TokenBucketLimiter` have **no Spring dependency** → fast, hermetic unit tests |
| `repo` / `domain` | Persistence | DB unique constraint on `code` is the source of truth for uniqueness |

## 2. Control flow

**Shorten** `POST /api/v1/links`
0. `RequestIdFilter` – assigns/validates `X-Request-Id` (response header, log MDC, error body).
1. `RateLimitFilter` – token bucket per client IP on every state-changing method (path-independent) → 429 + `Retry-After` when empty.
2. Bean Validation on the DTO (shape, alias pattern, TTL bounds) → 400 with per-field `errors`.
   - Optional `Idempotency-Key`: same key + same request (URL/alias/TTL fingerprint) → replay the original link (200, `Idempotent-Replayed: true`); same key + different request → 422; concurrent duplicates are resolved by the unique constraint on the key (loser re-reads and replays). See ADR-005.
3. `UrlValidator` – scheme, length, no userinfo, host resolves only to public addresses → 400.
4. `LinkService.create`
   - alias given: format → reserved-word check → `existsByCode` fast path → `saveAndFlush`; a unique-violation (lost race) maps to 409.
   - otherwise: generate Base62 code → `saveAndFlush`; on collision retry up to `max-code-attempts`, then 503.
5. 201 + `Location` + `LinkResponse`.

**Redirect** `GET /{code}` (HEAD behaves identically but records no click)
1. Lookup → 404. `statusAt(clock.instant())` ≠ ACTIVE → 410 (expired link is *not* counted).
2. `AnalyticsService.recordClick` in its own transaction: insert `ClickEvent` + atomic SQL `click_count = click_count + 1`.
   Failure is logged and swallowed – **analytics never breaks a redirect**.
3. `302 Location` + `Cache-Control: no-store`.

**Analytics** `GET /api/v1/links/{code}/analytics`: total (denormalized counter), unique visitors (`count(distinct visitor_hash)`),
per-UTC-day counts (`GROUP BY click_day`), top 5 referrer hosts.

## 3. Data model

```
short_links( id PK, code UNIQUE(32), original_url(2048), created_at, expires_at NULL, active, click_count,
             idempotency_key UNIQUE NULL(64), request_fingerprint NULL(64) )
click_events( id PK, code, clicked_at, click_day, referrer_host, visitor_hash(32) )
   indexes: (code, click_day), (code, visitor_hash)
```

## 4. Key decisions (ADR-style)

| # | Decision | Alternatives | Why |
|---|---|---|---|
| D1 | Random Base62 from `SecureRandom`, 7 chars | Sequential ID + Base62; hash of URL | Sequential IDs are enumerable; hashes leak/dedupe. 3.5·10¹² space keeps collisions rare; collisions handled by bounded retry. |
| D2 | DB unique constraint is the arbiter, not "check then insert" | Pessimistic locks; in-memory set | Correct under concurrency and across multiple app instances. Covered by a 16-thread race test. |
| D3 | `LinkService` is **not** transactional at class level | `@Transactional` everywhere | A constraint violation inside an outer transaction marks it rollback-only, making retry impossible. Each repo call is its own short transaction. |
| D4 | 302 + `no-store` | 301 | 301 is cached by browsers/CDNs → analytics blind spots, expiry/deactivation unenforceable. |
| D5 | Analytics is best-effort and isolated; HEAD requests are not counted | Fail the redirect if logging fails | Redirect availability is the product; analytics is secondary. Trade-off: a click can be lost if the analytics write fails. |
| D6 | Atomic SQL counter increment | Read-modify-write on the entity | No lost updates under concurrent clicks (40-click parallel test). |
| D7 | Visitor identity = salted SHA-256(IP\|UA), truncated; referrer = host only | Store raw IP / full referrer | Privacy by design – no PII, no tokens from referrer query strings. Trade-off: unique visitors is approximate. |
| D8 | In-process token bucket on every state-changing request (path-independent), eviction scan throttled to 1/s | Redis; gateway-only | Zero infrastructure for the prototype; bounded memory; documented as single-node. |
| D9 | `Clock` injected everywhere time matters | `Instant.now()` | Deterministic expiry/rate-limit/day-bucket tests without sleeping. |
| D10 | H2 + JPA; `ddl-auto: update` in memory, **Flyway + `validate` under the `file` profile** | Flyway everywhere; Postgres now | In-memory stays zero-setup; persistent data is schema-controlled (ADR-007). Production: Postgres + `flyway-database-postgresql`. |

## 4a. Security controls

| Threat | Control |
|---|---|
| SSRF / internal network probing via submitted URL | Resolve host, reject loopback, private, link-local (incl. 169.254.169.254), CGNAT, multicast, unspecified, IPv6 ULA; reject if *any* A/AAAA is non-public |
| Open-redirect abuse to `javascript:`/`file:` | Only `http`/`https` accepted |
| Phishing via `user:pass@host` | Userinfo rejected |
| Enumeration of links | Unpredictable random codes |
| Alias squatting of app paths | Reserved words (`api`, `actuator`, …) |
| Write-API abuse | Per-client token bucket, bounded memory, spoof-resistant client identity |
| Information leakage | RFC 7807 errors, no stack traces, generic 500; no raw IP stored |
| Log/response injection | Control characters and whitespace rejected in URLs |

## 5. Tooling & execution approach
- **Build/test:** Maven, JUnit 5, AssertJ, Mockito, MockMvc + real H2, JaCoCo.
- **AI-assisted loop per task:** intent/constraints/acceptance → generate → engineer review → tests → quality gates → log (see `04-AI_USAGE_LOG.md`).
- **Quality gates:** `mvn verify` green (incl. performance budget) · JaCoCo report reviewed · Checkstyle + SpotBugs (both in `mvn verify`) · dependency scan (`-Psecurity` in CI, Dependabot) · SSRF matrix · concurrency tests · secret review · smoke test · human sign-off on high-impact items (ADR-009).

## 6. Scaling path (not implemented, by design)
1. Postgres (Flyway migrations already in place); read replicas for redirects.
2. Cache `code → URL` (Caffeine locally, Redis shared) with short TTL ≤ min(expiry); invalidate on deactivate **and on expiry PATCH** (ADR-010).
3. Click events → append-only queue (Kafka/SQS) with async consumers and daily rollups; keep `click_count` as a cache.
4. Distributed rate limiting at the gateway / Redis.
5. Auth + per-tenant ownership of links.

## 7. Observability & contract
- Micrometer counters via `/actuator/metrics`: `shortener.links.created`, `shortener.code.collisions`, `shortener.analytics.failures` (the last one is how best-effort analytics stays visible – ADR-004).
- Health/readiness probes via actuator; readiness includes the database (`readinessState,db`), liveness does not; graceful shutdown (20 s).
- Every request gets an `X-Request-Id` (response header, log MDC, error body `requestId`); every error body has a stable `code` (`error/ErrorCode`) - ADR-007.
- The API contract `docs/openapi.yaml` is copied onto the classpath at build time and served at `/openapi.yaml` (single source of truth).
- Decision records: `docs/adr/ADR-001 … ADR-010` expand the decision table in §4.
