# 03 – Scenarios: Greenfield, Brownfield, Ambiguous

Each scenario shows **decomposition → execution → validation → engineer decision points**.

> Honesty note on the brownfield scenario: the repository is delivered in its final state. The brownfield change set
> (§2) is described as a delta *on top of* the greenfield core. If you want the history to show it, stage it as two
> commits (greenfield core, then the brownfield delta); the file lists below say exactly which files belong to each.

---

## 1. Greenfield – "Build a URL shortener with core APIs"

**Intent:** from an empty repo to a runnable service with shorten, redirect, manage, analytics.

**Decomposition** (see dependency graph in `01-REQUIREMENTS_AND_PLAN.md`)

| Step | Task | Output files |
|---|---|---|
| G1 | Normalize requirement, log ambiguities (A1–A8) | `docs/01-…` |
| G2 | Domain + schema | `domain/ShortLink`, `ClickEvent`, `repo/*` |
| G3 | Unguessable code generation | `service/CodeGenerator` |
| G4 | Safe URL intake | `service/UrlValidator` |
| G5 | Use cases | `service/LinkService`, `AnalyticsService` |
| G6 | REST + error model | `api/*` |
| G7 | Abuse control | `service/TokenBucketLimiter`, `config/RateLimitFilter` |
| G8 | Tests + docs | `src/test/**`, `docs/**` |

**AI-assisted execution:** each step was specified as *intent / constraints / acceptance criteria / context*
(templates in `04-AI_USAGE_LOG.md`). Example for G5:

> *Intent:* create a short link, with custom alias support. *Constraints:* uniqueness must hold under concurrency and
> across instances; no class-level `@Transactional`; time via injected `Clock`; bounded retries. *Acceptance:* 16-thread
> same-alias race yields exactly one 201; collisions retried ≤ N then 503; invalid URL never reaches the DB.

**Validation:** unit tests per class, MockMvc integration tests for every status code (201/302/400/404/409/410/429),
concurrency tests, SSRF matrix, `mvn verify`.

**Engineer decision points:** redirect status (302 vs 301), code length/alphabet, "always new link" vs dedupe, H2 for the prototype.

---

## 2. Brownfield – a real change to a shipped, tested baseline

**Baseline (the "existing system"):** the service as delivered at v1.0.0 and verified before any change below: `mvn verify`
green (**105 tests**, 93.5 % instruction / 83.5 % branch coverage), smoke test **33/33** against the running app
(`docs/evidence/smoke-transcript.txt`, run 2026-10-12). Everything in this section was done *on top of that* and re-verified.

Two brownfield tasks, one defect and one enhancement, both discovered or requested after the baseline existed.

### BF-1 Bug fix – "clicks vanish when a link is deactivated" (found in the full-codebase review, ADR-008 #1)

| Step | What was done |
|---|---|
| Symptom (reasoned, then reproduced as a failing expectation) | A click counted at the same moment an operator deactivates a link can be erased from `click_count`. |
| Root cause | `LinkService.deactivate` did *load entity → `deactivate()` → `save(entity)`*. Hibernate rewrites **every** column on `save`, including `click_count`, with the value read earlier. The atomic SQL increment (D6) is silently undone by a read-modify-write on the same row. |
| Impact analysis | Data flow *redirect → analytics → `incrementClicks`* shares the `short_links` row with *DELETE → deactivate*. Any other writer using entity `save` has the same defect class: grep showed `deactivate` was the only one. |
| Fix | Targeted `UPDATE ... SET active=false WHERE code=? AND active=true` (`deactivateByCode`); idempotent in SQL. |
| Regression guard | `LinkServiceTest.deactivateUsesAnAtomicUpdate...` (fails if anyone reintroduces `save`), `RedirectIntegrationTest` (count survives deactivation). |
| Rollback | Revert one repository method and one service method; no schema change. |

### BF-2 Enhancement – "operators must be able to change a link's expiry" (`PATCH /api/v1/links/{code}`)

**Requirement as received:** "Operators need to extend a link before it expires, and sometimes remove the expiry."

**Ambiguities → decisions** (this is requirement normalization on a live system)

