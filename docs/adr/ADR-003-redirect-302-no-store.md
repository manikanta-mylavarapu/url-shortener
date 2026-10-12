# ADR-003: Redirect with 302 and `Cache-Control: no-store`

**Status:** accepted

**Context.** Redirects are the hot path. Browsers and CDNs cache 301/308 responses indefinitely.

**Decision.** Always answer `302 Found` with `Cache-Control: no-store`.

**Consequences.**
- (+) Every visit reaches the service, so clicks are counted, and expiry/deactivation take effect immediately (410).
- (−) Higher origin load and latency than a cacheable redirect. Mitigation path: an in-process cache for `code → URL` with a TTL no longer than the link's remaining life, invalidated on deactivate (not implemented).

**Alternative rejected.** 301: faster, but silently breaks analytics and makes a link impossible to switch off once a client has cached it.

**Verified by.** `redirectIs302WithNoStoreAndCountsTheClick`, `linkExpiresAtTheConfiguredInstant`.
