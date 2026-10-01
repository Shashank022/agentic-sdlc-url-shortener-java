# Interview walkthrough

Use this five-to-seven-minute walkthrough to show a real requirement-to-patch path, candidate validation, governance, lineage, and rollback.

## Before the call

From the repository root:

```bash
mvn clean verify
docker compose up --build
```

Run the requests in [`examples/shortener.http`](../examples/shortener.http) with the VS Code REST Client. In a separate terminal, configure an OpenAI-compatible model provider and build the CLI jar:

```bash
export AGENT_BASE_URL=http://localhost:11434/v1
export AGENT_MODEL=your-coding-model
# Set AGENT_API_KEY only when required by the provider.
mvn -q package -DskipTests
```

The workflow safely stops incomplete before code generation if no contextual provider is available.

## Demo sequence

### 1. Show ambiguity stopping before implementation

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc run --scenario ambiguous --workspace .
```

The run pauses at `requirements`; no implementation has been generated. Inspect `status RUN_ID` and `events RUN_ID`, then explain that a vague phrase cannot be approved as a source-change request.

### 2. Revise with concrete criteria

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc revise RUN_ID --actor "Product owner" \
  --request "Add expiry and privacy-preserving click analytics." \
  --acceptance-criteria "Expired links return HTTP 410||Click counts are available without storing visitor IP addresses"

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc resume RUN_ID
```

The previous request and stage artifacts are preserved under `.agentic/runs/RUN_ID/revisions/`. The model receives the updated criteria and repository evidence, then proposes source and test changes.

### 3. Review and approve the exact candidate diff

At `pending_checkpoint: changes`, inspect `.agentic/runs/RUN_ID/artifacts/implementation.json`. It contains the complete diff, changed files, `AC-*` traceability, risks, test plan, and proposal hash. Approve the same proposal:

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc approve RUN_ID --checkpoint changes \
  --actor "Reviewer" --rationale "I reviewed the source diff, criterion links, risks, and test plan."

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc resume RUN_ID
```

The approved operations are applied to `.agentic/runs/RUN_ID/candidate/`; the repository checkout remains unchanged. The fixed Maven runner compiles candidate source, runs the full test suite, and requires a positive executed-test count. On failure, the repair agent receives the output and current candidate source, may make at most two bounded corrections, and must pass the build again. Security and documentation then run against the validated candidate.

### 4. Review readiness and promote locally

At the `release` checkpoint, inspect `release_readiness.json`, especially its final candidate diff (including repair changes), changed files, risks, test plan, and validation evidence. Also inspect `repair.json`, `security_review.json`, and `engineering_summary.md`. The release approval is bound to the final candidate fingerprint; local promotion copies that reviewed source and evidence to `.agentic/releases/RUN_ID/`:

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc approve RUN_ID --checkpoint release \
  --actor "Reviewer" --rationale "Build evidence, source review, and residual risks are acceptable."

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc resume RUN_ID
```

Promotion updates only `.agentic/current_release.json`. The project has no production deployment tool or credential.

### 5. Show rollback and actual metrics

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc rollback-release RUN_ID \
  --actor "Reviewer" --rationale "Demonstrate audited local rollback."

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc metrics --workspace .
```

Rollback restores the previous release pointer and reports whether the original source fingerprint still matches. Metrics count actual code-generation calls, Maven executions and failures, repair attempts and recovery duration, and verified rollbacks.

## Short opening summary

> This Java 17 Spring Boot project combines a URL shortener with a contextual SDLC workflow. It proposes repository-specific source and regression-test changes, binds human approval to the exact diff, validates an isolated candidate, and blocks promotion unless real build evidence passes. The source checkout stays unchanged and the workflow cannot deploy to production.
