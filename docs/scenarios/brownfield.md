# Scenario: brownfield expiry and analytics

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc run --scenario brownfield --workspace .
```

The codebase stage inspects Java imports, Spring MVC route annotations, service modules, and the SQLite boundary before producing an impact summary. The request preserves the redirect contract while adding expiry and analytics. Review `repo_reasoning.json` and `decomposition.json`; then approve release after validation:

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc approve RUN_ID --checkpoint release \
  --actor "Reviewer" --rationale "Regression criteria and policy checks passed."

java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc resume RUN_ID
```

The codebase report names the API, domain service, database schema, and relevant tests. This fixture uses the working Java prototype so repository reasoning is demonstrated against a real codebase rather than a fabricated architecture.
