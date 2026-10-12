# ADR-009: Lint, static analysis, performance and dependency scanning as build gates

**Status:** accepted (revised: the first version made lint opt-in; a gate that only runs on request is not a gate)

**Context.** The brief asks for quality gates covering "analysis, linting, tests, security, performance". Tests and security
controls existed; the other gates were documented as missing.

**Decision.** The gates that can run anywhere run in every `mvn verify`, locally and in CI:
- **Lint:** Checkstyle (`config/quality/checkstyle.xml`), main and test sources. Objective rules only - unused/star/redundant
  imports, empty blocks/statements, `equals`/`hashCode`, fall-through, missing `default`, string `==`, needless boolean logic,
  inner assignment, tabs, final newline. Style-taste rules are warnings, not failures.
- **Static analysis:** SpotBugs (`effort=Max`, `threshold=Medium`, `failOnError=true`) with documented suppressions in
  `config/quality/spotbugs-exclude.xml` (record/entity exposure, validating constructors, JPA-written fields).
  SpotBugs 4.8 parses JDK class files with ASM, which stops at Java 23, so the `spotbugs` profile activates itself on JDK 17-23
  and is skipped on newer JDKs (CI runs 17 and 21, so it is always enforced there). `-Pspotbugs` forces it, `-Dspotbugs.skip=true` skips it.
- **Performance:** `RedirectPerformanceTest` in the normal test run: p95 latency budget on the redirect path, a 4-thread burst,
  no click lost. Budgets are ~10x laptop numbers so shared CI runners do not flake it.
- **Dependency vulnerabilities:** OWASP dependency-check (`-Psecurity`, fails at CVSS >= 7) plus weekly Dependabot PRs. This one is
  *not* in the default build: it downloads the NVD database (minutes, rate limited without an API key, needs network), which would make
  every local build slow and flaky. It is enforced in CI whenever the `NVD_API_KEY` secret exists.

**Consequences.**
- (+) Every gate named in the brief has a command, runs automatically, and appears in the log.
- (-) The Checkstyle/SpotBugs/OWASP configurations were written without being able to run Maven in the authoring environment: the
  first `mvn verify` may report findings or need a rule tuned. Source was pre-checked by script for the Checkstyle rules.
- (-) On JDK 24+ SpotBugs does not run locally; the CI matrix covers it.
