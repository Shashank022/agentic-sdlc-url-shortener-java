# Scenario: greenfield URL shortener

Configure the OpenAI-compatible provider described in the [README](../../README.md), build the CLI jar, then start the run:

```bash
mvn -q package -DskipTests
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc run --scenario greenfield --workspace .
```

The generated candidate starts from `pom.xml` only. The model must create the working application source and regression tests; the existing application source is not copied into this candidate. The workflow pauses at `changes` with a full diff, criterion links, risks, and test plan. Review `.agentic/runs/RUN_ID/artifacts/implementation.json`, then approve that exact proposal:

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc approve RUN_ID --checkpoint changes \
  --actor "Reviewer" --rationale "The source/test diff and risks match the accepted criteria."

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc resume RUN_ID
```

The full Maven test command runs against the candidate, not the original checkout. After security review and documentation, inspect readiness, approve `release`, and resume to promote the source and evidence bundle locally. No production deployment occurs.
