# 01 – Requirement Understanding & Task Decomposition

## 1. Raw requirement
> Build a URL shortener from scratch with core APIs, analytics, and reliability features.

## 2. Normalized engineering problem
Provide an HTTP service that:

| # | Capability | Normalized statement |
|---|-----------|----------------------|
| F1 | Shorten | Accept a long http(s) URL, return a unique short code + short URL |
| F2 | Redirect | `GET /{code}` redirects to the original URL; unknown → 404; expired/deactivated → 410 |
| F3 | Custom alias | Optional caller-chosen code; collision → 409 |
| F4 | Expiry | Optional TTL; expired links stop redirecting |
| F5 | Analytics | Per-link total clicks, unique visitors, clicks per day, top referrers |
| F6 | Management | Read metadata, list (paged), deactivate |
| R1 | Reliability | Bounded retry on code collision; analytics failure never breaks a redirect; graceful shutdown; health endpoint |
| R2 | Abuse control | Per-client rate limit on write APIs |
| S1 | Security | Reject non-http(s), credentials-in-URL, private/loopback/link-local targets (SSRF guard), unguessable codes, no raw IP storage |

## 3. Ambiguities found → decision (assumption log)

| ID | Ambiguity | Options considered | Decision & rationale |
|----|-----------|--------------------|----------------------|
| A1 | Should the same long URL always map to the same code? | (a) dedupe, (b) always new | **(b) always new.** Dedupe leaks that someone else shortened the URL, and conflicts with per-link TTL/analytics. |
| A2 | Redirect status | 301 vs 302 | **302 + `Cache-Control: no-store`.** 301 is cached by browsers, which would bypass analytics and make expiry/deactivation unenforceable. |
| A3 | "Unique visitor" definition | raw IP, cookie, hash | **Salted SHA-256 of IP + User-Agent**, truncated. No raw IP stored (privacy). Approximate by design. |
| A4 | "Reliability" | HA, retries, graceful degradation | Scope to what a single node can prove: bounded collision retry, isolated analytics failures, graceful shutdown, health probe. HA/replication documented as out of scope. |
| A5 | Persistence | in-memory vs DB | **H2** (in-memory default, file via `file` profile). Schema/queries are portable JPA so Postgres is a config change. |
| A6 | Code format | sequential vs random | **Random Base62, 7 chars (~3.5e12)** from `SecureRandom`. Sequential IDs are enumerable. |
| A7 | Auth / multi-tenancy | none vs API keys | **Out of scope**; noted as top follow-up. Rate limiting is the only abuse control. |
| A8 | Rate-limit identity | remote addr vs `X-Forwarded-For` | **Remote addr by default**; XFF is client-controlled and spoofable, so trusting it is opt-in. |

## 4. Task decomposition (with dependencies)

```
T1 requirements/ambiguity log ──┐
                                ├─> T2 domain + schema ─> T3 code generator ─┐
                                │                                           ├─> T5 LinkService ─> T6 REST controllers ─┐
                                └─> T4 URL validator (SSRF guard) ──────────┘                                          │
T7 error model (RFC 7807) ─────────────────────────────────────────────────────────────> T6                              │
T8 analytics (record + queries) ─> T5/T6                                                                                 │
T9 rate limiter filter ──────────────────────────────────────────────────────────────────────────────────────────────────┤
T10 unit tests (T3,T4,T9,T5) ─> T11 integration tests (T6,T8) ─> T12 quality gates ─> T13 docs & engineering summary <────┘
```

| Task | Intent | Acceptance criteria | Depends on |
|------|--------|--------------------|-----------|
| T2 | Persist links and clicks | Unique constraint on `code`; `click_events` indexed by `(code, click_day)` | T1 |
| T3 | Unguessable codes | Only `[0-9A-Za-z]`, configured length, injectable RNG for tests | T1 |
| T4 | Safe URL intake | Rejects non-http(s), userinfo, >2048 chars, loopback/private/link-local/CGNAT/ULA hosts | T1 |
| T5 | Core logic | Collision retries ≤ N then 503; alias conflict 409; expiry checked against injected `Clock` | T2–T4 |
| T6 | API | See `docs/openapi.yaml`; all errors `application/problem+json` | T5, T7, T8 |
| T8 | Analytics | Click write failures logged, not propagated | T2 |
| T9 | Abuse control | Token bucket per client; 429 + `Retry-After`; bounded memory | T1 |
| T10–T11 | Verification | Collision, expiry, concurrency (same alias race), SSRF matrix, rate limit all covered | T3–T9 |
| T12 | Quality gates | `mvn verify` green; JaCoCo report; dependency/secret review | all |

## 5. Execution approach (AI-assisted, engineer-led)
Each task is given to the AI with: **intent, constraints, acceptance criteria, technical context**. Output is reviewed by the engineer, then validated by tests. See `docs/AI_USAGE_LOG.md` for the traceability record and the sign-off checklist.
