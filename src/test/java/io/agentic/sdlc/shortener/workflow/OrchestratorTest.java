package io.agentic.sdlc.shortener.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrchestratorTest {
    private static final Path PROJECT = Path.of("").toAbsolutePath();
    @TempDir Path temp;

    @Test
    void greenfieldCreatesCandidateDiffThenPromotesAndVerifiesRollback() throws Exception {
        Path workspace = workspace("greenfield");
        Orchestrator orchestrator = orchestrator(workspace, new PassingBackend());
        String baselineHash = CandidateWorkspace.fingerprint(workspace.resolve("src"));
        String firstRun = finish(orchestrator, "greenfield");
        String secondRun = finish(orchestrator, "greenfield");
        Path current = workspace.resolve(".agentic/current_release.json");
        assertTrue(Files.readString(current).contains(secondRun));
        Path release = workspace.resolve(".agentic/releases/" + secondRun);
        assertTrue(Files.isRegularFile(release.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(release.resolve("artifacts/engineering_summary.md")));
        assertTrue(Files.isRegularFile(release.resolve("source/src/main/java/io/agentic/sdlc/shortener/generated/RunMarker.java")));
        assertFalse(Files.exists(workspace.resolve("src/main/java/io/agentic/sdlc/shortener/generated/RunMarker.java")));
        assertEquals(baselineHash, CandidateWorkspace.fingerprint(workspace.resolve("src")));
        Map<String, Object> rollback = orchestrator.rollbackRelease(secondRun, "reviewer", "Restore the previous reviewed bundle.");
        assertEquals(firstRun, rollback.get("current_release") instanceof Map<?, ?> releasePointer ? releasePointer.get("run_id") : null);
        assertEquals(true, rollback.get("baseline_verified"));
        orchestrator.rollbackRelease(firstRun, "reviewer", "Clear the local pointer after the demo.");
        assertFalse(Files.exists(current));
        assertThrows(IllegalStateException.class, () -> orchestrator.rollbackRelease(secondRun, "reviewer", "Repeat rollback."));
        assertEquals(2L, ((Number) orchestrator.metrics().get("rollback_count")).longValue());
    }

    @Test
    void changeApprovalReviewsTheExactDiffAndDenialDiscardsCandidate() throws Exception {
        Path workspace = workspace("deny-change");
        Orchestrator orchestrator = orchestrator(workspace, new PassingBackend());
        String runId = String.valueOf(orchestrator.createRun("greenfield", Map.of()).get("run_id"));
        Map<String, Object> review = orchestrator.execute(runId);
        assertEquals("WAITING_APPROVAL", review.get("status"));
        assertEquals("changes", review.get("pending_checkpoint"));
        Map<?, ?> proposal = (Map<?, ?>) stage(review, "implementation").get("output");
        assertTrue(((List<?>) proposal.get("changed_files")).contains("src/main/java/io/agentic/sdlc/shortener/generated/RunMarker.java"));
        assertTrue(String.valueOf(proposal.get("diff")).contains("public class RunMarker"));
        assertFalse(Files.exists(workspace.resolve(".agentic/runs/" + runId + "/candidate")));
        Map<String, Object> denied = orchestrator.approve(runId, "changes", "reviewer", "The proposed test does not match the requirement.", "deny");
        assertEquals("FAILED", denied.get("status"));
        assertFalse(Files.exists(workspace.resolve(".agentic/runs/" + runId + "/candidate")));
        assertTrue(orchestrator.events(runId).stream().anyMatch(event -> event.eventType().equals("APPROVAL_DENIED")
                && event.payload().containsKey("proposal_sha256")));
    }

    @Test
    void ambiguousRequestPausesUntilMeasurableCriteriaAreRevised() throws Exception {
        Path workspace = workspace("ambiguous");
        Orchestrator orchestrator = orchestrator(workspace, new PassingBackend());
        String runId = String.valueOf(orchestrator.createRun("ambiguous", Map.of()).get("run_id"));
        Map<String, Object> waiting = orchestrator.execute(runId);
        assertEquals("WAITING_APPROVAL", waiting.get("status"));
        assertEquals("requirements", waiting.get("pending_checkpoint"));
        assertEquals("PENDING", stage(waiting, "implementation").get("status"));
        assertThrows(IllegalStateException.class, () -> orchestrator.approve(runId, "requirements", "reviewer",
                "Accept implied assumptions.", "approve"));

        orchestrator.revise(runId, Map.of("request", "Return expired links as gone and expose aggregate click counts.",
                "acceptance_criteria", List.of("Return HTTP 410 for links after their expiry instant.",
                        "Expose click count per link without storing visitor IP addresses.")), "product-owner");
        Map<String, Object> changes = orchestrator.execute(runId);
        assertEquals("changes", changes.get("pending_checkpoint"));
        orchestrator.approve(runId, "changes", "reviewer", "The exact source and regression test diff is acceptable.", "approve");
        Map<String, Object> release = orchestrator.execute(runId);
        assertEquals("WAITING_APPROVAL", release.get("status"), release.toString());
        assertEquals("release", release.get("pending_checkpoint"));
        orchestrator.approve(runId, "release", "reviewer", "Candidate build, tests and policy checks passed.", "approve");
        assertEquals("SUCCEEDED", orchestrator.execute(runId).get("status"));
        assertTrue(Files.isRegularFile(workspace.resolve(".agentic/runs/" + runId + "/revisions/request-1/request.json")));
        assertTrue(orchestrator.events(runId).stream().anyMatch(event -> event.eventType().equals("PATCH_PROPOSED")));
        assertTrue(orchestrator.events(runId).stream().anyMatch(event -> event.eventType().equals("BUILD_EXECUTION")));
    }

    @Test
    void revisedRequirementsSupersedeOldArtifactsAndApprovals() throws Exception {
        Path workspace = workspace("revision");
        Orchestrator orchestrator = orchestrator(workspace, new PassingBackend());
        String runId = String.valueOf(orchestrator.createRun("ambiguous", Map.of()).get("run_id"));
        orchestrator.execute(runId);
        Map<String, Object> revised = orchestrator.revise(runId, Map.of(
                "request", "Add safe URL validation and expiry.",
                "acceptance_criteria", List.of("Reject unsafe HTTP targets and local addresses.", "Return 410 for expired links.")), "reviewer");
        assertEquals(1, revised.get("replan_count"));
        assertEquals("CREATED", revised.get("status"));
        assertTrue(((List<?>) revised.get("stages")).stream().allMatch(stage -> "PENDING".equals(((Map<?, ?>) stage).get("status"))));
        assertTrue(Files.isRegularFile(workspace.resolve(".agentic/runs/" + runId + "/revisions/request-1/artifacts/intake.json")));
        assertTrue(orchestrator.events(runId).stream().anyMatch(event -> event.eventType().equals("REPLAN_TRIGGERED")));
    }

    @Test
    void sourceChangesDuringApprovalArchiveOldEvidenceAndInvalidateThePlan() throws Exception {
        Path workspace = workspace("source-change");
        Orchestrator orchestrator = orchestrator(workspace, new PassingBackend());
        String runId = String.valueOf(orchestrator.createRun("ambiguous", Map.of()).get("run_id"));
        orchestrator.execute(runId);
        Files.writeString(workspace.resolve("src/main/java/io/agentic/sdlc/shortener/link/ShortenerService.java"),
                "\n// source changed during review\n", java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IllegalStateException.class, () -> orchestrator.approve(runId, "requirements", "reviewer", "Approve old plan.", "approve"));
        assertEquals("CREATED", orchestrator.summary(runId).get("status"));
        assertEquals(1, orchestrator.summary(runId).get("replan_count"));
        Path revisions = workspace.resolve(".agentic/runs/" + runId + "/revisions/source-1/artifacts");
        assertTrue(Files.isRegularFile(revisions.resolve("repo_reasoning.json")));
        assertFalse(Files.exists(workspace.resolve(".agentic/runs/" + runId + "/artifacts/repo_reasoning.json")));
    }

    @Test
    void boundedProviderRetryAndConservativeFallbackAreRecorded() throws Exception {
        Path workspace = workspace("retry-fallback");
        AtomicInteger attempts = new AtomicInteger();
        LocalAgentBackend local = new PassingBackend();
        AgentBackend flaky = (stage, context) -> {
            if (stage.equals("architecture") && attempts.getAndIncrement() == 0) throw new IllegalStateException("temporary provider timeout");
            return local.execute(stage, context);
        };
        Orchestrator retrying = new Orchestrator(workspace, flaky, new ConservativeFallbackBackend(), 2, 3);
        String runId = String.valueOf(retrying.createRun("greenfield", Map.of()).get("run_id"));
        Map<String, Object> result = retrying.execute(runId);
        assertEquals("changes", result.get("pending_checkpoint"));
        assertEquals(2, stage(result, "architecture").get("attempts"));
        assertTrue(retrying.events(runId).stream().anyMatch(event -> event.eventType().equals("STAGE_RETRY")));

        AgentBackend unavailable = (stage, context) -> {
            if (stage.equals("implementation")) throw new AgentUnavailable("configured provider is offline");
            return local.execute(stage, context);
        };
        Orchestrator fallback = new Orchestrator(workspace("fallback"), unavailable, new ConservativeFallbackBackend(), 2, 3);
        String fallbackId = String.valueOf(fallback.createRun("greenfield", Map.of()).get("run_id"));
        assertEquals("FAILED", fallback.execute(fallbackId).get("status"));
        assertTrue(fallback.events(fallbackId).stream().anyMatch(event -> event.eventType().equals("AGENT_FALLBACK")
                && "unvalidated code is never accepted".equals(event.payload().get("success_policy"))));
    }

    @Test
    void failedBuildIsFedToBoundedRepairAndMetricsReflectTheRecovery() throws Exception {
        Path workspace = workspace("repair");
        AtomicInteger builds = new AtomicInteger();
        PassingBackend backend = new PassingBackend() {
            @Override
            public Map<String, Object> execute(String stageId, AgentContext context) throws Exception {
                if (stageId.equals("tests") && builds.getAndIncrement() == 0) {
                    return validation("failed", 1, "Compilation failed in RunMarker.java");
                }
                if (stageId.equals("repair")) {
                    return Map.of("status", "proposed", "diagnosis", "Correct the generated state accessor.",
                            "changes", List.of(change("src/main/java/io/agentic/sdlc/shortener/generated/RunMarker.java", "update",
                                    "package io.agentic.sdlc.shortener.generated; public class RunMarker { public String state() { return \"fixed\"; } }\n",
                                    List.of("AC-1"), "Fix the compiler error reported by the candidate build.")));
                }
                return super.execute(stageId, context);
            }
        };
        Orchestrator orchestrator = new Orchestrator(workspace, backend, new ConservativeFallbackBackend(), 2, 3, 1);
        String runId = String.valueOf(orchestrator.createRun("greenfield", Map.of()).get("run_id"));
        orchestrator.execute(runId);
        orchestrator.approve(runId, "changes", "reviewer", "Approve source and test proposal.", "approve");
        Map<String, Object> waiting = orchestrator.execute(runId);
        assertEquals("release", waiting.get("pending_checkpoint"));
        Map<?, ?> repair = (Map<?, ?>) stage(waiting, "repair").get("output");
        assertEquals("recovered", repair.get("status"));
        assertEquals(1, repair.get("attempts"));
        assertEquals(2L, ((Number) orchestrator.metrics().get("build_executions")).longValue());
        assertEquals(1L, ((Number) orchestrator.metrics().get("build_failures")).longValue());
        assertEquals(1L, ((Number) orchestrator.metrics().get("successful_repairs")).longValue());
        assertTrue(orchestrator.events(runId).stream().anyMatch(event -> event.eventType().equals("REPAIR_RECOVERED")));
    }

    @Test
    void exhaustedRepairLimitBlocksReadinessAndDiscardsCandidate() throws Exception {
        Path workspace = workspace("repair-exhausted");
        PassingBackend backend = new PassingBackend() {
            @Override
            public Map<String, Object> execute(String stageId, AgentContext context) throws Exception {
                if (stageId.equals("tests")) return validation("failed", 1, "The candidate still fails.");
                if (stageId.equals("repair")) return Map.of("status", "proposed", "diagnosis", "Attempt a bounded fix.",
                        "changes", List.of(change("src/main/java/io/agentic/sdlc/shortener/generated/RunMarker.java", "update",
                                "package io.agentic.sdlc.shortener.generated; public class RunMarker { public String state() { return \"attempted\"; } }\n",
                                List.of("AC-1"), "Attempt to address the reported compiler failure.")));
                return super.execute(stageId, context);
            }
        };
        Orchestrator orchestrator = new Orchestrator(workspace, backend, new ConservativeFallbackBackend(), 2, 3, 1);
        String baselineHash = CandidateWorkspace.fingerprint(workspace.resolve("src"));
        String runId = String.valueOf(orchestrator.createRun("greenfield", Map.of()).get("run_id"));
        orchestrator.execute(runId);
        orchestrator.approve(runId, "changes", "reviewer", "Approve generated patch for candidate validation.", "approve");
        Map<String, Object> stopped = orchestrator.execute(runId);

        assertEquals("FAILED", stopped.get("status"));
        assertEquals("failed", ((Map<?, ?>) stage(stopped, "repair").get("output")).get("status"));
        assertEquals(1L, ((Number) orchestrator.metrics().get("repair_attempts")).longValue());
        assertFalse(Files.exists(workspace.resolve(".agentic/runs/" + runId + "/candidate")));
        assertEquals(baselineHash, CandidateWorkspace.fingerprint(workspace.resolve("src")));
    }

    @Test
    void candidateEditedAfterValidationCannotReceiveReleaseApproval() throws Exception {
        Path workspace = workspace("candidate-tamper");
        Orchestrator orchestrator = orchestrator(workspace, new PassingBackend());
        String runId = String.valueOf(orchestrator.createRun("greenfield", Map.of()).get("run_id"));
        orchestrator.execute(runId);
        orchestrator.approve(runId, "changes", "reviewer", "Approve exact implementation diff.", "approve");
        Map<String, Object> release = orchestrator.execute(runId);
        assertEquals("release", release.get("pending_checkpoint"));
        Path candidateSource = workspace.resolve(".agentic/runs/" + runId
                + "/candidate/src/main/java/io/agentic/sdlc/shortener/generated/RunMarker.java");
        Files.writeString(candidateSource, "// changed after validation\n", java.nio.file.StandardOpenOption.APPEND);

        assertThrows(IllegalStateException.class, () -> orchestrator.approve(runId, "release", "reviewer",
                "Approve the candidate.", "approve"));
        assertEquals("FAILED", orchestrator.summary(runId).get("status"));
        assertFalse(Files.exists(workspace.resolve(".agentic/runs/" + runId + "/candidate")));
        assertTrue(orchestrator.events(runId).stream().anyMatch(event -> event.eventType().equals("CANDIDATE_INTEGRITY_FAILURE")));
    }

    @Test
    void untestedAcceptanceCriterionBlocksReadinessAndRemovesCandidate() throws Exception {
        Path workspace = workspace("missing-test");
        PassingBackend missingTestForFirstCriterion = new PassingBackend() {
            @Override
            public Map<String, Object> execute(String stageId, AgentContext context) throws Exception {
                if (stageId.equals("implementation")) {
                    return proposal(context, List.of(
                            change("src/main/java/io/agentic/sdlc/shortener/generated/Feature.java", "create",
                                    "package io.agentic.sdlc.shortener.generated; public class Feature {}\n", List.of("AC-1", "AC-2"), "New implementation."),
                            change("src/test/java/io/agentic/sdlc/shortener/generated/FeatureTest.java", "create",
                                    testSource(), List.of("AC-2"), "Test only the second acceptance criterion.")));
                }
                return super.execute(stageId, context);
            }
        };
        Orchestrator orchestrator = orchestrator(workspace, missingTestForFirstCriterion);
        String runId = String.valueOf(orchestrator.createRun("greenfield", Map.of("acceptance_criteria", List.of(
                "Create the short-link API.", "Track clicks without storing IP addresses."))).get("run_id"));
        Map<String, Object> pending = orchestrator.execute(runId);
        orchestrator.approve(runId, "changes", "reviewer", "Review generated patch.", "approve");
        Map<String, Object> failed = orchestrator.execute(runId);
        assertEquals("FAILED", failed.get("status"));
        Map<?, ?> readiness = (Map<?, ?>) stage(failed, "release_readiness").get("output");
        assertEquals("blocked", readiness.get("decision"));
        assertTrue(((List<?>) readiness.get("blockers")).stream().anyMatch(item -> String.valueOf(item).contains("AC-1")));
        assertFalse(Files.exists(workspace.resolve(".agentic/runs/" + runId + "/candidate")));
    }

    @Test
    void securityAndDocumentationRunConcurrentlyAfterVerifiedCandidate() throws Exception {
        Path workspace = workspace("parallel-review");
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        PassingBackend local = new PassingBackend() {
            @Override
            public Map<String, Object> execute(String stage, AgentContext context) throws Exception {
                if (List.of("security_review", "documentation").contains(stage)) {
                    int now = active.incrementAndGet();
                    maximum.accumulateAndGet(now, Math::max);
                    ready.countDown();
                    try {
                        assertTrue(ready.await(5, TimeUnit.SECONDS), "Independent review stages did not start together.");
                        return super.execute(stage, context);
                    } finally {
                        active.decrementAndGet();
                    }
                }
                return super.execute(stage, context);
            }
        };
        Orchestrator orchestrator = orchestrator(workspace, local);
        String runId = String.valueOf(orchestrator.createRun("greenfield", Map.of()).get("run_id"));
        orchestrator.execute(runId);
        orchestrator.approve(runId, "changes", "reviewer", "Approve exact diff.", "approve");
        Map<String, Object> result = orchestrator.execute(runId);
        assertEquals("release", result.get("pending_checkpoint"));
        assertEquals(2, maximum.get());
    }

    @Test
    void operatorCanStopAndOversizedAgentOutputFailsClosed() throws Exception {
        Orchestrator orchestrator = orchestrator(workspace("stop"), new PassingBackend());
        String stoppedId = String.valueOf(orchestrator.createRun("ambiguous", Map.of()).get("run_id"));
        orchestrator.execute(stoppedId);
        assertEquals("STOPPED", orchestrator.requestStop(stoppedId, "operator", "Wait for product clarification.").get("status"));
        assertTrue(orchestrator.events(stoppedId).stream().anyMatch(event -> event.eventType().equals("SAFE_STOP")));

        Path workspace = workspace("large-output");
        LocalAgentBackend local = new LocalAgentBackend(path -> validation("passed", 0, "BUILD SUCCESS"));
        AgentBackend oversized = (stage, context) -> stage.equals("repo_reasoning")
                ? Map.of("content", "x".repeat(3_000_001)) : local.execute(stage, context);
        Orchestrator large = new Orchestrator(workspace, oversized, new ConservativeFallbackBackend(), 1, 1);
        String runId = String.valueOf(large.createRun("greenfield", Map.of()).get("run_id"));
        assertEquals("FAILED", large.execute(runId).get("status"));
        assertTrue(Files.isRegularFile(workspace.resolve(".agentic/runs/" + runId + "/artifacts/intake.json")));
        assertFalse(Files.exists(workspace.resolve(".agentic/runs/" + runId + "/artifacts/repo_reasoning.json")));
        assertTrue(large.events(runId).stream().anyMatch(event -> event.eventType().equals("SAFE_STOP")));
    }

    private String finish(Orchestrator orchestrator, String scenario) {
        String runId = String.valueOf(orchestrator.createRun(scenario, Map.of()).get("run_id"));
        Map<String, Object> waitingForChanges = orchestrator.execute(runId);
        assertEquals("WAITING_APPROVAL", waitingForChanges.get("status"), waitingForChanges.toString());
        assertEquals("changes", waitingForChanges.get("pending_checkpoint"));
        orchestrator.approve(runId, "changes", "reviewer", "Review source diff, risks and test plan.", "approve");
        Map<String, Object> waitingForRelease = orchestrator.execute(runId);
        assertEquals("WAITING_APPROVAL", waitingForRelease.get("status"), waitingForRelease.toString());
        assertEquals("release", waitingForRelease.get("pending_checkpoint"));
        orchestrator.approve(runId, "release", "reviewer", "Candidate tests and policy evidence passed.", "approve");
        assertEquals("SUCCEEDED", orchestrator.execute(runId).get("status"));
        return runId;
    }

    private Orchestrator orchestrator(Path workspace, AgentBackend backend) {
        return new Orchestrator(workspace, backend, new ConservativeFallbackBackend(), 2, 3);
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

    private static Map<String, Object> proposal(AgentContext context, List<Map<String, Object>> changes) {
        return Map.of("status", "proposed", "summary", "Add a requirement-specific feature and its regression test.",
                "changes", changes, "risks", List.of("Candidate-only source changes require review."),
                "test_plan", List.of("Compile all Java sources and run all Maven tests."));
    }

    private static Map<String, Object> change(String path, String operation, String content, List<String> ids, String rationale) {
        return Map.of("path", path, "operation", operation, "content", content,
                "criterion_ids", ids, "rationale", rationale);
    }

    private static String testSource() {
        return "package io.agentic.sdlc.shortener.generated;\n"
                + "import static org.junit.jupiter.api.Assertions.assertEquals;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "class RunMarkerTest { @Test void markerIsGenerated() { assertEquals(\"initial\", new RunMarker().state()); } }\n";
    }

    private static Map<String, Object> validation(String status, int exitCode, String output) {
        return Map.of("status", status, "exit_code", exitCode, "duration_seconds", 0.01,
                "command", List.of("mvn", "--batch-mode", "--no-transfer-progress", "test"), "output_tail", output);
    }

    private static Map<String, Object> proposal(AgentContext context, List<Map<String, Object>> changes,
                                               String title) {
        return Map.of("status", "proposed", "summary", title, "changes", changes,
                "risks", List.of(), "test_plan", List.of("Run Maven test suite."));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stage(Map<String, Object> summary, String name) {
        return ((List<Map<String, Object>>) summary.get("stages")).stream()
                .filter(item -> item.get("stage_id").equals(name)).findFirst().orElseThrow();
    }

    private static class PassingBackend extends LocalAgentBackend {
        @Override
        public Map<String, Object> execute(String stageId, AgentContext context) throws Exception {
            if (stageId.equals("implementation")) {
                List<?> rawCriteria = (List<?>) context.priorOutputs().get("intake").get("acceptance_criteria");
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < rawCriteria.size(); i++) ids.add("AC-" + (i + 1));
                List<Map<String, Object>> changes = List.of(
                        change("src/main/java/io/agentic/sdlc/shortener/generated/RunMarker.java", "create",
                                "package io.agentic.sdlc.shortener.generated; public class RunMarker { public String state() { return \"initial\"; } }\n",
                                ids, "A generated class is the minimal source fixture for this test run."),
                        change("src/test/java/io/agentic/sdlc/shortener/generated/RunMarkerTest.java", "create",
                                testSource(), ids, "A generated regression test is associated with every criterion."));
                return proposal(context, changes);
            }
            if (stageId.equals("tests")) return validation("passed", 0, "BUILD SUCCESS: candidate test suite passed.");
            return super.execute(stageId, context);
        }
    }
}
