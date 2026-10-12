# ADR-010: Changing a link's expiry (PATCH) - semantics

**Status:** accepted

**Context.** Brownfield requirement BF-2: operators must be able to extend or remove a link's expiry on a running system.

**Decision.**
- `PATCH /api/v1/links/{code}` with exactly one of `expiresInSeconds` (expiry = now + N) or `neverExpires: true`.
- Expired links may be extended (they become active again); deactivated links answer 410 and cannot be revived through this call.
- Implemented as a targeted `UPDATE ... WHERE code = ? AND active = true` so it can neither rewrite `click_count` (BF-1) nor
  resurrect a link that was deactivated concurrently (0 rows updated -> 410).
- Time comes from the injected `Clock`; bounds match create (60 s .. 1 year).

**Alternatives.** Absolute `expiresAt` timestamp (idempotent but clients must handle clock skew; rejected); JSON `null` to mean "no
expiry" (indistinguishable from a missing field in PATCH; rejected); extend relative to the old expiry (depends on state the client
may not know; rejected); allow reviving deactivated links (needs its own audited operation; rejected).

**Consequences.** (+) additive, no migration, race-safe. (-) repeating the same request moves the expiry again; no authentication, so
any caller can change any link (documented limitation); a future redirect cache must invalidate on PATCH.
