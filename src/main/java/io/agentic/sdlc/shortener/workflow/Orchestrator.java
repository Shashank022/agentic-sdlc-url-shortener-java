package io.agentic.sdlc.shortener.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import tools.jackson.databind.json.JsonMapper;

/** Persistent DAG runner. Agents produce reviewable artifacts; this class owns gates and side effects. */
public class Orchestrator {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "STOPPED", "ROLLED_BACK");
    private final Path workspace;
    private final Path stateRoot;
    private final WorkflowStore store;
    private final AgentBackend backend;
    private final AgentBackend fallback;
    private final int maxAttempts;
    private final int maxWorkers;
    private final JsonMapper json = JsonMapper.builder().build();

    public Orchestrator(Path workspace) {
        this(workspace, new LocalAgentBackend(), new LocalAgentBackend(), 2, 3);
    }

    public Orchestrator(Path workspace, AgentBackend backend, int maxAttempts) {
        this(workspace, backend, new LocalAgentBackend(), maxAttempts, 3);
    }

    public Orchestrator(Path workspace, AgentBackend backend, AgentBackend fallback,
                        int maxAttempts, int maxWorkers) {
        if (maxAttempts < 1 || maxWorkers < 1) throw new IllegalArgumentException("Attempts and workers must be positive.");
        this.workspace = workspace.toAbsolutePath().normalize();
        this.stateRoot = this.workspace.resolve(".agentic");
        this.store = new WorkflowStore(stateRoot.resolve("state.sqlite3"));
        this.backend = backend;
        this.fallback = fallback;
        this.maxAttempts = maxAttempts;
        this.maxWorkers = maxWorkers;
    }

    public Map<String, Object> createRun(String scenarioName, Map<String, Object> overrides) {
        Scenario scenario = ScenarioCatalog.get(scenarioName);
        Map<String, Object> request = new LinkedHashMap<>(scenario.asRequest());
        if (overrides != null) request.putAll(overrides);
        RunState run = new RunState();
        run.runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        run.traceId = UUID.randomUUID().toString();
        run.scenario = scenarioName;
        run.workspace = workspace.toString();
        run.request = request;
        run.sourceFingerprint = sourceFingerprint();
        run.createdAt = WorkflowStore.now();
        run.updatedAt = run.createdAt;
        WorkflowGraph.STAGES.forEach(stage -> run.stages.add(new StageState(stage.id())));
        store.createRun(run);
        store.appendEvent(run.runId, "RUN_CREATED", null, "human", Map.of(
                "scenario", scenarioName, "title", request.getOrDefault("title", scenario.title()),
                "source_fingerprint", run.sourceFingerprint));
        writeJson(runDirectory(run.runId).resolve("request.json"), request);
        return summary(run.runId);
    }

    public Map<String, Object> execute(String runId) {
        String owner = WorkflowStore.owner();
        store.acquireLease(runId, owner, 300);
        try {
            return executeLeased(runId, owner);
        } finally {
            store.releaseLease(runId, owner);
        }
    }

    private Map<String, Object> executeLeased(String runId, String owner) {
        RunState run = store.getRun(runId);
        if (TERMINAL.contains(run.status)) return summary(runId);
        refreshSourceContext(run);
        run = store.getRun(runId);
        if (run.stopRequested || run.status.equals("STOP_REQUESTED")) {
            safeStop(run, "Stop requested by operator.");
            return summary(runId);
        }
        for (StageState stage : run.stages) {
            if (stage.status.equals("RUNNING")) {
                stage.status = "PENDING";
                stage.error = "Recovered after runner exit; retrying from the stage boundary.";
                store.appendEvent(runId, "STALE_STAGE_RECOVERED", stage.stageId, "policy",
                        Map.of("action", "retry from stage boundary"));
            }
        }
        run.status = "RUNNING";
        store.saveRun(run);
        store.appendEvent(runId, "RUN_RESUMED", null, "system", Map.of("runner", owner));

        while (true) {
            store.renewLease(runId, owner, 300);
            run = store.getRun(runId);
            if (run.stopRequested || run.status.equals("STOP_REQUESTED")) {
                safeStop(run, "Stop requested between stage groups.");
                return summary(runId);
            }
            List<WorkflowStage> ready = readyStages(run);
            if (ready.isEmpty()) {
                if (run.stages.stream().allMatch(stage -> stage.status.equals("SUCCEEDED"))) {
                    run.status = "SUCCEEDED";
                    run.finishedAt = WorkflowStore.now();
                    store.saveRun(run);
                    store.appendEvent(runId, "RUN_SUCCEEDED", null, "system", Map.of("metrics", store.metrics()));
                } else {
                    run.status = "FAILED";
                    run.finishedAt = WorkflowStore.now();
                    store.saveRun(run);
                    store.appendEvent(runId, "GRAPH_STALLED", null, "policy", Map.of("reason", "No runnable stage remained."));
                }
                return summary(runId);
            }
            WorkflowStage gate = ready.stream().filter(stage -> stage.checkpoint() != null).findFirst().orElse(null);
            if (gate != null) {
                if (handleGate(run, gate)) return summary(runId);
                continue;
            }
            boolean failed = executeReadyGroup(run, ready);
            if (failed) {
                run = store.getRun(runId);
                run.status = "FAILED";
                run.finishedAt = WorkflowStore.now();
                store.saveRun(run);
                store.appendEvent(runId, "SAFE_STOP", null, "policy",
                        Map.of("reason", "A required stage failed; release promotion was blocked."));
                removeStaging(runId);
                return summary(runId);
            }
        }
    }

    private boolean executeReadyGroup(RunState run, List<WorkflowStage> ready) {
        Map<String, Map<String, Object>> priorOutputs = outputs(run);
        List<Callable<StageResult>> tasks = new ArrayList<>();
        for (WorkflowStage stage : ready) {
            StageState state = run.stage(stage.id());
            state.status = "RUNNING";
            state.attempts++;
            state.error = null;
            tasks.add(() -> executeStage(run, stage, priorOutputs));
        }
        store.saveRun(run);
        List<StageResult> results = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxWorkers, tasks.size()));
        try {
            for (Future<StageResult> result : executor.invokeAll(tasks)) {
                try {
                    results.add(result.get());
                } catch (Exception exception) {
                    results.add(new StageResult("unknown", null, 0, 1, exception.toString()));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workflow execution interrupted.", exception);
        } finally {
            executor.shutdownNow();
        }
        boolean failed = false;
        for (StageResult result : results) {
            StageState state = run.stage(result.stageId());
            state.elapsedMillis = result.elapsedMillis();
            state.attempts = Math.max(state.attempts, result.attempts());
            if (result.error() != null || result.output() == null) {
                failed = true;
                state.status = "FAILED";
                state.error = result.error() == null ? "Agent returned no output." : result.error();
                store.appendEvent(run.runId, "STAGE_FAILED", state.stageId, "policy",
                        Map.of("error", state.error, "attempts", state.attempts));
                continue;
            }
            state.output = result.output();
            try {
                validateOutput(state.stageId, result.output());
                writeStageArtifact(run.runId, state.stageId, result.output());
                if (state.stageId.equals("release_promotion")) promoteRelease(run);
            } catch (Exception exception) {
                failed = true;
                state.status = "FAILED";
                state.error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                store.appendEvent(run.runId, "STAGE_FAILED", state.stageId, "policy",
                        Map.of("error", state.error, "attempts", state.attempts));
                continue;
            }
            state.status = "SUCCEEDED";
            state.error = null;
            store.appendEvent(run.runId, "STAGE_SUCCEEDED", state.stageId,
                    WorkflowGraph.byId().get(state.stageId).agent(), Map.of(
                            "attempts", state.attempts,
                            "elapsed_seconds", result.elapsedMillis() / 1000.0,
                            "output_sha256", digest(result.output())));
            if (state.attempts > 1) {
                store.appendEvent(run.runId, "STAGE_RECOVERED", state.stageId, "policy",
                        Map.of("attempts", state.attempts, "recovery_seconds", result.elapsedMillis() / 1000.0));
            }
        }
        store.saveRun(run);
        return failed;
    }

    private StageResult executeStage(RunState run, WorkflowStage stage,
                                     Map<String, Map<String, Object>> priorOutputs) {
        long started = System.nanoTime();
        int attempts = 0;
        Exception last = null;
        AgentContext context = new AgentContext(workspace, run.runId, run.scenario, run.request, priorOutputs);
        for (int number = 1; number <= maxAttempts; number++) {
            attempts = number;
            try {
                return new StageResult(stage.id(), backend.execute(stage.id(), context), elapsed(started), attempts, null);
            } catch (AgentUnavailable unavailable) {
                store.appendEvent(run.runId, "AGENT_FALLBACK", stage.id(), "policy", Map.of(
                        "reason", safeMessage(unavailable), "provider", backend.getClass().getSimpleName(),
                        "fallback", "local-deterministic"));
                try {
                    return new StageResult(stage.id(), fallback.execute(stage.id(), context), elapsed(started), attempts, null);
                } catch (Exception fallbackFailure) {
                    last = fallbackFailure;
                    break;
                }
            } catch (Exception exception) {
                last = exception;
                if (number < maxAttempts) {
                    store.appendEvent(run.runId, "STAGE_RETRY", stage.id(), "policy",
                            Map.of("attempt", number + 1, "max_attempts", maxAttempts));
                    try {
                        Thread.sleep(Math.min(50L * (1L << Math.min(number - 1, 4)), 500L));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        last = interrupted;
                        break;
                    }
                }
            }
        }
        return new StageResult(stage.id(), null, elapsed(started), attempts,
                last == null ? "Agent failed without an error." : last.getClass().getSimpleName() + ": " + safeMessage(last));
    }

    private boolean handleGate(RunState run, WorkflowStage stage) {
        if (stage.id().equals("requirement_approval")) {
            Map<String, Object> intake = run.stage("intake").output;
            List<?> questions = asList(intake.get("open_questions"));
            if (questions.isEmpty()) {
                Map<String, Object> output = Map.of("decision", "not_required", "checkpoint", "requirements",
                        "reason", "Acceptance criteria are concrete.");
                stageSucceeded(run, stage, output);
                store.appendEvent(run.runId, "APPROVAL_AUTO_PASSED", stage.id(), "policy", output);
                return false;
            }
        } else if (stage.id().equals("release_approval")) {
            Map<String, Object> readiness = run.stage("release_readiness").output;
            if (!"ready_for_human_review".equals(readiness.get("decision"))) {
                run.stage(stage.id()).status = "FAILED";
                run.stage(stage.id()).error = "Release readiness policy blocked promotion.";
                run.status = "FAILED";
                run.finishedAt = WorkflowStore.now();
                store.saveRun(run);
                store.appendEvent(run.runId, "POLICY_BLOCKED", stage.id(), "policy",
                        Map.of("blockers", asList(readiness.get("blockers"))));
                return true;
            }
        }
        String checkpoint = stage.checkpoint();
        store.requestApproval(run.runId, checkpoint);
        run.stage(stage.id()).status = "WAITING_APPROVAL";
        run.pendingCheckpoint = checkpoint;
        run.status = "WAITING_APPROVAL";
        store.saveRun(run);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("checkpoint", checkpoint);
        payload.put("questions", checkpoint.equals("requirements")
                ? asList(run.stage("intake").output.get("open_questions")) : List.of());
        store.appendEvent(run.runId, "APPROVAL_REQUESTED", stage.id(), "policy", payload);
        return true;
    }

    public Map<String, Object> approve(String runId, String checkpoint, String actor,
                                       String rationale, String decision) {
        requireText(actor, "Approval actor");
        requireText(rationale, "Approval rationale");
        String normalizedDecision = decision == null ? "approve" : decision.toLowerCase();
        if (!normalizedDecision.equals("approve") && !normalizedDecision.equals("deny")) {
            throw new IllegalArgumentException("Decision must be approve or deny.");
        }
        String owner = WorkflowStore.owner();
        store.acquireLease(runId, owner, 300);
        try {
            RunState run = store.getRun(runId);
            if (!run.status.equals("WAITING_APPROVAL") || !checkpoint.equals(run.pendingCheckpoint)) {
                throw new IllegalStateException("Run is not waiting for the " + checkpoint + " checkpoint.");
            }
            if (!run.sourceFingerprint.equals(sourceFingerprint())) {
                refreshSourceContext(run);
                throw new IllegalStateException("Source changed during review; downstream stages were invalidated. Resume the run.");
            }
            String stageId = checkpoint.equals("requirements") ? "requirement_approval" : "release_approval";
            String approvalDecision = normalizedDecision.equals("approve") ? "approved" : "denied";
            store.decideApproval(runId, checkpoint, approvalDecision, actor.trim(), rationale.trim());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("decision", approvalDecision);
            output.put("checkpoint", checkpoint);
            output.put("actor", actor.trim());
            output.put("rationale", rationale.trim());
            StageState stage = run.stage(stageId);
            stage.output = output;
            if (approvalDecision.equals("approved")) {
                stage.status = "SUCCEEDED";
                stage.error = null;
                writeStageArtifact(runId, stageId, output);
                run.status = "CREATED";
                run.pendingCheckpoint = null;
                store.appendEvent(runId, "APPROVAL_RECORDED", stageId, actor.trim(), output);
            } else {
                stage.status = "DENIED";
                stage.error = rationale.trim();
                run.status = "FAILED";
                run.pendingCheckpoint = null;
                run.finishedAt = WorkflowStore.now();
                store.appendEvent(runId, "APPROVAL_DENIED", stageId, actor.trim(), output);
                store.appendEvent(runId, "SAFE_STOP", null, "policy", Map.of("reason", "Human approval was denied."));
            }
            store.saveRun(run);
            return summary(runId);
        } finally {
            store.releaseLease(runId, owner);
        }
    }

    public Map<String, Object> revise(String runId, Map<String, Object> request, String actor) {
        requireText(actor, "Revision actor");
        if (request == null || request.isEmpty()) throw new IllegalArgumentException("A revised request is required.");
        RunState run = store.getRun(runId);
        if (TERMINAL.contains(run.status)) throw new IllegalStateException("A terminal run cannot be revised.");
        run.request.putAll(request);
        run.sourceFingerprint = sourceFingerprint();
        run.status = "CREATED";
        run.finishedAt = null;
        run.stopRequested = false;
        run.pendingCheckpoint = null;
        run.replanCount++;
        run.stages.forEach(stage -> {
            stage.status = "PENDING";
            stage.attempts = 0;
            stage.elapsedMillis = 0;
            stage.output = new LinkedHashMap<>();
            stage.error = null;
        });
        store.supersedeApprovals(runId);
        clearArtifacts(runId);
        store.saveRun(run);
        store.appendEvent(runId, "REPLAN_TRIGGERED", null, actor.trim(),
                Map.of("replan_count", run.replanCount, "source_fingerprint", run.sourceFingerprint));
        return summary(runId);
    }

    public Map<String, Object> requestStop(String runId, String actor, String reason) {
        requireText(actor, "Stop actor");
        requireText(reason, "Stop reason");
        RunState run = store.getRun(runId);
        if (TERMINAL.contains(run.status)) return summary(runId);
        run.stopRequested = true;
        if (!run.status.equals("RUNNING")) {
            run.status = "STOPPED";
            run.finishedAt = WorkflowStore.now();
        } else {
            run.status = "STOP_REQUESTED";
        }
        store.saveRun(run);
        store.appendEvent(runId, "SAFE_STOP", null, actor.trim(), Map.of("reason", reason.trim()));
        return summary(runId);
    }

    private void safeStop(RunState run, String reason) {
        run.status = "STOPPED";
        run.pendingCheckpoint = null;
        run.finishedAt = WorkflowStore.now();
        store.saveRun(run);
        store.appendEvent(run.runId, "SAFE_STOP", null, "policy", Map.of("reason", reason));
        removeStaging(run.runId);
    }

    public Map<String, Object> rollbackRelease(String runId, String actor, String rationale) {
        requireText(actor, "Rollback actor");
        requireText(rationale, "Rollback rationale");
        RunState run = store.getRun(runId);
        if (!run.status.equals("SUCCEEDED")) {
            throw new IllegalStateException("Only a succeeded release can be rolled back.");
        }
        Path release = stateRoot.resolve("releases").resolve(runId);
        Path previous = release.resolve("previous_release.json");
        if (!Files.exists(previous)) throw new IllegalStateException("No promoted release is available to roll back.");
        Path current = stateRoot.resolve("current_release.json");
        if (!Files.exists(current) || !runId.equals(readJson(current).get("run_id"))) {
            throw new IllegalStateException("Only the active local release can be rolled back.");
        }
        Map<String, Object> before = readJson(previous);
        Object previousValue = before.get("release");
        if (previousValue == null) {
            try { Files.deleteIfExists(current); }
            catch (IOException exception) { throw new IllegalStateException("Could not remove current release pointer.", exception); }
        } else {
            writeJson(current, previousValue);
        }
        run.status = "ROLLED_BACK";
        run.finishedAt = WorkflowStore.now();
        store.saveRun(run);
        store.appendEvent(runId, "ROLLBACK_COMPLETED", null, actor.trim(), Map.of(
                "rationale", rationale.trim(), "restored_run_id", previousValue instanceof Map<?, ?> map ? map.get("run_id") : "none"));
        return Map.of("rollback_status", "completed", "run_id", runId,
                "current_release", previousValue == null ? Map.of() : previousValue);
    }

    public Map<String, Object> summary(String runId) {
        RunState run = store.getRun(runId);
        List<Map<String, Object>> stages = run.stages.stream().map(stage -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stage_id", stage.stageId);
            item.put("status", stage.status);
            item.put("attempts", stage.attempts);
            item.put("elapsed_ms", stage.elapsedMillis);
            item.put("output", stage.output == null || stage.output.isEmpty() ? null : stage.output);
            item.put("error", stage.error);
            return item;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", run.runId);
        result.put("trace_id", run.traceId);
        result.put("scenario", run.scenario);
        result.put("status", run.status);
        result.put("pending_checkpoint", run.pendingCheckpoint);
        result.put("replan_count", run.replanCount);
        result.put("created_at", run.createdAt);
        result.put("updated_at", run.updatedAt);
        result.put("stages", stages);
        result.put("artifact_dir", ".agentic/runs/" + run.runId + "/artifacts");
        result.put("metrics", store.metrics());
        return result;
    }

    public List<WorkflowEvent> events(String runId) {
        store.getRun(runId);
        return store.events(runId);
    }

    public Map<String, Object> metrics() {
        return store.metrics();
    }

    public List<Map<String, Object>> listRuns() {
        return store.runs().stream().limit(20).map(run -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("run_id", run.runId);
            item.put("scenario", run.scenario);
            item.put("status", run.status);
            item.put("created_at", run.createdAt);
            item.put("replan_count", run.replanCount);
            return item;
        }).toList();
    }

    public Map<String, Object> scenarios() {
        Map<String, Object> result = new LinkedHashMap<>();
        ScenarioCatalog.all().forEach((name, scenario) -> result.put(name, Map.of(
                "title", scenario.title(), "request", scenario.request(),
                "acceptance_criteria", scenario.acceptanceCriteria(), "mode", scenario.mode())));
        return result;
    }

    public RunState runState(String runId) {
        return store.getRun(runId);
    }

    private List<WorkflowStage> readyStages(RunState run) {
        Map<String, WorkflowStage> graph = WorkflowGraph.byId();
        return WorkflowGraph.STAGES.stream().filter(stage -> run.stage(stage.id()).status.equals("PENDING"))
                .filter(stage -> stage.dependencies().stream().allMatch(dependency -> run.stage(dependency).status.equals("SUCCEEDED")))
                .map(stage -> graph.get(stage.id())).toList();
    }

    private Map<String, Map<String, Object>> outputs(RunState run) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        run.stages.stream().filter(stage -> stage.status.equals("SUCCEEDED") && stage.output != null)
                .forEach(stage -> result.put(stage.stageId, Map.copyOf(stage.output)));
        return result;
    }

    private void stageSucceeded(RunState run, WorkflowStage workflowStage, Map<String, Object> output) {
        StageState stage = run.stage(workflowStage.id());
        stage.status = "SUCCEEDED";
        stage.output = output;
        store.saveRun(run);
        writeStageArtifact(run.runId, stage.stageId, output);
    }

    private void validateOutput(String stageId, Map<String, Object> output) {
        if (output == null || output.isEmpty()) throw new IllegalStateException("Agent output for " + stageId + " must be a non-empty object.");
        String encoded;
        try {
            encoded = json.writeValueAsString(output);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent output could not be serialized safely.", exception);
        }
        if (encoded.length() > 1_000_000) throw new IllegalStateException("Agent output exceeds the 1 MB artifact limit.");
        if (stageId.equals("documentation") && !"engineering_summary.md".equals(output.get("path"))) {
            throw new IllegalStateException("Documentation can only target the approved engineering summary path.");
        }
        if (stageId.equals("release_promotion") && !Boolean.FALSE.equals(output.get("external_deployment"))) {
            throw new IllegalStateException("The reference workflow is not allowed to deploy externally.");
        }
    }

    private void refreshSourceContext(RunState run) {
        String current = sourceFingerprint();
        if (run.sourceFingerprint.equals(current)) return;
        Set<String> invalidated = WorkflowGraph.descendants("repo_reasoning");
        for (StageState stage : run.stages) {
            if (invalidated.contains(stage.stageId)) {
                stage.status = "PENDING";
                stage.attempts = 0;
                stage.elapsedMillis = 0;
                stage.output = new LinkedHashMap<>();
                stage.error = null;
            }
        }
        run.sourceFingerprint = current;
        run.status = "CREATED";
        run.finishedAt = null;
        run.pendingCheckpoint = null;
        run.replanCount++;
        store.supersedeApprovals(run.runId);
        clearStageArtifacts(run.runId, invalidated);
        store.saveRun(run);
        store.appendEvent(run.runId, "REPLAN_TRIGGERED", null, "policy",
                Map.of("reason", "Source fingerprint changed.", "invalidated_stages", invalidated,
                        "replan_count", run.replanCount, "source_fingerprint", current));
    }

    private String sourceFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> paths = new ArrayList<>();
            Path source = workspace.resolve("src/main/java");
            if (Files.exists(source)) {
                try (var stream = Files.walk(source)) {
                    paths.addAll(stream.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).sorted().toList());
                }
            }
            Path pom = workspace.resolve("pom.xml");
            if (Files.exists(pom)) paths.add(pom);
            for (Path path : paths) {
                digest.update(workspace.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(path));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint the Java workspace.", exception);
        }
    }

    private void writeStageArtifact(String runId, String stageId, Map<String, Object> output) {
        writeJson(runDirectory(runId).resolve("artifacts").resolve(stageId + ".json"), output);
        Object markdown = output.get("markdown");
        if (stageId.equals("documentation") && markdown instanceof String text) {
            try {
                atomicText(runDirectory(runId).resolve("artifacts").resolve("engineering_summary.md"), text);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not write the engineering summary artifact.", exception);
            }
        }
    }

    private void promoteRelease(RunState run) throws IOException {
        Path releases = stateRoot.resolve("releases");
        Path target = releases.resolve(run.runId);
        if (Files.exists(target)) throw new IllegalStateException("Release bundle already exists for " + run.runId + ".");
        Path staging = stateRoot.resolve("staging").resolve(run.runId);
        deleteTree(staging);
        Path artifactSource = runDirectory(run.runId).resolve("artifacts");
        Path artifactTarget = staging.resolve("artifacts");
        Files.createDirectories(artifactTarget);
        if (Files.exists(artifactSource)) {
            try (var paths = Files.walk(artifactSource)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    Path destination = artifactTarget.resolve(artifactSource.relativize(path));
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path pointer = stateRoot.resolve("current_release.json");
        Object previous = Files.exists(pointer) ? readJson(pointer) : null;
        Map<String, Object> readiness = run.stage("release_readiness").output;
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("run_id", run.runId);
        manifest.put("trace_id", run.traceId);
        manifest.put("scenario", run.scenario);
        manifest.put("promoted_at", WorkflowStore.now());
        manifest.put("release_scope", readiness.get("release_scope"));
        manifest.put("external_deployment", false);
        manifest.put("source_fingerprint", run.sourceFingerprint);
        manifest.put("approval", run.stage("release_approval").output);
        writeJson(staging.resolve("manifest.json"), manifest);
        Map<String, Object> previousWrapper = new LinkedHashMap<>();
        previousWrapper.put("release", previous);
        writeJson(staging.resolve("previous_release.json"), previousWrapper);
        Files.createDirectories(releases);
        move(staging, target);
        writeJson(pointer, Map.of("run_id", run.runId, "path", ".agentic/releases/" + run.runId,
                "promoted_at", manifest.get("promoted_at")));
        deleteTree(stateRoot.resolve("staging").resolve(run.runId));
    }

    private Map<String, Object> readJson(Path path) {
        try {
            return json.readValue(Files.readString(path), Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read JSON artifact " + path + ".", exception);
        }
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            atomicText(path, json.writeValueAsString(value) + "\n");
        } catch (Exception exception) {
            throw new IllegalStateException("Could not write workflow artifact " + path + ".", exception);
        }
    }

    private static void atomicText(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        move(temporary, path);
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private void clearArtifacts(String runId) {
        deleteTree(runDirectory(runId).resolve("artifacts"));
        removeStaging(runId);
    }

    private void removeStaging(String runId) {
        deleteTree(stateRoot.resolve("staging").resolve(runId));
    }

    private Path runDirectory(String runId) {
        return stateRoot.resolve("runs").resolve(runId);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not clean workspace-scoped staging artifacts.", exception);
        }
    }

    private String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash stage output.", exception);
        }
    }

    private void clearStageArtifacts(String runId, Set<String> stageIds) {
        Path artifacts = runDirectory(runId).resolve("artifacts");
        for (String stageId : stageIds) {
            try {
                Files.deleteIfExists(artifacts.resolve(stageId + ".json"));
                if (stageId.equals("documentation")) Files.deleteIfExists(artifacts.resolve("engineering_summary.md"));
            } catch (IOException exception) {
                throw new IllegalStateException("Could not remove stale stage artifacts.", exception);
            }
        }
        removeStaging(runId);
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
    }

    private record StageResult(String stageId, Map<String, Object> output, long elapsedMillis,
                               int attempts, String error) { }
}
