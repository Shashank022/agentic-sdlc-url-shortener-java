# Scenario: ambiguous request

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc run --scenario ambiguous --workspace .
```

“Make short links safer and faster” contains no measurable acceptance criteria. Intake records open questions and pauses at `requirements`. Implementation is still pending. A reviewer cannot approve vague assumptions into code; revise the run with product clarification and explicit measurable criteria:

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc revise RUN_ID --actor "Product owner" \
  --request "Add optional expiry and privacy-preserving click analytics." \
  --acceptance-criteria "Expired links return HTTP 410||Click counts are exposed without storing visitor IP addresses"

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc resume RUN_ID
```

Replanning stores the previous request and artifacts under `revisions/`, invalidates stale decisions, and produces new repository reasoning, plan, design, and source/test work. The run then pauses for exact-diff approval before applying its candidate patch. Every later promotion requires validated evidence and a separate release approval.
