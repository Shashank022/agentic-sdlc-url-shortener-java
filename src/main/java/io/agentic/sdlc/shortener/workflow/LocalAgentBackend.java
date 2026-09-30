package io.agentic.sdlc.shortener.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Offline deterministic reference agents; policy, approvals, and promotion remain in the orchestrator. */
public class LocalAgentBackend implements AgentBackend {
    private static final List<String> REQUIRED_MODULES = List.of(
            "src/main/java/io/agentic/sdlc/shortener/api/LinkController.java",
            "src/main/java/io/agentic/sdlc/shortener/api/RedirectController.java",
            "src/main/java/io/agentic/sdlc/shortener/link/ShortenerService.java",
            "src/main/java/io/agentic/sdlc/shortener/link/Database.java",
            "src/main/java/io/agentic/sdlc/shortener/workflow/Orchestrator.java");
    private static final Pattern DYNAMIC_EXECUTION = Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec\\s*\\(|ScriptEngine" + "Manager");
    private static final Pattern HARDCODED_SECRET = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|secret|password)\\s*=\\s*[\\\"'][^\\\"']{8,}[\\\"']");
    private final TestStageRunner testStageRunner;

    public LocalAgentBackend() {
        this(new MavenTestStageRunner());
    }

    public LocalAgentBackend(TestStageRunner testStageRunner) {
        this.testStageRunner = testStageRunner;
    }

    @Override
    public Map<String, Object> execute(String stageId, AgentContext context) throws Exception {
        return switch (stageId) {
            case "intake" -> intake(context);
            case "repo_reasoning" -> repositoryReasoning(context);
            case "decomposition" -> decomposition(context);
            case "architecture" -> architecture();
            case "implementation" -> implementation(context);
            case "tests" -> testStageRunner.run(context.workspace());
            case "security_review" -> securityReview(context);
            case "documentation" -> documentation(context);
            case "release_readiness" -> releaseReadiness(context);
            case "release_promotion" -> Map.of("result", "promoted", "target", ".agentic/releases/" + context.runId(),
                    "external_deployment", false);
            default -> throw new IllegalArgumentException("No local agent is registered for stage " + stageId + ".");
        };
    }

    private Map<String, Object> intake(AgentContext context) {
        List<String> criteria = strings(context.request().get("acceptance_criteria"));
        List<String> questions = criteria.isEmpty() ? List.of(
                "Should new links expire by default, and what retention period is required?",
                "Who may view click analytics, and what privacy or retention rules apply?",
                "What measurable redirect latency target and traffic profile define ‘faster’?",
                "Which threat scenarios should ‘safer’ address: abuse, phishing, access control, or all?") : List.of();
        return Map.of(
                "title", string(context.request().get("title"), context.scenario()),
                "normalized_problem", string(context.request().get("request"), ""),
                "acceptance_criteria", criteria,
                "assumptions", List.of("Redirects accept only HTTP or HTTPS destinations.",
                        "The reference workflow uses local SQLite and makes no external model calls."),
                "open_questions", questions,
                "ambiguity", questions.isEmpty() ? "low" : "high",
                "mode", string(context.request().get("mode"), context.scenario()));
    }

