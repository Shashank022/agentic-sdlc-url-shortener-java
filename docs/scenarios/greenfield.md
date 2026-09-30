# Scenario: greenfield URL shortener

Build the runnable Java jar first, then start the greenfield run:

```bash
mvn -q package -DskipTests
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc run --scenario greenfield --workspace .
```

The request has concrete acceptance criteria, so the requirements gate is recorded as not required. The runner advances through design, implementation mapping, and parallel test/security/documentation work, then pauses for release approval.

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc approve RUN_ID --checkpoint release \
  --actor "Reviewer" --rationale "Review the passing validation output and promote the local artifact bundle."

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc resume RUN_ID
```

Review `.agentic/runs/RUN_ID/artifacts/` and `.agentic/releases/RUN_ID/manifest.json`.
