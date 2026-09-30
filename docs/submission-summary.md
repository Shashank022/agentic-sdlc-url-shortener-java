# Engineering submission summary

## Objective and result

This Java 17 / Spring Boot prototype turns a software request into normalized requirements, a dependency-aware plan, architecture decisions, an implementation map, validation evidence, documentation, and a locally promoted release bundle. It also includes a runnable URL shortener with click analytics, optional expiry, alias validation, health checks, and request rate limiting.

## Plan and rationale

1. Keep the shortener domain logic independently testable behind a small JDBC persistence boundary.
2. Model SDLC work as a validated DAG so independent checks can run concurrently and join before release.
3. Persist run/stage state, approvals, leases, and append-only decisions so pauses, resumes, and replans retain lineage.
4. Pause for human review when requirements are ambiguous and before every release promotion.
5. Keep provider choice behind `AgentBackend` and keep write/deploy authority in the orchestrator.

The reference agents are deterministic and require no credentials or external model calls. A future provider adapter can supply model-backed analysis without moving approval or release policy into model output.

## Artifacts

- Spring MVC API, SQLite schema, Maven build, Docker image, and Compose file.
- DAG, agent roles, persistent orchestrator, Java CLI, approval flow, audit events, bounded retry/fallback, replanning, safe stop, local release promotion, and rollback.
- Greenfield, brownfield, and ambiguous scenario definitions and walkthroughs.
- Domain, API integration, graph, and orchestration tests; CI workflow; security guidance; architecture, validation, and risk notes.

## Validation

Run `mvn clean test` for the JUnit suite and `mvn verify` for the JaCoCo 80% line-coverage gate. The workflow test stage executes the shortener domain and API integration suites; the GitHub Actions job runs every test, including orchestration cases. Tests cover validation, redirects, expiry, privacy-safe statistics, concurrent click updates, all workflow gates, parallel stage execution, source/request replanning, retry/fallback, readiness blocking, safe stop, promotion, and rollback.

## Assumptions and trade-offs

- Short links accept HTTP(S) targets only; the service never fetches the destination itself.
- Expiry is optional. Click analytics count successful redirects and do not retain visitor IP addresses.
- SQLite and a process-local rate limiter keep the prototype self-contained; neither targets multi-replica production scale.
- The implementation stage produces an impact map and validates checked-in code; agents cannot modify arbitrary source files.
- Release promotion is an atomic local artifact operation, not a production deployment.

## Risks and limits

The policy scan is a small deterministic source check, not SAST or a security certification. The service has no user authentication, abuse-review workflow, distributed rate limiting, load test, external telemetry backend, or cloud deployment integration. Unmapped acceptance criteria and unsupported performance targets block readiness instead of being assumed satisfied.

See [Architecture](architecture.md), [Risk and trade-offs](risk-and-tradeoffs.md), and [Validation](validation.md) for details.
