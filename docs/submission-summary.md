# Engineering submission summary

## Objective and result

This Java 17 / Spring Boot project pairs a URL shortener with a governed engineering workflow. With an OpenAI-compatible provider configured, a run carries the requirement and inspected repository context through planning, design, source/test proposal, exact-diff human approval, isolated candidate application, Maven validation, bounded failure repair, security review, documentation, and local promotion approval.

The original checkout remains unchanged. Greenfield candidates start with the Maven starter only; brownfield candidates copy Maven configuration and `src/` inputs only, leaving unrelated files and credentials outside. The final local release contains the verified candidate and evidence. The workflow has no production deployment capability.

## Design choices

1. Keep shortener domain logic independently testable behind a JDBC persistence boundary.
2. Model work as a validated DAG, with security review and documentation joining after candidate validation.
3. Keep provider calls separate from orchestration policy. A provider proposes changes; the orchestrator validates paths, acceptance-criterion links, approval hashes, and validation evidence.
4. Use a disposable candidate copy for all generated writes. This makes rollback a safe candidate discard and lets the original source fingerprint prove no working-tree change occurred.
5. Require explicit review of the source diff before apply and of the final candidate diff (including repair changes), build/security/documentation evidence before promotion.
6. Preserve prior run artifacts when request or source changes trigger replanning.

The offline backend can inspect repository code and run policy checks. If no model endpoint exists, or model generation fails, code generation and repair report incomplete and the workflow cannot pass readiness.

## Artifacts

- Spring MVC API, SQLite schema, Maven build, Docker image, and Compose file.
- Contextual OpenAI-compatible agent adapter and conservative offline fallback.
- Isolated candidate workspace, constrained Java source/test patch model, full Maven test runner, repair loop, and diff-bound approval checkpoints.
- Persistent DAG orchestrator, SQLite run/event/approval state, source fingerprinting, revision archives, local release bundle and rollback.
- Greenfield, brownfield, and ambiguous scenario definitions; API examples, interview walkthrough, architecture, validation, and risk documents.
- JUnit tests for API/domain behavior, DAG lifecycle, source isolation, patch path policy, build evidence, failure repair, replanning, approvals, and rollback.

## Validation

The candidate runner executes `mvn --batch-mode --no-transfer-progress test` from the candidate directory with a bounded timeout. It stores command, exit code, executed-test count, elapsed duration, and captured output. Missing Maven, timeout, nonzero exit, zero executed tests, absent evidence, absent criterion-linked tests, failed security policy, or missing final documentation blocks release readiness. Build failures are forwarded to repair, which must apply a constrained patch and rerun the same test command within the configured attempt limit.

The greenfield, brownfield, and ambiguous scenario files are runbooks. Actual provider-backed execution artifacts must be captured from the exact submitted revision to claim all three demonstrations; unit tests use test backends and are not presented as those scenario runs.

The repository quality gate is `mvn clean verify`; GitHub Actions enforces the same build and JaCoCo coverage threshold. Local Maven is not bundled; see `docs/validation.md` for exact checks.

## Trade-offs and limits

- Model output quality depends on the selected provider/model. Structured output parsing, path policy, full candidate build, tests, static source scan, and two human gates are required, but not a substitute for secure isolated execution or code review.
- The current candidate builder uses complete-file Java create/update/delete operations, not line-oriented patch hunks. The reviewer sees the generated full-file diff before approval.
- The runner does not authenticate approval identities; the CLI records actor and rationale but production needs identity/authorization integration.
- SQLite and the shortener's process-local rate limiter are single-node choices.
- The static scanner is not SAST, dependency scanning, DAST, or penetration testing. No deployment tool, external telemetry, load test, or production environment rollback is included.

See [Architecture](architecture.md), [Risk and trade-offs](risk-and-tradeoffs.md), and [Validation](validation.md) for details.
