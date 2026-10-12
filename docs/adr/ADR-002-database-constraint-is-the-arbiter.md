# ADR-002: The database unique constraint decides uniqueness (no check-then-insert, no class-level @Transactional)

**Status:** accepted

**Context.** Two requests can pick the same alias, the same random code, or carry the same Idempotency-Key at the same moment. An application-level "does it exist?" check followed by an insert is a race.

**Decision.** `short_links.code` and `short_links.idempotency_key` carry unique constraints. The service inserts and treats `DataIntegrityViolationException` as the signal: retry with a new code, report 409 for an alias, or re-read and replay for an Idempotency-Key. `LinkService` is **not** `@Transactional` at class level: a constraint violation inside an outer transaction marks it rollback-only, so a retry could never succeed. Each repository call is its own short transaction. An `existsByCode` pre-check is kept only as a fast path for friendlier errors.

**Consequences.**
- (+) Correct under concurrency and across multiple app instances, with no distributed lock.
- (−) Uses the exception path under contention (cheap at this scale).
- (−) Relies on the DB reporting violations as `DataIntegrityViolationException` (true for JPA on H2/Postgres).

**Verified by.** `concurrentCreatesOfTheSameAliasYieldExactlyOneWinner`, `concurrentRequestsWithTheSameKeyAllResolveToOneLink`, `LinkServiceTest.aliasRaceLostToConcurrentRequestIsReportedAsConflict`, `losingARaceOnTheKeyReplaysTheWinnersResult`.