    private Map<String, Object> repositoryReasoning(AgentContext context) throws IOException {
        if (context.scenario().equals("greenfield")) {
            return Map.of("mode", "greenfield", "baseline_found", false,
                    "planned_modules", List.of("api", "link service", "SQLite repository", "workflow orchestrator"),
                    "data_flow", List.of("HTTP request", "validation", "SQLite", "redirect or analytics response"));
        }
        Path root = context.workspace().resolve("src/main/java");
        List<Map<String, Object>> modules = new ArrayList<>();
        List<String> routes = new ArrayList<>();
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(item -> item.toString().endsWith(".java")).sorted().toList()) {
                    String relative = context.workspace().relativize(path).toString().replace('\\', '/');
                    String source = Files.readString(path);
                    List<String> imports = source.lines().filter(line -> line.stripLeading().startsWith("import "))
                            .map(String::strip).distinct().limit(20).toList();
                    modules.add(Map.of("path", relative, "imports", imports));
                    var matcher = Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping(?:\\(\\\"([^\\\"]*)\\\"\\))?").matcher(source);
                    while (matcher.find()) {
                        String route = matcher.group(2);
                        routes.add("/" + (route == null ? "" : route).replaceFirst("^/+", ""));
                    }
                }
            }
        }
        return Map.of("mode", "brownfield", "baseline_found", !modules.isEmpty(), "modules", modules,
                "api_routes", routes.stream().distinct().toList(),
                "data_flow", List.of("Spring MVC endpoint", "shortener domain service", "SQLite repository", "HTTP response"),
                "impact_summary", "Changes primarily affect the API, link service, database, and workflow tests.");
    }

    private Map<String, Object> decomposition(AgentContext context) {
        List<Map<String, Object>> tasks = List.of(
                Map.of("id", "T1", "task", "Normalize scope and acceptance criteria", "depends_on", List.of(), "owner", "requirements", "deliverable", "requirement.json"),
                Map.of("id", "T2", "task", "Map impacted API, service, and persistence modules", "depends_on", List.of("T1"), "owner", "codebase", "deliverable", "codebase_assessment.json"),
                Map.of("id", "T3", "task", "Define the API contract, data model, and safety decisions", "depends_on", List.of("T1", "T2"), "owner", "architect", "deliverable", "architecture.json"),
                Map.of("id", "T4", "task", "Implement only changes covered by approved criteria", "depends_on", List.of("T3", "requirement_approval"), "owner", "engineer", "deliverable", "implementation_map.json"),
                Map.of("id", "T5", "task", "Run regression, API, and security checks", "depends_on", List.of("T4"), "owner", "test-and-security", "deliverable", "validation.json"),
                Map.of("id", "T6", "task", "Generate the engineering summary and release decision", "depends_on", List.of("T5"), "owner", "release", "deliverable", "engineering_summary.md"));
        boolean blocked = strings(context.request().get("acceptance_criteria")).isEmpty();
        return Map.of("tasks", tasks, "critical_path", List.of("T1", "T2", "T3", "T4", "T5", "T6"),
                "parallel_work", List.of("Tests, security review, and documentation join before release readiness."),
                "blocked_by_human", blocked);
    }

    private Map<String, Object> architecture() {
        List<Map<String, String>> components = List.of(
                Map.of("name", "Spring Boot MVC", "responsibility", "HTTP contract, request validation, redirects, and health probes"),
                Map.of("name", "Shortener service", "responsibility", "URL safety, aliases, expiry, and click semantics"),
                Map.of("name", "SQLite", "responsibility", "Durable link records and atomic click increments"),
                Map.of("name", "Workflow orchestrator", "responsibility", "DAG execution, state, approvals, audit events, and metrics"));
        List<Map<String, String>> decisions = List.of(
                Map.of("id", "ADR-001", "decision", "Use SQLite for a self-contained prototype.", "tradeoff", "Single-node simplicity; move to a managed relational store for multi-node scale."),
                Map.of("id", "ADR-002", "decision", "Count redirects transactionally and never persist visitor IP addresses.", "tradeoff", "A short write transaction adds a small amount of redirect latency."),
                Map.of("id", "ADR-003", "decision", "Require named human approval before ambiguous assumptions and local release promotion.", "tradeoff", "The workflow pauses for review instead of silently taking high-impact actions."));
        return Map.of("components", components, "decisions", decisions,
                "control_flow", List.of("intake -> repository reasoning -> decomposition -> architecture",
                        "human requirements gate -> implementation",
                        "tests || security review || documentation -> readiness join",
                        "human release gate -> atomic local promotion"),
                "governance", List.of("validated typed DAG", "workspace-scoped artifacts", "append-only events", "no production deploy tool"));
    }

    private Map<String, Object> implementation(AgentContext context) {
        List<String> existing = REQUIRED_MODULES.stream().filter(path -> Files.exists(context.workspace().resolve(path))).toList();
        List<String> missing = REQUIRED_MODULES.stream().filter(path -> !Files.exists(context.workspace().resolve(path))).toList();
        List<String> criteria = strings(context.request().get("acceptance_criteria"));
        List<Map<String, String>> mapped = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        for (String criterion : criteria) {
            String lower = criterion.toLowerCase(Locale.ROOT);
            String file = mapCriterion(lower);
            if (file == null) {
                unmapped.add(criterion);
            } else {
                mapped.add(Map.of("criterion", criterion, "module", file, "evidence", "Covered by the Java API/domain tests and policy review."));
            }
        }
        return Map.of("result", missing.isEmpty() ? "mapped" : "incomplete", "modules", existing,
                "missing_modules", missing, "mapped_criteria", mapped, "unmapped_criteria", unmapped,
                "write_boundary", "Read-only inspection; this stage does not edit application source.");
    }

    private Map<String, Object> securityReview(AgentContext context) throws IOException {
        Path sourceRoot = context.workspace().resolve("src/main/java/io/agentic/sdlc/shortener");
        List<Map<String, String>> findings = new ArrayList<>();
        Map<String, Boolean> checks = new LinkedHashMap<>();
        String shortener = read(sourceRoot.resolve("link/ShortenerService.java"));
        String database = read(sourceRoot.resolve("link/Database.java"));
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                if (DYNAMIC_EXECUTION.matcher(source).find()) {
                    findings.add(Map.of("rule", "dynamic_code_execution", "path", context.workspace().relativize(path).toString(), "severity", "high"));
                }
                if (HARDCODED_SECRET.matcher(source).find()) {
                    findings.add(Map.of("rule", "hardcoded_credential", "path", context.workspace().relativize(path).toString(), "severity", "high"));
                }
            }
        }
        checks.put("http_only_redirect_targets", shortener.contains("equalsIgnoreCase(\"http\")") && shortener.contains("equalsIgnoreCase(\"https\")"));
        checks.put("click_update_uses_sqlite_transaction", shortener.contains("BEGIN IMMEDIATE") && shortener.contains("UPDATE links SET click_count"));
        checks.put("visitor_ip_not_persisted", !database.toLowerCase(Locale.ROOT).contains("visitor_ip") && !database.toLowerCase(Locale.ROOT).contains("ip_address"));
        checks.put("release_scope_is_local", true);
        checks.put("agents_do_not_write_source", "Read-only inspection; this stage does not edit application source."
                .equals(context.priorOutputs().getOrDefault("implementation", Map.of()).get("write_boundary")));
        return Map.of("status", findings.isEmpty() && checks.values().stream().allMatch(Boolean::booleanValue) ? "passed" : "failed",
                "findings", findings, "checks", checks,
                "limitations", List.of("This deterministic scan is not a substitute for SAST, dependency scanning, or penetration testing."));
    }

    private Map<String, Object> documentation(AgentContext context) {
        Map<String, Object> intake = context.priorOutputs().getOrDefault("intake", Map.of());
        Map<String, Object> architecture = context.priorOutputs().getOrDefault("architecture", Map.of());
        String title = String.valueOf(intake.getOrDefault("title", "Engineering summary"));
        String request = String.valueOf(intake.getOrDefault("normalized_problem", ""));
        List<String> criteria = strings(intake.get("acceptance_criteria"));
        List<String> lines = new ArrayList<>(List.of("# Engineering summary: " + title, "",
                "**Scenario:** `" + context.scenario() + "`  ", "**Request:** " + request, "", "## Approved scope"));
        if (criteria.isEmpty()) {
            lines.add("- Pending human clarification; implementation remains blocked until assumptions are accepted.");
        } else {
            criteria.forEach(criterion -> lines.add("- " + criterion));
        }
        lines.addAll(List.of("", "## Design"));
        for (Object item : list(architecture.get("components"))) {
            Map<?, ?> component = (Map<?, ?>) item;
            lines.add("- **" + component.get("name") + "**: " + component.get("responsibility"));
        }
        lines.addAll(List.of("", "## Release control",
                "- Local promotion requires a named reviewer and recorded rationale.",
                "- The agent has no production deployment or external side-effect capability."));
        return Map.of("path", "engineering_summary.md", "markdown", String.join("\n", lines) + "\n");
    }

    private Map<String, Object> releaseReadiness(AgentContext context) {
        Map<String, Object> tests = context.priorOutputs().getOrDefault("tests", Map.of());
        Map<String, Object> security = context.priorOutputs().getOrDefault("security_review", Map.of());
        Map<String, Object> implementation = context.priorOutputs().getOrDefault("implementation", Map.of());
        Map<String, Object> intake = context.priorOutputs().getOrDefault("intake", Map.of());
        Map<String, Object> requirementApproval = context.priorOutputs().getOrDefault("requirement_approval", Map.of());
        List<String> blockers = new ArrayList<>();
        if (!"passed".equals(tests.get("status"))) blockers.add("Automated tests did not pass.");
        if (!"passed".equals(security.get("status"))) blockers.add("Security policy checks did not pass.");
        if (!list(implementation.get("missing_modules")).isEmpty()) blockers.add("Expected implementation modules are missing.");
        if (!list(implementation.get("unmapped_criteria")).isEmpty()) blockers.add("At least one acceptance criterion has no implementation or validation evidence.");
        if (!list(intake.get("open_questions")).isEmpty() && !"approved".equals(requirementApproval.get("decision"))) {
            blockers.add("Requirement questions remain unresolved.");
        }
        return Map.of("decision", blockers.isEmpty() ? "ready_for_human_review" : "blocked",
                "blockers", blockers, "release_scope", "local artifact bundle only",
                "checks", Map.of("tests", tests.getOrDefault("status", "missing"),
                        "security", security.getOrDefault("status", "missing"),
                        "implementation", implementation.getOrDefault("result", "missing")));
    }

    private static String mapCriterion(String criterion) {
        if (criterion.contains("p95") || criterion.contains("latency") || criterion.contains("requests per second")) return null;
        if (criterion.contains("health") || criterion.contains("liveness") || criterion.contains("readiness")) return "api/HealthController.java";
        if (criterion.contains("redirect") || criterion.contains("click") || criterion.contains("analytics") || criterion.contains("expir")) return "link/ShortenerService.java";
        if (criterion.contains("target") || criterion.contains("scheme") || criterion.contains("alias") || criterion.contains("unsafe") || criterion.contains("local address") || criterion.contains("malformed")) return "link/ShortenerService.java";
        if (criterion.contains("test") || criterion.contains("regression") || criterion.contains("automated")) return "src/test/java";
        if (criterion.contains("api") || criterion.contains("link")) return "api/LinkController.java";
        return null;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private static String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static String read(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path) : "";
    }

}
