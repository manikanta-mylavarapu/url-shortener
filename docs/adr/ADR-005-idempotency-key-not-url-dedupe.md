# ADR-005: Retry safety via `Idempotency-Key`, not by de-duplicating identical URLs

**Status:** accepted · **Added after comparing with the reference repositories** (one re-uses identical anonymous links, the other supports `Idempotency-Key`).

**Context.** Clients retry on timeouts. Without protection a retried `POST` creates a second link, and the client may never learn which one it "owns".

**Decision.** Support an optional `Idempotency-Key` header (1–64 chars `[A-Za-z0-9._:-]`).
- First request: creates the link → `201`.
- Same key + same request (URL, alias, TTL fingerprint): returns the original link → `200` + `Idempotent-Replayed: true`.
- Same key + different request: `422`.
- Concurrent duplicates: the unique constraint on the key picks one winner; losers re-read and replay (ADR-002).

Identical URLs **without** a key still produce distinct links.

**Consequences.**
- (+) Safe retries without changing semantics for clients that don't send a key; no leak that a URL was shortened before; per-link TTL and analytics stay independent.
- (−) Keys are stored forever on the link row (no expiry window); a retention job is needed for strict hygiene.
- (−) Only protects creation, not delete (delete is already idempotent).

**Verified by.** `LinkServiceTest` idempotency cases, `retryWithSameIdempotencyKeyReturnsTheOriginalLinkAndCreatesNoDuplicate`, `reusingAKeyForADifferentRequestIs422`, `concurrentRequestsWithTheSameKeyAllResolveToOneLink`.
