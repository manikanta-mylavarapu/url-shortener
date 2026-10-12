# ADR-006: Engineer-led, AI-assisted execution – no autonomous orchestration engine

**Status:** accepted · **Needs your explicit agreement** (it is a scoping decision that reviewers may challenge)

**Context.** The brief says: *"Focus on engineer-led execution accelerated by AI, not autonomous orchestration."* Both reference repositories chose to build a workflow/agent orchestration engine (dependency graph, gates, policy engine, approvals, retries, rollback, audit log) alongside the shortener.

**Decision.** Build only the product and the *engineering process artifacts* around it: task decomposition with dependencies and acceptance criteria, a traceability log of AI output (accepted/edited/rejected with rationale), quality gates run by the engineer (`mvn verify`, `scripts/smoke.sh`), explicit human sign-off checklist for high-impact changes. No component executes SDLC steps on its own.

**Consequences.**
- (+) Matches the stated expectation; far less code to defend; the engineer demonstrably owns each decision.
- (+) The effort goes into the product quality and the validation evidence.
- (−) Less "wow" than a visual orchestration console, and no machine-generated audit trail. The traceability log is manual and only as honest as the engineer keeps it.

**If the reviewer wants more.** A thin, optional step is a script that appends a JSON line (task id, prompt file, files touched, test result, reviewer, timestamp) to `docs/evidence/ai-trace.jsonl` per task. It would still be engineer-triggered, not autonomous.
