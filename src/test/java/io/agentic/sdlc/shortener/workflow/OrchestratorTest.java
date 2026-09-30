package io.agentic.sdlc.shortener.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrchestratorTest {
    private static final Path PROJECT = Path.of("").toAbsolutePath();
    @TempDir Path temp;

    @Test
    void greenfieldRunPausesForReleaseThenPromotesAndRollsBack() throws Exception {
        Path workspace = workspace("greenfield");
        Orchestrator orchestrator = orchestrator(workspace);
        String firstRun = finish(orchestrator, "greenfield");
        String secondRun = finish(orchestrator, "greenfield");
        Path current = workspace.resolve(".agentic/current_release.json");
        assertTrue(Files.readString(current).contains(secondRun));
        assertTrue(Files.isRegularFile(workspace.resolve(".agentic/releases/" + secondRun + "/manifest.json")));
        assertTrue(Files.isRegularFile(workspace.resolve(".agentic/releases/" + secondRun + "/artifacts/engineering_summary.md")));
        assertEquals(firstRun, orchestrator.rollbackRelease(secondRun, "reviewer", "Restore the previous reviewed bundle.")
                .get("current_release") instanceof Map<?, ?> release ? release.get("run_id") : null);
        orchestrator.rollbackRelease(firstRun, "reviewer", "Clear the local pointer after the demo.");
        assertFalse(Files.exists(current));
        assertThrows(IllegalStateException.class, () -> orchestrator.rollbackRelease(secondRun, "reviewer", "Repeat rollback."));
        assertEquals(2L, ((Number) orchestrator.metrics().get("rollback_count")).longValue());
    }

    @Test
    void ambiguousRequestRequiresTwoHumanCheckpointsAndKeepsAnAuditTrail() throws Exception {
        Path workspace = workspace("ambiguous");
        Orchestrator orchestrator = orchestrator(workspace);
        String runId = String.valueOf(orchestrator.createRun("ambiguous", Map.of()).get("run_id"));
        Map<String, Object> first = orchestrator.execute(runId);
        assertEquals("WAITING_APPROVAL", first.get("status"), first.toString());
        assertEquals("requirements", first.get("pending_checkpoint"));
        assertEquals("WAITING_APPROVAL", stage(first, "requirement_approval").get("status"));
        assertEquals("PENDING", stage(first, "implementation").get("status"));
        orchestrator.approve(runId, "requirements", "reviewer", "Accept optional expiry and aggregate-only analytics.", "approve");
        Map<String, Object> second = orchestrator.execute(runId);
        assertEquals("WAITING_APPROVAL", second.get("status"));
        assertEquals("release", second.get("pending_checkpoint"));
        orchestrator.approve(runId, "release", "reviewer", "Tests and policy checks passed.", "approve");
        assertEquals("SUCCEEDED", orchestrator.execute(runId).get("status"));
        List<WorkflowEvent> events = orchestrator.events(runId);
        assertEquals(2, events.stream().filter(event -> event.eventType().equals("APPROVAL_REQUESTED")).count());
        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("STAGE_SUCCEEDED")
                && event.stageId().equals("security_review")));
        assertTrue(Files.isRegularFile(workspace.resolve(".agentic/state.sqlite3")));
    }

    @Test
    void revisionInvalidatesPriorStagesAndApprovals() throws Exception {
        Orchestrator orchestrator = orchestrator(workspace("revision"));
        String runId = String.valueOf(orchestrator.createRun("ambiguous", Map.of()).get("run_id"));
        orchestrator.execute(runId);
        Map<String, Object> revised = orchestrator.revise(runId, Map.of(
                "request", "Add safe URL validation, optional expiry, and privacy-preserving analytics.",
                "acceptance_criteria", List.of("Reject unsafe HTTP targets and local addresses.",
                        "Return 410 for expired links.", "Count clicks without storing visitor IP addresses.")), "reviewer");
        assertEquals(1, revised.get("replan_count"));
        assertEquals("CREATED", revised.get("status"));
        assertTrue(((List<?>) revised.get("stages")).stream().allMatch(stage -> "PENDING".equals(((Map<?, ?>) stage).get("status"))));
        assertTrue(orchestrator.events(runId).stream().anyMatch(event -> event.eventType().equals("REPLAN_TRIGGERED")));
    }

    @Test
    void sourceChangesDuringApprovalInvalidateThePlanBeforeApproval() throws Exception {
        Path workspace = workspace("source-change");
        Orchestrator orchestrator = orchestrator(workspace);
        String runId = String.valueOf(orchestrator.createRun("ambiguous", Map.of()).get("run_id"));
        orchestrator.execute(runId);
        Files.writeString(workspace.resolve("src/main/java/io/agentic/sdlc/shortener/link/ShortenerService.java"), "\n// source changed during review\n", java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IllegalStateException.class, () -> orchestrator.approve(runId, "requirements", "reviewer", "Approve old plan.", "approve"));
        assertEquals("CREATED", orchestrator.summary(runId).get("status"));
        assertEquals(1, orchestrator.summary(runId).get("replan_count"));
        Path artifacts = workspace.resolve(".agentic/runs/" + runId + "/artifacts");
        assertTrue(Files.isRegularFile(artifacts.resolve("intake.json")));
        assertFalse(Files.exists(artifacts.resolve("repo_reasoning.json")));
    }

    @Test
    void boundedRetriesAndProviderFallbackAreRecorded() throws Exception {
        Path workspace = workspace("retry-fallback");
        AtomicInteger attempts = new AtomicInteger();
        LocalAgentBackend local = new PassingBackend();
        AgentBackend flaky = (stage, context) -> {
            if (stage.equals("architecture") && attempts.getAndIncrement() == 0) throw new IllegalStateException("temporary provider timeout");
            return local.execute(stage, context);
        };
        Orchestrator retrying = new Orchestrator(workspace, flaky, new PassingBackend(), 2, 3);
        String runId = String.valueOf(retrying.createRun("greenfield", Map.of()).get("run_id"));
        Map<String, Object> result = retrying.execute(runId);
        assertEquals("WAITING_APPROVAL", result.get("status"), result.toString());
        assertEquals(2, stage(result, "architecture").get("attempts"));
        assertTrue(retrying.events(runId).stream().anyMatch(event -> event.eventType().equals("STAGE_RETRY")));

        AgentBackend unavailable = (stage, context) -> {
            if (stage.equals("architecture")) throw new AgentUnavailable("configured provider is offline");
            return local.execute(stage, context);
        };
        Orchestrator fallback = new Orchestrator(workspace("fallback"), unavailable, new PassingBackend(), 2, 3);
        String fallbackId = String.valueOf(fallback.createRun("greenfield", Map.of()).get("run_id"));
        assertEquals("WAITING_APPROVAL", fallback.execute(fallbackId).get("status"));
        assertTrue(fallback.events(fallbackId).stream().anyMatch(event -> event.eventType().equals("AGENT_FALLBACK")));
    }

    @Test
    void independentTestsSecurityAndDocumentationStagesRunInParallel() throws Exception {
        Path workspace = workspace("parallel");
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(3);
        LocalAgentBackend local = new PassingBackend();
        AgentBackend parallel = (stage, context) -> {
            if (List.of("tests", "security_review", "documentation").contains(stage)) {
                int now = active.incrementAndGet();
                maximum.accumulateAndGet(now, Math::max);
                ready.countDown();
                try {
                    if (!ready.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Independent stages did not start together.");
                    }
                    return local.execute(stage, context);
                } finally {
                    active.decrementAndGet();
                }
            }
            return local.execute(stage, context);
        };
        Orchestrator orchestrator = new Orchestrator(workspace, parallel, new PassingBackend(), 2, 3);
        String runId = String.valueOf(orchestrator.createRun("greenfield", Map.of()).get("run_id"));
        Map<String, Object> result = orchestrator.execute(runId);
        assertEquals("WAITING_APPROVAL", result.get("status"), result.toString());
        assertEquals(3, maximum.get());
    }

    @Test
    void policyBlocksUnmeasuredCriteriaAndOperatorCanStopSafely() throws Exception {
        Orchestrator orchestrator = orchestrator();
        String blockedId = String.valueOf(orchestrator.createRun("greenfield", Map.of(
                "acceptance_criteria", List.of("Redirect p95 latency must remain below 100 ms at 100 requests per second."))).get("run_id"));
        assertEquals("FAILED", orchestrator.execute(blockedId).get("status"));
        Map<?, ?> readiness = (Map<?, ?>) stage(orchestrator.summary(blockedId), "release_readiness").get("output");
        assertEquals("blocked", readiness.get("decision"));
        assertTrue(((List<?>) readiness.get("blockers")).stream().anyMatch(item -> String.valueOf(item).contains("validation evidence")));

        String stoppedId = String.valueOf(orchestrator.createRun("ambiguous", Map.of()).get("run_id"));
        orchestrator.execute(stoppedId);
        assertEquals("STOPPED", orchestrator.requestStop(stoppedId, "operator", "Wait for product clarification.").get("status"));
        assertTrue(orchestrator.events(stoppedId).stream().anyMatch(event -> event.eventType().equals("SAFE_STOP")));
    }

    @Test
    void deniedApprovalFailsClosedAndMetricsIncludeOnlyTerminalRuns() throws Exception {
        Orchestrator orchestrator = orchestrator();
        String runId = String.valueOf(orchestrator.createRun("ambiguous", Map.of()).get("run_id"));
        orchestrator.execute(runId);
        assertEquals("FAILED", orchestrator.approve(runId, "requirements", "reviewer", "Do not accept implicit assumptions.", "deny").get("status"));
        assertEquals(1, orchestrator.metrics().get("completed_runs"));
        assertThrows(IllegalArgumentException.class, () -> orchestrator.approve(runId, "requirements", "", "Missing actor.", "approve"));
    }

    @Test
    void oversizedAgentOutputFailsClosedBeforeItCanBecomeAnArtifact() throws Exception {
        Path workspace = workspace("large-output");
        AgentBackend oversized = (stage, context) -> Map.of("content", "x".repeat(1_000_001));
        Orchestrator orchestrator = new Orchestrator(workspace, oversized, new PassingBackend(), 1, 1);
        String runId = String.valueOf(orchestrator.createRun("greenfield", Map.of()).get("run_id"));
        assertEquals("FAILED", orchestrator.execute(runId).get("status"));
        assertFalse(Files.exists(workspace.resolve(".agentic/runs/" + runId + "/artifacts/intake.json")));
        assertTrue(orchestrator.events(runId).stream().anyMatch(event -> event.eventType().equals("SAFE_STOP")));
    }

    private String finish(Orchestrator orchestrator, String scenario) {
        String runId = String.valueOf(orchestrator.createRun(scenario, Map.of()).get("run_id"));
        Map<String, Object> waiting = orchestrator.execute(runId);
        assertEquals("WAITING_APPROVAL", waiting.get("status"), waiting.toString());
        assertEquals("release", waiting.get("pending_checkpoint"));
        orchestrator.approve(runId, "release", "reviewer", "Validation evidence reviewed.", "approve");
        assertEquals("SUCCEEDED", orchestrator.execute(runId).get("status"));
        return runId;
    }

    private Orchestrator orchestrator() {
        return new Orchestrator(workspace("run"), new PassingBackend(), 2);
    }

    private Orchestrator orchestrator(Path workspace) {
        return new Orchestrator(workspace, new PassingBackend(), 2);
    }

    private Path workspace(String name) {
        try {
            Path target = Files.createDirectories(temp.resolve(name));
            Path source = PROJECT.resolve("src/main/java");
            try (var paths = Files.walk(source)) {
                for (Path path : paths.toList()) {
                    Path destination = target.resolve(PROJECT.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(destination);
                    else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Files.copy(PROJECT.resolve("pom.xml"), target.resolve("pom.xml"), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare temporary Java workspace.", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stage(Map<String, Object> summary, String name) {
        return ((List<Map<String, Object>>) summary.get("stages")).stream()
                .filter(item -> item.get("stage_id").equals(name)).findFirst().orElseThrow();
    }

    private static class PassingBackend extends LocalAgentBackend {
        @Override
        public Map<String, Object> execute(String stageId, AgentContext context) throws Exception {
            if (stageId.equals("tests")) {
                return Map.of("status", "passed", "exit_code", 0, "duration_seconds", 0.01,
                        "command", List.of("mvn", "test"), "output_tail", "5 tests passed");
            }
            return super.execute(stageId, context);
        }
    }
}