| Question | Decision | Why |
|---|---|---|
| New expiry relative to *now* or to the old expiry? | Relative to **now** (`expiresInSeconds`), same 60 s .. 1 year bounds as create | Works the same for active and expired links; no hidden dependence on stale state |
| How to remove the expiry? | Explicit `neverExpires: true`; exactly one of the two fields is required (400 otherwise) | JSON `null` vs "absent" is ambiguous in a PATCH; an explicit flag cannot be sent by accident |
| Can an **expired** link be extended? | Yes - it becomes active again | The common operator need ("we forgot to extend it") |
| Can a **deactivated** link be revived? | No - 410 | Deactivation is a deliberate operator decision; reviving needs its own, audited endpoint |
| Is it retry-idempotent? | No in time (repeating moves the expiry forward again); documented | Making it so would need an absolute timestamp or an idempotency key; rejected as over-engineering here |

**Codebase reasoning - impact analysis**

| Area | Impact | Change |
|---|---|---|
| `ShortLink` / schema | None: `expires_at` is already nullable | **No migration needed** (checked against Flyway `V1`) |
| `ShortLinkRepository` | Must not rewrite `click_count` (see BF-1) | New targeted `updateExpiry` UPDATE, guarded by `active = true` |
| `LinkService` | New use case; reads expiry through the injected `Clock` | `updateExpiry(code, ttl)`: 404 unknown, 410 deactivated, 410 if deactivated between read and write |
| `LinkController` / DTO | New route + validated body | `PATCH /{code}`, `UpdateLinkRequest` with a class-level "exactly one" validation |
| `RateLimitFilter` | Another state-changing method | Already covered (all POST/PUT/PATCH/DELETE) - no change, test confirms |
| Redirect path | **Data-flow risk:** expiry is read on every redirect | No cache exists, so the change is visible immediately; a future cache must invalidate on PATCH (noted in architecture §6) |
| Analytics | Must be unaffected | Counters untouched by the UPDATE (asserted: clicks before/after PATCH) |
| Idempotency fingerprint | Covers create inputs only | Unaffected |
| OpenAPI / README / smoke | Contract grows | Additive: new operation + schema; smoke section 10 |

**Decomposition**

```
B1 decide semantics (table above) ─► B2 repo UPDATE ─► B3 service rule ─► B4 controller + DTO + validation ─┐
                                                                                                        ├─► B6 OpenAPI/README/smoke ─► B7 docs + log
                                       B5 tests: unit (service) ∥ integration (HTTP, test clock) ◄──────────┘
```

**Execution & validation:** service logic executed with a `javac` harness against an in-memory repository (extend an expired link
from "now", clear expiry, deactivated → gone, unknown → not found); `LinkServiceTest` (5 new cases) and
`LinkApiIntegrationTest` (4 new cases: revive-from-now keeps clicks, `neverExpires`, deactivated stays gone, invalid bodies/unknown
code) run under `mvn verify`; smoke section 10 (6 checks) against the running service.

**Risk / rollback:** additive endpoint, no schema change; rollback = remove the route. Residual: any operator can extend any
link (no auth - documented limitation A7), and an extended expired link resurrects its short URL, which is intentional.

---

## 3. Ambiguous – "Make the short links secure and reliable"

**Problem:** the requirement has no measurable meaning. Treating it as an engineering problem means *forcing the
interpretations into explicit, testable decisions* – and showing which were deliberately **not** done.

**Step 1 – Interpretations & clarifying questions**

| Question that would normally go to the product owner | Options | Decision (assumption) |
|---|---|---|
| "Secure" against what? | guessing, abuse, SSRF/phishing, data leakage, auth | Four threats in scope: **enumeration, write abuse, malicious targets, PII leakage**. Authentication/ownership out of scope (A7). |
| "Reliable" – what failure matters most? | availability, correctness under load, durability | **Correctness under concurrency + graceful degradation** on a single node; HA/durability documented as out of scope (A4). |
| Acceptable false positives of the SSRF guard? | strict vs lenient | Strict: reject unresolvable hosts and any host with a non-public address; configurable switch. |

**Step 2 – Decomposition into testable controls**

