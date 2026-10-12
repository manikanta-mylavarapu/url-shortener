# ADR-004: Analytics is best-effort and isolated from the redirect (write-behind queue deferred)

**Status:** accepted · **Deferred item:** asynchronous write-behind queue

**Context.** The product is the redirect. Click analytics is valuable but secondary.

**Decision.** Record the click synchronously in its own transaction (insert event + atomic `click_count = click_count + 1`). If that fails, log it, increment the `shortener.analytics.failures` counter, and still serve the redirect.

**Consequences.**
- (+) An analytics outage can never take redirects down; failures are observable via `/actuator/metrics/shortener.analytics.failures`.
- (−) A failed click is lost (undercounting). Redirect latency includes one DB write.
- (−) `click_events` grows without bound; needs retention/rollups at scale.

**Deferred.** A bounded in-memory write-behind queue with batch inserts would remove the DB write from the redirect path and absorb DB blips. It was **not** built because it adds failure modes (lost events on crash, back-pressure policy, shutdown draining) that deserve their own design and tests; the counter makes the current loss visible so the need can be measured first.

**Verified by.** `LinkServiceTest.analyticsFailureNeverBreaksARedirect_butIsCounted`, `concurrentRedirectsDoNotLoseClicks`.
