# ADR-007: Request correlation, stable error codes, DB-aware readiness, versioned migrations

**Status:** accepted · supersedes the `ddl-auto: update` caveat in ADR/architecture D10

**Context.** Reference implementations reviewed for this exercise (see `docs/SUBMISSION.md`) show four operability
practices a production service needs and this one lacked: correlating a failing request with its log lines, error
bodies clients can branch on, a readiness signal that reflects the database, and schema changes under version control.

**Decision.**
1. **`X-Request-Id`** (`RequestIdFilter`, runs first): honour a caller id only if `[A-Za-z0-9._-]{1,64}`, otherwise generate a
   UUID; echo it in the response, put it in the MDC (log pattern `[%X{requestId}]`) and in every error body.
   Unsafe ids are *replaced*, not sanitised, so log forging via CR/LF or markup is impossible.
2. **`code` member in every problem+json** (`error/ErrorCode`): stable identifiers (`LINK_NOT_FOUND`, `ALIAS_UNAVAILABLE`,
   `RATE_LIMITED`, ...). Framework errors (malformed JSON, 405) get `HTTP_ERROR` so the envelope is uniform.
   Status alone is ambiguous (400 = bad URL, alias, key or body) and titles may be reworded.
3. **Readiness = app state + database** via the actuator health group `readiness: readinessState,db` (configuration only,
   no custom indicator to maintain). Liveness stays independent of the DB so a DB outage does not restart-loop the pods.
4. **Flyway** owns the schema under the `file` (persistent) profile with Hibernate in `validate`; the in-memory default keeps
   `ddl-auto: update` and Flyway disabled so a fresh clone and the whole test suite do not depend on migration SQL.
   `FlywayMigrationIntegrationTest` builds the schema *only* from `db/migration` with `validate` and drives the full API, so entity
   and migration drift fails CI. `V1` is idempotent (`IF NOT EXISTS`) and the profile baselines at 0 so a `./data` directory
   created by an earlier `update` run is adopted rather than rejected.

**Alternatives.** Custom `HealthIndicator` (more code, same result); Flyway for every profile (couples all tests to SQL I could not run
in the authoring environment); sanitising caller ids (still lets through attacker-chosen content); Liquibase (heavier for one table pair).

**Consequences.**
- (+) Every log line and error is traceable to one request; clients get a stable contract; schema changes are reviewable SQL.
- (✔) Verified: `FlywayMigrationIntegrationTest` passed on the engineer's machine, so Hibernate's `validate` accepts the migrated schema.
- (−) The test covers H2 only; Postgres needs `flyway-database-postgresql` and its own check.
- (−) Flyway is on the classpath even when disabled (small startup cost, one extra dependency).
- Not done on purpose: Postgres driver/dialect module (add `flyway-database-postgresql` when moving off H2).
