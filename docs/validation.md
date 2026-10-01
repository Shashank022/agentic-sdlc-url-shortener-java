# Validation approach

## Repository quality gate

```bash
mvn clean test
mvn verify
```

`mvn verify` generates JaCoCo reports and enforces the configured line-coverage threshold. GitHub Actions runs `mvn clean verify` for pushes and pull requests.

JUnit covers:

- Domain and API behavior: HTTP(S) target rules, alias validation, expiry, transactional click counts, API errors, health endpoints, request IDs, and SQLite readiness.
- Patch policy: safe relative paths, allowed Java source/test extensions, criterion links, required generated tests, create/update/delete semantics, and bounded patch size.
- Candidate safety: brownfield source copy, greenfield Maven-only seed, isolated create/update/delete operations, and unchanged baseline fingerprint.
- Model adapter: structured request context, provider response parsing, bearer auth, endpoint failures, and malformed output.
- Orchestration: exact diff approvals, ambiguous requirements, approval denial, actual test evidence policy, bounded repair and revalidation, provider retry/fallback, request/source replanning, revision archives, candidate deletion, source-verified rollback, and metrics from build/repair events.

## Candidate quality gate

After the named reviewer approves the exact implementation diff, the workflow applies changes only below `.agentic/runs/{run_id}/candidate/`. `MavenTestStageRunner` runs:

```text
mvn --batch-mode --no-transfer-progress test
```

from that candidate directory. This compiles application and test sources, runs all Maven tests, and captures the command, exit code, executed-test count, duration, and output tail. A fixed timeout and output cap apply. A missing executable, timeout, nonzero exit, zero executed tests, missing evidence, or no generated test file for an acceptance criterion blocks readiness.

If tests fail, the repair agent receives the error output and current candidate source. At most two patch attempts are accepted by default; every repair is path/criterion checked and followed by a new build/test execution. The original workspace is never repaired in place. Exhaustion blocks readiness and discards the candidate.

## Manual review checklist

1. Confirm intake preserves submitted criteria and ambiguous requests pause.
2. Check repository evidence identifies classes, methods, routes, imports, and existing test files.
3. Inspect the proposed complete-file diff, rationale, acceptance-criterion IDs, risks, and generated test plan before approving `changes`.
4. Verify the command, exit code, executed-test count, output tail, repair history, candidate security checks, and generated summary.
5. At release approval, inspect the final source diff, including any repair changes, along with risks, test plan, and validation evidence.
6. Confirm readiness is policy-derived and the release manifest records the second approval.
7. Exercise a request replan, stop, denied diff, exhausted repair, and release rollback; check revision archives and audit events.

## Known validation limits

The security review is intentionally small and deterministic; it is not a compliance claim or replacement for independent security testing. Candidate builds execute Maven plugins and generated tests, so use trusted repositories and isolated runners. Production use requires stronger OS/container sandboxing, identity-bound approval, resource/network policy, dependency scanning, and provider evaluation. The scenario markdown files are reproducible run instructions; they are not substitutes for captured greenfield, brownfield, and ambiguous execution artifacts from the exact submitted revision. Run `mvn clean verify` and preserve real scenario outputs before claiming those demonstrations are complete.