| Control | Test (acceptance) |
|---|---|
| Unguessable codes (SecureRandom, 62⁷) | `CodeGeneratorTest` – alphabet, length, 20 000 distinct draws |
| Bounded collision retry | `LinkServiceTest.createGivesUpAfterBoundedAttempts` (3 attempts → 503) |
| Uniqueness under race | `concurrentCreatesOfTheSameAliasYieldExactlyOneWinner` |
| SSRF guard | `UrlValidatorTest` – 11 internal targets incl. metadata IP, IPv6 ULA, mixed A records |
| Write abuse | `RateLimitIntegrationTest` + `TokenBucketLimiterTest` (burst, refill, cap, bounded memory) |
| Spoofed `X-Forwarded-For` | rotating header does not mint fresh buckets |
| Privacy | analytics response never contains IP or referrer query/path |
| Failure isolation | `analyticsFailureNeverBreaksARedirect` |
| No info leakage | `errorResponsesNeverLeakStackTraces` |

**Step 3 – Explicit non-goals (stated so the reviewer can challenge them):** authentication, per-user ownership, CAPTCHA,
malware/phishing reputation lookups, multi-node rate limiting, HA/replication.

**Engineer decision points:** fail-open vs fail-closed when the rate-limit table is saturated (chose fail-open, see
`05-TESTING_AND_RISKS.md`), strictness of the SSRF guard, trusting `X-Forwarded-For` (default no).

---

## 4. Task cards (intent / constraints / acceptance / context)

These are the task specifications handed to the AI, written the way the brief asks. They are specifications, not
verbatim chat transcripts.

### Card B3 (initial build) – "A link must stop redirecting when expired or deactivated"
- **Intent:** serve 410 for expired/deactivated links without changing behaviour for existing links.
- **Context:** `ShortLink` entity, `LinkService.resolveForRedirect`, `GlobalExceptionHandler`, Spring Data JPA on H2.
- **Constraints:** new columns nullable/defaulted (no data migration); time only via injected `Clock`; expired hits must not be counted; no new dependencies.
- **Acceptance:** 59 s → 302, 60 s → 410 (boundary test); deactivate is idempotent; metadata shows `EXPIRED`/`DEACTIVATED`; analytics unchanged by 410s.
- **Review focus (human):** boundary semantics (`!now.isBefore(expiresAt)`), error mapping, absence of side effects on the 410 path.

### Card A2 (ambiguous) – "URL intake must not be usable to reach internal networks"
- **Intent:** reject submitted URLs that point at non-public destinations.
- **Context:** `UrlValidator`, JDK `InetAddress` classification helpers, cloud metadata endpoint risk.
- **Constraints:** injectable resolver (no real DNS in tests); validator has no Spring dependency; reject if *any* resolved address is non-public; configurable off-switch for tests only.
- **Acceptance:** loopback, RFC1918, link-local (169.254.169.254), CGNAT, multicast, unspecified, IPv6 ULA/link-local all rejected; public IPv4/IPv6 accepted; unresolvable host rejected when the guard is on.
- **Review focus (human):** JDK helpers miss CGNAT and IPv6 ULA (added explicitly); DNS-rebinding remains a documented residual risk.

### Card G9 (added after reference review) – "Creating a link must be safe to retry"
- **Intent:** a client retrying `POST /api/v1/links` must not create duplicates.
- **Context:** `LinkService`, `ShortLink`, unique constraints, ADR-002 and ADR-005.
- **Constraints:** optional header; behaviour without the header unchanged; concurrency resolved by the DB constraint; no class-level `@Transactional`.
- **Acceptance:** same key+request → 200 replay, same code; same key+different request → 422; 16 concurrent same-key requests → exactly one row and one creation; malformed key → 400.
- **Review focus (human):** fingerprint covers url/alias/ttl; key retention (keys are never expired – documented).

### Card BF-2 (brownfield) – "Operators can change a link's expiry"
- **Intent:** let an operator extend, shorten or remove a link's expiry without touching its code, clicks or analytics.
- **Context:** `ShortLink.expiresAt` (nullable), `ShortLinkRepository`, `LinkService`, `LinkController`, BF-1 (never rewrite `click_count` via entity save), ADR-010.
- **Constraints:** no schema change; time via the injected `Clock`; targeted UPDATE guarded by `active = true`; deactivated links stay dead; explicit field for "remove expiry"; additive API change only.
- **Acceptance:** extend-from-now on an expired link revives it and keeps its click count; `neverExpires` clears the expiry; deactivated → 410; `{}`, contradictory, out-of-range and malformed bodies → 400 `VALIDATION_FAILED`; unknown code → 404.
- **Review focus (human):** the semantics table (especially "expired may be revived, deactivated may not"), absence of lost updates, the 410-on-race path.
