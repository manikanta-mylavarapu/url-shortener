# ADR-001: Random Base62 short codes from SecureRandom

**Status:** accepted · **Owner:** engineer (AI-drafted, human-reviewed)

**Context.** Short codes identify links publicly. They must be unique, short, and not enumerable.

**Decision.** 7 characters from `[0-9A-Za-z]`, drawn from `SecureRandom` (62⁷ ≈ 3.5·10¹² codes). Collisions are resolved by a bounded retry (`app.max-code-attempts`, default 5) then HTTP 503 with `Retry-After`.

**Consequences.**
- (+) Not guessable or enumerable; no coordination between instances; no information about creation order.
- (−) Collisions are possible and must be handled; probability rises with table size (birthday bound), so lengthen the code if the table nears ~10⁹ links.
- (−) Not human-memorable (custom aliases cover that need).

**Alternatives rejected.** Sequential ID → Base62 (enumerable, leaks volume). Hash of the URL (deterministic dedupe leaks that a URL was shortened before and conflicts with per-link TTL/analytics).

**Verified by.** `CodeGeneratorTest`, `LinkServiceTest.createRetriesOnCodeCollisionThenSucceeds`, `createGivesUpAfterBoundedAttempts`.
