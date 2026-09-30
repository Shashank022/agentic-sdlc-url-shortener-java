# Scenario: ambiguous request

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc run --scenario ambiguous --workspace .
```

“Safer and faster” has no measurable acceptance criteria. Intake records questions about default expiry, analytics access and retention, latency/traffic targets, and threat scope. The workflow pauses before implementation.

For the demo, a reviewer may explicitly accept a narrow set of assumptions:

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc approve RUN_ID --checkpoint requirements \
  --actor "Reviewer" \
  --rationale "For this prototype, accept optional expiry, HTTP(S)-only targets, and aggregate analytics without visitor identifiers."

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc resume RUN_ID
```

For a real requirement, revise it with measurable criteria instead of accepting assumptions:

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc revise RUN_ID --actor "Product owner" \
  --request "Add optional expiry and privacy-preserving click analytics." \
  --acceptance-criteria "Expired links return 410||Do not persist visitor IP addresses||Redirect p95 is under 100 ms at 100 requests per second"

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc resume RUN_ID
```

Either path still requires a separate release approval after validation.
