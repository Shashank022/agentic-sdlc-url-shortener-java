# Interview walkthrough

This is a five-to-seven-minute path through the project. It shows the working API first, then the workflow's planning, human gates, validation, audit trail, and rollback.

## Before the call

From the repository root:

```bash
mvn clean verify
docker compose up --build
```

Run the requests in [`examples/shortener.http`](../examples/shortener.http) with the VS Code REST Client extension. The example creates a link, follows it, then reads aggregate click statistics. A successful redirect returns `302` and increments the count; expired links return `410`, while missing links return `404`.

In another terminal, build the runnable jar for workflow commands:

```bash
mvn -q package -DskipTests
```

## Demo sequence

### 1. Start with the ambiguous request

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc run --scenario ambiguous --workspace .
```

Copy the printed `run_id`. The run pauses at the requirements checkpoint before implementation planning proceeds. Use this to explain that uncertain assumptions become an explicit human decision rather than silent agent guesses.

### 2. Inspect the persisted run and audit history

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc status RUN_ID

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc events RUN_ID
```

Point out the persisted stage projection, trace ID, checkpoint, and append-only events in `.agentic/state.sqlite3`. The stage artifacts are reviewable JSON under `.agentic/runs/RUN_ID/artifacts/`.

### 3. Record a requirements decision and resume

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc approve RUN_ID --checkpoint requirements \
  --actor "Reviewer" \
  --rationale "Accept optional expiry and aggregate analytics without visitor identifiers."

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc resume RUN_ID
```

The workflow builds an implementation map, then runs tests, security review, and documentation as independent stages before joining at release readiness. The reference agents are deterministic and offline; the implementation stage does not write source code.

### 4. Approve local promotion and inspect the result

When the run reaches the separate release checkpoint, record the decision and resume:

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc approve RUN_ID --checkpoint release \
  --actor "Reviewer" \
  --rationale "Validation passed; approve local artifact promotion."

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc resume RUN_ID
```

Show the manifest at `.agentic/releases/RUN_ID/manifest.json` and the active pointer at `.agentic/current_release.json`. Promotion is atomic and local; this prototype does not deploy to a cloud environment.

### 5. Exercise rollback and close with trade-offs

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc rollback-release RUN_ID \
  --actor "Reviewer" --rationale "Demonstrate audited local rollback."
```

Close by distinguishing what is implemented from what production would require: the backend is local and deterministic, persistence is single-node SQLite, and rate limiting is process-local. A provider adapter, isolated build workers, managed persistence, shared rate limiting, stronger security scanning, and a deployment policy would be follow-up work.

## Short opening summary

> This Java 17 Spring Boot project pairs a URL shortener with a persistent, governed SDLC workflow prototype. The workflow is a validated DAG with bounded parallel checks, human approval at ambiguity and release boundaries, event history, retry and fallback behavior, and audited local promotion or rollback. Agents produce reviewable artifacts; they do not write arbitrary source code or deploy externally.
