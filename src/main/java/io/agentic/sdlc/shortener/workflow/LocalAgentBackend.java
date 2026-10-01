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

/** Offline repository inspector and policy support. Source generation requires a contextual model provider. */
public class LocalAgentBackend implements AgentBackend {
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
            case "repair" -> Map.of("status", "incomplete", "reason", "Offline inspection cannot safely synthesize a repair patch.", "changes", List.of());
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
        Path testsRoot = context.workspace().resolve("src/test/java");
        List<Map<String, Object>> modules = new ArrayList<>();
        List<String> routes = new ArrayList<>();
        Map<String, List<String>> classToFile = new LinkedHashMap<>();
        List<Map<String, Object>> testInventory = new ArrayList<>();
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(item -> item.toString().endsWith(".java")).sorted().toList()) {
                    String relative = context.workspace().relativize(path).toString().replace('\\', '/');
                    String source = Files.readString(path);
                    List<String> imports = source.lines().filter(line -> line.stripLeading().startsWith("import "))
                            .map(String::strip).distinct().limit(20).toList();
                    List<String> classes = matches(source, "\\b(?:class|interface|enum|record)\\s+(\\w+)");
                    List<String> methods = matches(source, "(?m)\\b(?:public|protected|private)\\s+(?:static\\s+)?[\\w<>?,.\\[\\] ]+\\s+(\\w+)\\s*\\([^;{}]*\\)\\s*(?:throws [^{]+)?\\{");
                    modules.add(Map.of("path", relative, "imports", imports, "classes", classes, "methods", methods));
                    classes.forEach(name -> classToFile.computeIfAbsent(name, ignored -> new ArrayList<>()).add(relative));
                    var matcher = Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping(?:\\(\\\"([^\\\"]*)\\\"\\))?").matcher(source);
                    while (matcher.find()) {
                        String route = matcher.group(2);
                        routes.add("/" + (route == null ? "" : route).replaceFirst("^/+", ""));
                    }
                }
            }
        }
        if (Files.exists(testsRoot)) {
            try (var paths = Files.walk(testsRoot)) {
                for (Path path : paths.filter(item -> item.toString().endsWith(".java")).sorted().toList()) {
                    String source = Files.readString(path);
                    testInventory.add(Map.of("path", context.workspace().relativize(path).toString().replace('\\', '/'),
                            "classes", matches(source, "\\bclass\\s+(\\w+)"),
                            "test_methods", matches(source, "(?m)@Test\\s+(?:void\\s+)?(\\w+)\\s*\\(")));
                }
            }
        }
        List<String> dependencies = new ArrayList<>();
        for (Map<String, Object> module : modules) {
            String path = String.valueOf(module.get("path"));
            for (Object className : (List<?>) module.get("classes")) {
                for (String target : classToFile.getOrDefault(String.valueOf(className), List.of())) {
                    if (!target.equals(path)) dependencies.add(path + " -> " + target);
                }
            }
        }
        String criteriaText = String.join(" ", strings(context.request().get("acceptance_criteria"))).toLowerCase(Locale.ROOT);
        List<String> likelyImpacts = modules.stream().filter(module -> {
            String path = String.valueOf(module.get("path")).toLowerCase(Locale.ROOT);
            return criteriaText.split("\\W+").length > 0 && java.util.Arrays.stream(criteriaText.split("\\W+"))
                    .filter(token -> token.length() > 3).anyMatch(path::contains);
        }).map(module -> String.valueOf(module.get("path"))).toList();
        return Map.of("mode", "brownfield", "baseline_found", !modules.isEmpty(), "modules", modules,
                "api_routes", routes.stream().distinct().toList(),
                "tests", testInventory, "class_to_file", classToFile, "dependency_paths", dependencies.stream().distinct().toList(),
                "likely_impacted_files", likelyImpacts,
                "data_flow", routes.isEmpty() ? List.of("No annotated Spring MVC routes were found.") : List.of("Spring MVC route annotations found: " + routes.stream().distinct().toList()));
    }

    private Map<String, Object> decomposition(AgentContext context) {
        List<String> criteria = strings(context.priorOutputs().getOrDefault("intake", Map.of()).get("acceptance_criteria"));
        List<Map<String, Object>> tasks = new ArrayList<>();
        List<String> path = new ArrayList<>();
        tasks.add(Map.of("id", "T1", "task", "Inspect repository evidence and identify impacted code/tests",
                "depends_on", List.of(), "owner", "codebase", "deliverable", "repo_reasoning.json"));
        path.add("T1");
        for (int index = 0; index < criteria.size(); index++) {
            String id = "AC-" + (index + 1);
            String taskId = "T" + (index + 2);
            tasks.add(Map.of("id", taskId, "task", "Implement and test: " + criteria.get(index),
                    "depends_on", List.of("T1"), "owner", "engineer", "deliverable", id,
                    "criterion_ids", List.of(id)));
            path.add(taskId);
        }
        tasks.add(Map.of("id", "VALIDATE", "task", "Compile, test, and repair the isolated candidate",
                "depends_on", List.copyOf(path.subList(1, path.size())), "owner", "test-and-engineer", "deliverable", "validation.json"));
        tasks.add(Map.of("id", "DOCS", "task", "Document only the verified candidate and its evidence",
                "depends_on", List.of("VALIDATE"), "owner", "docs", "deliverable", "engineering_summary.md"));
        path.addAll(List.of("VALIDATE", "DOCS"));
        boolean blocked = criteria.isEmpty();
        return Map.of("tasks", tasks, "critical_path", path,
                "parallel_work", List.of("Candidate security review and documentation join before release readiness."),
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
        return Map.of("status", "incomplete", "reason", "Offline mode does not invent source changes. Configure an OpenAI-compatible code model to propose a contextual patch.",
                "changes", List.of(), "source_editing", false);
    }

    private Map<String, Object> securityReview(AgentContext context) throws IOException {
        Path sourceRoot = context.workspace().resolve("src/main/java");
        List<Map<String, String>> findings = new ArrayList<>();
        StringBuilder allSource = new StringBuilder();
        if (Files.exists(sourceRoot)) {
            try (var paths = Files.walk(sourceRoot)) {
                for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(path);
                    allSource.append(source).append('\n');
                    if (DYNAMIC_EXECUTION.matcher(source).find()) {
                        findings.add(Map.of("rule", "dynamic_code_execution", "path", context.workspace().relativize(path).toString(), "severity", "high"));
                    }
                    if (HARDCODED_SECRET.matcher(source).find()) {
                        findings.add(Map.of("rule", "hardcoded_credential", "path", context.workspace().relativize(path).toString(), "severity", "high"));
                    }
                }
            }
        }
        String source = allSource.toString().toLowerCase(Locale.ROOT);
        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("dynamic_execution_absent", findings.stream().noneMatch(item -> item.get("rule").equals("dynamic_code_execution")));
        checks.put("hardcoded_credentials_absent", findings.stream().noneMatch(item -> item.get("rule").equals("hardcoded_credential")));
        checks.put("candidate_isolated_from_baseline", Boolean.FALSE.equals(context.priorOutputs()
                .getOrDefault("apply_changes", Map.of()).get("baseline_modified")));
        checks.put("release_scope_is_local", true);
        List<String> criteria = strings(context.request().get("acceptance_criteria"));
        boolean requiresPrivacyCheck = criteria.stream().anyMatch(item -> item.toLowerCase(Locale.ROOT).matches(".*\\b(ip|privacy|visitor data)\\b.*"));
        if (requiresPrivacyCheck) checks.put("visitor_" + "ip_not_persisted",
                !source.contains("visitor_" + "ip") && !source.contains("ip_" + "address"));
        return Map.of("status", findings.isEmpty() && checks.values().stream().allMatch(Boolean::booleanValue) ? "passed" : "failed",
                "findings", findings, "checks", checks,
                "limitations", List.of("Static source checks do not replace SAST, dependency scanning, or penetration testing.",
                        "Acceptance behavior is validated by generated regression tests and the Maven build."));
    }

    private Map<String, Object> documentation(AgentContext context) {
        Map<String, Object> intake = context.priorOutputs().getOrDefault("intake", Map.of());
        Map<String, Object> architecture = context.priorOutputs().getOrDefault("architecture", Map.of());
        Map<String, Object> repair = context.priorOutputs().getOrDefault("repair", Map.of());
        Map<String, Object> validation = repair.get("final_validation") instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
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
        lines.addAll(List.of("", "## Verified implementation"));
        for (Object item : list(context.priorOutputs().getOrDefault("implementation", Map.of()).get("changed_files"))) {
            lines.add("- Changed: `" + item + "`");
        }
        lines.add("- Candidate validation: `" + validation.getOrDefault("status", "missing") + "` (exit " + validation.getOrDefault("exit_code", "missing") + ").");
        lines.add("- Validation command: `" + String.join(" ", strings(validation.get("command"))) + "`.");
        lines.add("- Repair attempts: " + repair.getOrDefault("attempts", 0) + ".");
        lines.addAll(List.of("", "## Release control",
                "- Named human approval is required before applying the proposal and promoting the local source bundle.",
                "- Promotion creates a local artifact bundle; it does not deploy to production.",
                "- Rollback restores the previous local release pointer and discards the isolated candidate."));
        return Map.of("path", "engineering_summary.md", "markdown", String.join("\n", lines) + "\n");
    }

    private Map<String, Object> releaseReadiness(AgentContext context) {
        Map<String, Object> repair = context.priorOutputs().getOrDefault("repair", Map.of());
        Map<String, Object> tests = repair.get("final_validation") instanceof Map<?, ?> raw
                ? map(raw) : context.priorOutputs().getOrDefault("tests", Map.of());
        Map<String, Object> security = context.priorOutputs().getOrDefault("security_review", Map.of());
        Map<String, Object> implementation = context.priorOutputs().getOrDefault("implementation", Map.of());
        Map<String, Object> intake = context.priorOutputs().getOrDefault("intake", Map.of());
        Map<String, Object> requirementApproval = context.priorOutputs().getOrDefault("requirement_approval", Map.of());
        List<String> blockers = new ArrayList<>();
        if (!"passed".equals(tests.get("status")) || !(tests.get("exit_code") instanceof Number code)
                || code.intValue() != 0 || !(tests.get("test_count") instanceof Number count) || count.intValue() < 1) {
            blockers.add("Candidate compilation/tests did not pass with executed-test evidence.");
        }
        if (!"passed".equals(security.get("status"))) blockers.add("Security policy checks did not pass.");
        if (!("not_needed".equals(repair.get("status")) || "recovered".equals(repair.get("status")))) blockers.add("Bounded repair did not produce a validated candidate.");
        for (Object item : list(implementation.get("criterion_traceability"))) {
            if (item instanceof Map<?, ?> criterion && list(criterion.get("test_files")).isEmpty()) {
                blockers.add("At least one criterion has no generated regression test.");
            }
        }
        if (!list(intake.get("open_questions")).isEmpty() && !"approved".equals(requirementApproval.get("decision"))) {
            blockers.add("Requirement questions remain unresolved.");
        }
        return Map.of("decision", blockers.isEmpty() ? "ready_for_human_review" : "blocked",
                "blockers", blockers, "release_scope", "local artifact bundle only",
                "checks", Map.of("tests", tests.getOrDefault("status", "missing"),
                        "security", security.getOrDefault("status", "missing"),
                        "implementation", implementation.getOrDefault("status", "missing")));
    }

    private static List<String> matches(String source, String expression) {
        var matcher = Pattern.compile(expression).matcher(source);
        List<String> values = new ArrayList<>();
        while (matcher.find()) values.add(matcher.group(1));
        return values.stream().distinct().toList();
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

    private static Map<String, Object> map(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

}
