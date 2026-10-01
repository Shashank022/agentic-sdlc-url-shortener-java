package io.agentic.sdlc.shortener.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAgentBackendTest {
    @TempDir Path workspace;

    @Test
    void deterministicIntakeCallsOutAmbiguityAndPreservesConcreteCriteria() throws Exception {
        LocalAgentBackend agent = new LocalAgentBackend(path -> Map.of("status", "passed"));
        Map<String, Object> ambiguous = agent.execute("intake", context("ambiguous", Map.of("request", "Make it safer."), Map.of()));
        assertEquals("high", ambiguous.get("ambiguity"));
        assertEquals(4, ((List<?>) ambiguous.get("open_questions")).size());
        Map<String, Object> concrete = agent.execute("intake", context("greenfield", request(List.of("Create a versioned API.")), Map.of()));
        assertEquals("low", concrete.get("ambiguity"));
        assertTrue(((List<?>) concrete.get("open_questions")).isEmpty());
    }

    @Test
    void repositoryReasoningRecognizesGreenfieldAndBrownfieldJavaRoutes() throws Exception {
        LocalAgentBackend agent = new LocalAgentBackend(path -> Map.of("status", "passed"));
        Map<String, Object> greenfield = agent.execute("repo_reasoning", context("greenfield", Map.of(), Map.of()));
        assertEquals(false, greenfield.get("baseline_found"));
        Path source = workspace.resolve("src/main/java/example/LinkController.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example;\nimport org.springframework.web.bind.annotation.GetMapping;\nclass LinkController { @GetMapping(\"/r/{code}\") void redirect() {} }");
        Map<String, Object> brownfield = agent.execute("repo_reasoning", context("brownfield", Map.of(), Map.of()));
        assertEquals(true, brownfield.get("baseline_found"));
        assertTrue(((List<?>) brownfield.get("api_routes")).contains("/r/{code}"));
        assertFalse(((List<?>) brownfield.get("modules")).isEmpty());
    }

    @Test
    void plansArchitectureAndMapsCriteriaWithoutClaimingUnmeasuredEvidence() throws Exception {
        LocalAgentBackend agent = new LocalAgentBackend(path -> Map.of("status", "passed"));
        Map<String, Object> intake = Map.of("acceptance_criteria", List.of());
        Map<String, Object> plan = agent.execute("decomposition", context("ambiguous", Map.of(), Map.of("intake", intake)));
        assertEquals(true, plan.get("blocked_by_human"));
        assertEquals(3, ((List<?>) plan.get("tasks")).size());
        assertTrue(((List<?>) plan.get("parallel_work")).get(0).toString().contains("join"));
        Map<String, Object> architecture = agent.execute("architecture", context("greenfield", Map.of(), Map.of()));
        assertEquals(4, ((List<?>) architecture.get("components")).size());
        Map<String, Object> implementation = agent.execute("implementation", context("greenfield",
                request(List.of("Create a versioned HTTP API.", "Redirect p95 latency below 100 ms.")), Map.of()));
        assertEquals("incomplete", implementation.get("status"));
        assertFalse(Boolean.TRUE.equals(implementation.get("source_editing")));
    }

    @Test
    void securityReviewAndReadinessReportFailedChecksAndSuccessEvidence() throws Exception {
        LocalAgentBackend agent = new LocalAgentBackend(path -> Map.of("status", "passed"));
        Path root = workspace.resolve("src/main/java/io/agentic/sdlc/shortener");
        Files.createDirectories(root.resolve("link"));
        Files.createDirectories(root.resolve("workflow"));
        Files.writeString(root.resolve("link/ShortenerService.java"), "equalsIgnoreCase(\"http\") equalsIgnoreCase(\"https\") BEGIN IMMEDIATE UPDATE links SET click_count");
        Files.writeString(root.resolve("link/Database.java"), "CREATE TABLE links(code TEXT)");
        Files.writeString(root.resolve("workflow/Detector.java"), "class Detector { String rule = \"ScriptEngine\" + \"Manager\"; }");
        Map<String, Object> security = agent.execute("security_review", context("greenfield", Map.of(), Map.of(
                "apply_changes", Map.of("baseline_modified", false))));
        assertEquals("passed", security.get("status"));
        assertTrue(((List<?>) security.get("findings")).isEmpty());
        Files.writeString(root.resolve("link/PrivacyRepository.java"), "class PrivacyRepository { String ip_address; }");
        Map<String, Object> privacyViolation = agent.execute("security_review", context("brownfield",
                Map.of("acceptance_criteria", List.of("Expose statistics without persisting visitor IP addresses.")),
                Map.of("apply_changes", Map.of("baseline_modified", false))));
        assertEquals("failed", privacyViolation.get("status"));
        assertEquals(false, ((Map<?, ?>) privacyViolation.get("checks")).get("visitor_ip_not_persisted"));
        Map<String, Object> validation = Map.of("status", "passed", "exit_code", 0, "test_count", 1,
                "command", List.of("mvn", "test"), "output_tail", "BUILD SUCCESS");
        Map<String, Object> blocked = agent.execute("release_readiness", context("greenfield", Map.of(), Map.of(
                "tests", Map.of("status", "failed", "exit_code", 1), "repair", Map.of("status", "failed", "final_validation", Map.of("status", "failed", "exit_code", 1)),
                "security_review", security, "implementation", Map.of("criterion_traceability", List.of()),
                "intake", Map.of("open_questions", List.of()), "requirement_approval", Map.of())));
        assertEquals("blocked", blocked.get("decision"));
        Map<String, Object> ready = agent.execute("release_readiness", context("greenfield", Map.of(), Map.of(
                "tests", validation, "repair", Map.of("status", "not_needed", "final_validation", validation),
                "security_review", security, "implementation", Map.of("criterion_traceability", List.of()),
                "intake", Map.of("open_questions", List.of()), "requirement_approval", Map.of())));
        assertEquals("ready_for_human_review", ready.get("decision"));
        assertEquals(false, agent.execute("release_promotion", context("greenfield", Map.of(), Map.of())).get("external_deployment"));
    }

    @Test
    void emitsEngineeringSummaryAndUsesInjectedTestStageRunner() throws Exception {
        LocalAgentBackend agent = new LocalAgentBackend(path -> Map.of("status", "passed", "exit_code", 0));
        Map<String, Object> summary = agent.execute("documentation", context("greenfield", Map.of(), Map.of(
                "intake", Map.of("title", "Demo", "normalized_problem", "Build links.", "acceptance_criteria", List.of("Create links.")),
                "architecture", Map.of("components", List.of(Map.of("name", "API", "responsibility", "Serve requests."))))));
        assertTrue(String.valueOf(summary.get("markdown")).contains("# Engineering summary: Demo"));
        assertEquals("passed", agent.execute("tests", context("greenfield", Map.of(), Map.of())).get("status"));
        assertEquals("incomplete", agent.execute("implementation", context("greenfield", request(List.of("Create a versioned API.")), Map.of())).get("status"));
    }

    private AgentContext context(String scenario, Map<String, Object> request, Map<String, Map<String, Object>> outputs) {
        return new AgentContext(workspace, "run-123", scenario, request, outputs);
    }

    private static Map<String, Object> request(List<String> criteria) {
        return Map.of("title", "Demo shortener", "request", "Build a URL shortener.",
                "acceptance_criteria", criteria, "mode", "greenfield");
    }
}
