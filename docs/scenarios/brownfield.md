# Scenario: brownfield expiry and analytics

Configure the contextual model provider and start the scenario:

```bash
java -jar target/agentic-sdlc-url-shortener-1.0.0.jar \
  --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --logging.level.root=ERROR sdlc run --scenario brownfield --workspace .
```

The repository analysis includes Java classes, methods, imports, Spring MVC routes, and test files. The model uses that evidence to propose the smallest code/test set for preserving redirects while adding expiry and privacy-preserving click analytics. Inspect `repo_reasoning.json`, `decomposition.json`, and `implementation.json` before approving `--checkpoint changes`.

The approved patch is applied to a copy below `.agentic/runs/RUN_ID/candidate/`. The runner compiles and tests the full candidate; if the build fails, the captured output and current candidate code are sent to the repair stage, which is bounded and must rerun tests. Security review and the engineering summary describe the final candidate. Local release promotion requires a separate approval and includes a source snapshot for review.
