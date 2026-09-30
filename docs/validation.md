# Validation approach

## Automated checks

JUnit covers three layers:

- **Domain tests:** HTTP(S) and local-address rules, alias validation, uniqueness, expiry bounds, atomic click behavior, concurrent increments, and SQLite readiness.
- **API integration tests:** create/redirect/stats flow, request IDs, health checks, input errors, conflicts, not-found behavior, and `410` expiry behavior against an embedded Spring Boot server.
- **Orchestration tests:** DAG structure and join, all three scenarios, approvals and denial, retry/fallback, request/source replanning, safe stop, criteria evidence blocking, parallel stage execution, release promotion, and rollback.

The workflow test stage runs the domain and API suites only, avoiding recursive execution of the orchestrator tests. GitHub Actions runs the entire suite separately on pushes and pull requests.

## Local quality gate

```bash
mvn clean test
mvn verify
```

`mvn verify` generates JaCoCo reports and enforces at least 80% line coverage for the Java bundle. The workflow release readiness stage requires its API/domain test run and source policy checks to pass before requesting human release approval.

## Manual review checklist

1. Confirm normalized requirements and open questions.
2. Review the dependency plan and architecture decisions before resolving ambiguous assumptions.
3. Check the brownfield assessment identifies Java routes and impacted modules.
4. Inspect test output, policy findings, and the generated engineering summary.
5. Check the release manifest and approval actor/rationale.
6. Exercise stop, replan, and rollback; confirm each leaves an audit event.

## Known validation limits

The security review is intentionally small and deterministic; it is not a compliance claim or a replacement for independent security testing. The prototype has no load test, distributed concurrency test, database migration test, dependency vulnerability scan, cloud deployment test, or model evaluation suite.
