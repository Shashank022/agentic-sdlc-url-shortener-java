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
import java.util.LinkedHashSet;
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
    private final int maxRepairAttempts;
    private final JsonMapper json = JsonMapper.builder().build();

    public Orchestrator(Path workspace) {
        this(workspace, OpenAiCompatibleAgentBackend.fromEnvironment(new ConservativeFallbackBackend()),
                new ConservativeFallbackBackend(), 2, 3, 2);
    }

    public Orchestrator(Path workspace, AgentBackend backend, int maxAttempts) {
        this(workspace, backend, new ConservativeFallbackBackend(), maxAttempts, 3, 2);
    }

    public Orchestrator(Path workspace, AgentBackend backend, AgentBackend fallback,
                        int maxAttempts, int maxWorkers) {
        this(workspace, backend, fallback, maxAttempts, maxWorkers, 2);
    }

    public Orchestrator(Path workspace, AgentBackend backend, AgentBackend fallback,
                        int maxAttempts, int maxWorkers, int maxRepairAttempts) {
        if (maxAttempts < 1 || maxWorkers < 1 || maxRepairAttempts < 0) throw new IllegalArgumentException("Attempts and workers must be positive; repair attempts cannot be negative.");
        this.workspace = workspace.toAbsolutePath().normalize();
        this.stateRoot = this.workspace.resolve(".agentic");
        this.store = new WorkflowStore(stateRoot.resolve("state.sqlite3"));
        this.backend = backend;
        this.fallback = fallback;
        this.maxAttempts = maxAttempts;
        this.maxWorkers = maxWorkers;
        this.maxRepairAttempts = maxRepairAttempts;
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
        if (validatedCandidateChanged(run)) {
            failCandidateIntegrity(run, "Candidate source changed after its last validation; no release approval will be requested.");
            return summary(runId);
        }
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
            if (validatedCandidateChanged(run)) {
                failCandidateIntegrity(run, "Candidate source changed after its last validation; downstream work was stopped.");
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
                removeCandidate(runId);
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
            Map<String, Object> stageEvidence = new LinkedHashMap<>();
            stageEvidence.put("attempts", state.attempts);
            stageEvidence.put("elapsed_seconds", result.elapsedMillis() / 1000.0);
            stageEvidence.put("output_sha256", digest(result.output()));
            stageEvidence.put("backend", backendDetails(backend));
            store.appendEvent(run.runId, "STAGE_SUCCEEDED", state.stageId,
                    WorkflowGraph.byId().get(state.stageId).agent(), stageEvidence);
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
        if (stage.id().equals("apply_changes")) {
            try {
                return new StageResult(stage.id(), applyApprovedChanges(run), elapsed(started), 1, null);
            } catch (Exception exception) {
                return new StageResult(stage.id(), null, elapsed(started), 1, exception.getClass().getSimpleName() + ": " + safeMessage(exception));
            }
        }
        if (stage.id().equals("repair")) {
            try {
                return new StageResult(stage.id(), repair(run, priorOutputs), elapsed(started), 1, null);
            } catch (Exception exception) {
                return new StageResult(stage.id(), null, elapsed(started), 1, exception.getClass().getSimpleName() + ": " + safeMessage(exception));
            }
        }
        if (stage.id().equals("release_readiness")) {
            return new StageResult(stage.id(), evaluateReadiness(run, priorOutputs), elapsed(started), 1, null);
        }
        if (stage.id().equals("release_promotion")) {
            return new StageResult(stage.id(), Map.of("result", "promoted", "target", ".agentic/releases/" + run.runId,
                    "external_deployment", false), elapsed(started), 1, null);
        }
        Path stageWorkspace = Set.of("tests", "security_review", "documentation", "release_readiness").contains(stage.id())
                ? candidatePath(run.runId) : workspace;
        AgentContext context = new AgentContext(stageWorkspace, run.runId, run.scenario, run.request, priorOutputs);
        for (int number = 1; number <= maxAttempts; number++) {
            attempts = number;
            try {
                if (stage.id().equals("implementation")) {
                    Map<String, Object> evidence = new LinkedHashMap<>(backendDetails(backend));
                    evidence.put("attempt", number);
                    evidence.put("scenario", run.scenario);
                    store.appendEvent(run.runId, "CODEGEN_ATTEMPT", stage.id(), backend.getClass().getSimpleName(), evidence);
                }
                Map<String, Object> output = backend.execute(stage.id(), context);
                if (stage.id().equals("intake")) output = groundIntake(run, output);
                if (stage.id().equals("implementation")) output = enrichImplementation(run, output, priorOutputs);
                if (stage.id().equals("tests")) {
                    output = buildEvidence(run, output);
                    recordBuild(run.runId, "initial", output);
                }
                if (stage.id().equals("documentation")) output = groundDocumentation(run, output, priorOutputs);
                return new StageResult(stage.id(), output, elapsed(started), attempts, null);
            } catch (AgentUnavailable unavailable) {
                store.appendEvent(run.runId, "AGENT_FALLBACK", stage.id(), "policy", Map.of(
                        "reason", safeMessage(unavailable), "provider", backend.getClass().getSimpleName(),
                        "fallback", "offline-conservative", "success_policy", "unvalidated code is never accepted"));
                try {
                    Map<String, Object> output = fallback.execute(stage.id(), context);
                    if (stage.id().equals("intake")) output = groundIntake(run, output);
                    if (stage.id().equals("implementation")) output = enrichImplementation(run, output, priorOutputs);
                    if (stage.id().equals("tests")) {
                        output = buildEvidence(run, output);
                        recordBuild(run.runId, "initial", output);
                    }
                    if (stage.id().equals("documentation")) output = groundDocumentation(run, output, priorOutputs);
                    return new StageResult(stage.id(), output, elapsed(started), attempts, null);
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

    private Map<String, Object> groundIntake(RunState run, Map<String, Object> output) {
        List<String> submitted = strings(run.request.get("acceptance_criteria"));
        Map<String, Object> grounded = new LinkedHashMap<>(output);
        if (submitted.isEmpty()) {
            grounded.put("acceptance_criteria", List.of());
            List<?> questions = asList(output.get("open_questions"));
            if (questions.isEmpty()) grounded.put("open_questions", List.of(
                    "Please provide measurable acceptance criteria before source changes can be proposed."));
            grounded.put("ambiguity", "high");
        } else {
            grounded.put("acceptance_criteria", submitted);
        }
        return grounded;
    }

    private Map<String, Object> enrichImplementation(RunState run, Map<String, Object> output,
                                                     Map<String, Map<String, Object>> priorOutputs) throws IOException {
        List<String> criteria = strings(priorOutputs.getOrDefault("intake", Map.of()).get("acceptance_criteria"));
        List<ProposedChange> changes = ChangeSet.parse(output, criteria);
        boolean greenfield = run.scenario.equals("greenfield");
        for (ProposedChange change : changes) {
            if (greenfield) {
                if (!change.operation().equals("create")) {
                    throw new IllegalStateException("Greenfield candidates start from the Maven starter; every source operation must create a file.");
                }
                continue;
            }
            boolean exists = CandidateWorkspace.safeRegularFile(workspace, change.path());
            if (change.operation().equals("create") && exists) throw new IllegalStateException("Create target already exists in the baseline: " + change.path());
            if (!change.operation().equals("create") && !exists) throw new IllegalStateException("Proposed " + change.operation() + " target is missing from the baseline: " + change.path());
        }
        String diff = CandidateWorkspace.diff(workspace, changes, greenfield);
        List<Map<String, Object>> traceability = new ArrayList<>();
        for (int index = 0; index < criteria.size(); index++) {
            String id = "AC-" + (index + 1);
            List<String> files = changes.stream().filter(change -> change.criterionIds().contains(id))
                    .map(ProposedChange::path).toList();
            List<String> tests = changes.stream().filter(change -> change.path().startsWith("src/test/java/")
                            && change.criterionIds().contains(id)).map(ProposedChange::path).toList();
            traceability.add(Map.of("criterion_id", id, "criterion", criteria.get(index), "files", files, "test_files", tests));
        }
        Map<String, Object> enriched = new LinkedHashMap<>(output);
        enriched.put("changed_files", CandidateWorkspace.changedPaths(changes));
        enriched.put("diff", diff);
        enriched.put("criterion_traceability", traceability);
        enriched.put("proposal_sha256", digest(changes));
        enriched.put("write_boundary", "Changes are applied only to a disposable candidate workspace after approval of this exact proposal.");
        store.appendEvent(run.runId, "PATCH_PROPOSED", "implementation", backend.getClass().getSimpleName(), Map.of(
                "changed_files", CandidateWorkspace.changedPaths(changes), "proposal_sha256", enriched.get("proposal_sha256"),
                "acceptance_criteria", traceability));
        return enriched;
    }

    private Map<String, Object> applyApprovedChanges(RunState run) throws Exception {
        Map<String, Object> proposal = run.stage("implementation").output;
        Map<String, Object> approval = run.stage("change_approval").output;
        if (!"approved".equals(approval.get("decision"))
                || !String.valueOf(proposal.get("proposal_sha256")).equals(String.valueOf(approval.get("proposal_sha256")))) {
            throw new IllegalStateException("The change approval does not match the current source proposal.");
        }
        List<String> criteria = strings(run.stage("intake").output.get("acceptance_criteria"));
        List<ProposedChange> changes = ChangeSet.parse(proposal, criteria);
        Path candidate = CandidateWorkspace.create(workspace, candidatePath(run.runId), run.scenario.equals("greenfield"));
        CandidateWorkspace.apply(candidate, changes);
        String candidateHash = CandidateWorkspace.fingerprint(candidate);
        String baselineHash = sourceFingerprint();
        if (!run.sourceFingerprint.equals(baselineHash)) {
            CandidateWorkspace.deleteTree(candidate);
            throw new IllegalStateException("Baseline source changed after approval; candidate was discarded.");
        }
        Map<String, Object> output = Map.of("status", "applied", "changed_files", CandidateWorkspace.changedPaths(changes),
                "candidate_fingerprint", candidateHash, "baseline_fingerprint", baselineHash,
                "baseline_modified", false, "workspace", ".agentic/runs/" + run.runId + "/candidate");
        store.appendEvent(run.runId, "PATCH_APPLIED_TO_CANDIDATE", "apply_changes", "policy", output);
        return output;
    }

    private Map<String, Object> repair(RunState run, Map<String, Map<String, Object>> priorOutputs) {
        Map<String, Object> initial = priorOutputs.getOrDefault("tests", Map.of());
        if ("passed".equals(initial.get("status"))) {
            return Map.of("status", "not_needed", "attempts", 0, "initial_validation", initial,
                    "final_validation", initial, "changed_files", List.of());
        }
        Map<String, Object> lastValidation = initial;
        List<Map<String, Object>> attempts = new ArrayList<>();
        List<String> criteria = strings(priorOutputs.getOrDefault("intake", Map.of()).get("acceptance_criteria"));
        for (int attempt = 1; attempt <= maxRepairAttempts; attempt++) {
            long started = System.nanoTime();
            Map<String, Map<String, Object>> repairContextOutputs = new LinkedHashMap<>(priorOutputs);
            repairContextOutputs.put("tests", lastValidation);
            repairContextOutputs.put("repair_history", Map.of("attempts", attempts));
            AgentContext context = new AgentContext(candidatePath(run.runId), run.runId, run.scenario, run.request,
                    Map.copyOf(repairContextOutputs));
            try {
                Map<String, Object> patch;
                try {
                    patch = backend.execute("repair", context);
                } catch (AgentUnavailable unavailable) {
                    store.appendEvent(run.runId, "AGENT_FALLBACK", "repair", "policy", Map.of(
                            "reason", safeMessage(unavailable), "provider", backend.getClass().getSimpleName(),
                            "fallback", "offline-conservative", "success_policy", "unvalidated code is never accepted"));
                    patch = fallback.execute("repair", context);
                }
                List<ProposedChange> changes = ChangeSet.parse(patch, criteria, false);
                CandidateWorkspace.apply(candidatePath(run.runId), changes);
                List<Map<String, Object>> traceability = changes.stream().map(change -> Map.<String, Object>of(
                        "path", change.path(), "criterion_ids", change.criterionIds(), "rationale", change.rationale())).toList();
                store.appendEvent(run.runId, "REPAIR_ATTEMPT", "repair", backend.getClass().getSimpleName(), Map.of(
                        "attempt", attempt, "diagnosis", patch.getOrDefault("diagnosis", "not supplied"),
                        "changed_files", CandidateWorkspace.changedPaths(changes), "prior_exit_code", lastValidation.getOrDefault("exit_code", -1),
                        "criterion_traceability", traceability, "backend", backendDetails(backend)));
                AgentContext testContext = new AgentContext(candidatePath(run.runId), run.runId, run.scenario, run.request,
                        Map.copyOf(repairContextOutputs));
                lastValidation = buildEvidence(run, backend.execute("tests", testContext));
                recordBuild(run.runId, "repair-" + attempt, lastValidation);
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("attempt", attempt);
                evidence.put("diagnosis", patch.getOrDefault("diagnosis", "not supplied"));
                evidence.put("changed_files", CandidateWorkspace.changedPaths(changes));
                evidence.put("risks", asList(patch.get("risks")));
                evidence.put("criterion_traceability", traceability);
                evidence.put("validation", lastValidation);
                evidence.put("elapsed_seconds", elapsed(started) / 1000.0);
                attempts.add(evidence);
                if ("passed".equals(lastValidation.get("status")) && validTestEvidence(lastValidation)) {
                    store.appendEvent(run.runId, "REPAIR_RECOVERED", "repair", "policy", Map.of(
                            "attempt", attempt, "elapsed_seconds", elapsed(started) / 1000.0));
                    return Map.of("status", "recovered", "attempts", attempts.size(), "attempt_history", attempts,
                            "initial_validation", initial, "final_validation", lastValidation,
                            "changed_files", CandidateWorkspace.changedPaths(changes));
                }
            } catch (Exception exception) {
                Map<String, Object> failedAttempt = Map.of("attempt", attempt, "status", "incomplete",
                        "reason", safeMessage(exception), "prior_exit_code", lastValidation.getOrDefault("exit_code", -1));
                attempts.add(failedAttempt);
                store.appendEvent(run.runId, "REPAIR_ATTEMPT", "repair", "policy", failedAttempt);
                break;
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "failed");
        output.put("attempts", attempts.size());
        output.put("attempt_history", attempts);
        output.put("initial_validation", initial);
        output.put("final_validation", lastValidation);
        output.put("changed_files", List.of());
        output.put("safe_stop", true);
        return output;
    }

    private Map<String, Object> evaluateReadiness(RunState run, Map<String, Map<String, Object>> priorOutputs) {
        Map<String, Object> implementation = priorOutputs.getOrDefault("implementation", Map.of());
        Map<String, Object> repairResult = priorOutputs.getOrDefault("repair", Map.of());
        Map<String, Object> validation = map(repairResult.get("final_validation"));
        Map<String, Object> security = priorOutputs.getOrDefault("security_review", Map.of());
        Map<String, Object> documentation = priorOutputs.getOrDefault("documentation", Map.of());
        List<String> blockers = new ArrayList<>();
        if (!validTestEvidence(validation)) blockers.add("Candidate compilation/tests did not pass with complete command and output evidence.");
        if (!Set.of("not_needed", "recovered").contains(String.valueOf(repairResult.get("status")))) {
            blockers.add("Validation failed and bounded repair did not recover the candidate.");
        }
        if (!"passed".equals(security.get("status"))) blockers.add("Candidate security/policy checks did not pass.");
        if (!(documentation.get("markdown") instanceof String markdown) || markdown.isBlank()) blockers.add("Verified implementation documentation is missing.");
        for (Object item : asList(implementation.get("criterion_traceability"))) {
            if (item instanceof Map<?, ?> criterion && asList(criterion.get("test_files")).isEmpty()) {
                blockers.add("Acceptance criterion " + criterion.get("criterion_id") + " has no generated regression test file.");
            }
        }
        if (!asList(run.stage("intake").output.get("open_questions")).isEmpty()
                && !"approved".equals(run.stage("requirement_approval").output.get("decision"))) {
            blockers.add("Requirement questions remain unresolved.");
        }
        List<String> changedFiles = finalChangedFiles(implementation, repairResult);
        String finalDiff = "";
        String candidateFingerprint = "missing";
        try {
            finalDiff = CandidateWorkspace.diffSnapshot(workspace, candidatePath(run.runId), changedFiles,
                    run.scenario.equals("greenfield"));
            candidateFingerprint = CandidateWorkspace.fingerprint(candidatePath(run.runId));
        } catch (IOException exception) {
            blockers.add("Final candidate diff or fingerprint could not be produced for review.");
        }
        List<Object> risks = new ArrayList<>(asList(implementation.get("risks")));
        for (Object item : asList(repairResult.get("attempt_history"))) {
            if (item instanceof Map<?, ?> attempt) risks.addAll(asList(attempt.get("risks")));
        }
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("decision", blockers.isEmpty() ? "ready_for_human_review" : "blocked");
        readiness.put("blockers", blockers);
        readiness.put("release_scope", "reviewable local source bundle; no deployment");
        readiness.put("changed_files", changedFiles);
        readiness.put("final_diff", finalDiff);
        readiness.put("risks", risks.stream().distinct().toList());
        readiness.put("test_plan", asList(implementation.get("test_plan")));
        readiness.put("test_validation", validation);
        readiness.put("security_status", security.getOrDefault("status", "missing"));
        readiness.put("documentation_status", markdownStatus(documentation));
        readiness.put("candidate_fingerprint", candidateFingerprint);
        return readiness;
    }

    private static List<String> finalChangedFiles(Map<String, Object> implementation, Map<String, Object> repair) {
        LinkedHashSet<String> changed = new LinkedHashSet<>(strings(implementation.get("changed_files")));
        changed.addAll(strings(repair.get("changed_files")));
        for (Object item : asList(repair.get("attempt_history"))) {
            if (item instanceof Map<?, ?> attempt) changed.addAll(strings(attempt.get("changed_files")));
        }
        return List.copyOf(changed);
    }

    private Map<String, Object> groundDocumentation(RunState run, Map<String, Object> output,
                                                    Map<String, Map<String, Object>> priorOutputs) {
        Object rawMarkdown = output.get("markdown");
        if (!(rawMarkdown instanceof String markdown) || markdown.isBlank()) {
            throw new IllegalStateException("Documentation agent did not produce an engineering summary.");
        }
        Map<String, Object> validation = map(priorOutputs.getOrDefault("repair", Map.of()).get("final_validation"));
        Map<String, Object> repairResult = priorOutputs.getOrDefault("repair", Map.of());
        Map<String, Object> security = priorOutputs.getOrDefault("security_review", Map.of());
        Map<String, Object> architecture = priorOutputs.getOrDefault("architecture", Map.of());
        Map<String, Object> implementation = priorOutputs.getOrDefault("implementation", Map.of());
        List<String> lines = new ArrayList<>();
        lines.add("\n## Requirement traceability");
        for (Object item : asList(implementation.get("criterion_traceability"))) {
            if (item instanceof Map<?, ?> criterion) lines.add("- " + criterion.get("criterion_id") + ": " + criterion.get("criterion")
                    + "; source/test files: " + String.join(", ", strings(criterion.get("files"))) + " / "
                    + String.join(", ", strings(criterion.get("test_files"))));
        }
        for (Object attempt : asList(repairResult.get("attempt_history"))) {
            if (attempt instanceof Map<?, ?> repairAttempt) {
                for (Object item : asList(repairAttempt.get("criterion_traceability"))) {
                    if (item instanceof Map<?, ?> trace) lines.add("- Repair changed `" + trace.get("path") + "` for "
                            + String.join(", ", strings(trace.get("criterion_ids"))) + ": " + trace.get("rationale"));
                }
            }
        }
        lines.add("\n## Design decisions and trade-offs");
        for (Object item : asList(architecture.get("decisions"))) {
            if (item instanceof Map<?, ?> decision) lines.add("- " + (decision.containsKey("id") ? decision.get("id") : "decision") + ": "
                    + (decision.containsKey("decision") ? decision.get("decision") : "") + " Trade-off: "
                    + (decision.containsKey("tradeoff") ? decision.get("tradeoff") : "not stated"));
        }
        lines.add("\n## Setup and validation commands");
        lines.add("- Build and test: `mvn --batch-mode --no-transfer-progress test`.");
        lines.add("- Run the application, when this candidate includes a Spring Boot entry point: `mvn spring-boot:run`.");
        lines.add("\n## Verified implementation and execution evidence");
        lines.add("- Scenario: `" + run.scenario + "`.");
        lines.add("- Changed files: " + String.join(", ", finalChangedFiles(implementation, repairResult)) + ".");
        lines.add("- Validation status: `" + validation.getOrDefault("status", "missing") + "`; exit code: `" + validation.getOrDefault("exit_code", "missing") + "`.");
        lines.add("- Validation command: `" + String.join(" ", strings(validation.get("command"))) + "`.");
        lines.add("- Validation output tail: `" + String.valueOf(validation.getOrDefault("output_tail", "missing")).replace("`", "'") + "`.");
        lines.add("- Repair outcome: `" + repairResult.getOrDefault("status", "missing") + "`; attempts: " + repairResult.getOrDefault("attempts", 0) + ".");
        lines.add(security.isEmpty() ? "- Security review is a separate required release gate; see `security_review.json`."
                : "- Security review: `" + security.getOrDefault("status", "missing") + "`.");
        for (Object risk : asList(implementation.get("risks"))) lines.add("- Residual risk: " + risk);
        for (Object limit : asList(security.get("limitations"))) lines.add("- Security limitation: " + limit);
        lines.add("- Source changes are isolated in a candidate copy; original workspace fingerprint remains `" + run.sourceFingerprint + "`.");
        lines.add("- Rollback removes the candidate and restores the prior local release pointer. This workflow cannot deploy to production.");
        Map<String, Object> grounded = new LinkedHashMap<>(output);
        grounded.put("markdown", markdown.stripTrailing() + "\n" + String.join("\n", lines) + "\n");
        grounded.put("evidence", Map.of("validation_status", validation.getOrDefault("status", "missing"),
                "exit_code", validation.getOrDefault("exit_code", -1), "changed_files",
                implementation.getOrDefault("changed_files", List.of()),
                "repair_status", repairResult.getOrDefault("status", "missing")));
        return grounded;
    }

    private void recordBuild(String runId, String phase, Map<String, Object> output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase);
        payload.put("status", output.getOrDefault("status", "missing"));
        payload.put("exit_code", output.getOrDefault("exit_code", -1));
        payload.put("command", output.getOrDefault("command", List.of()));
        payload.put("duration_seconds", output.getOrDefault("duration_seconds", 0));
        payload.put("output_tail", output.getOrDefault("output_tail", ""));
        payload.put("candidate_fingerprint", output.getOrDefault("candidate_fingerprint", "missing"));
        payload.put("baseline_unchanged", output.getOrDefault("baseline_unchanged", false));
        store.appendEvent(runId, "BUILD_EXECUTION", phase.equals("initial") ? "tests" : "repair", "test-runner", payload);
    }

    private Map<String, Object> buildEvidence(RunState run, Map<String, Object> validation) throws IOException {
        Map<String, Object> evidence = new LinkedHashMap<>(validation);
        String baseline = sourceFingerprint();
        evidence.put("candidate_fingerprint", CandidateWorkspace.fingerprint(candidatePath(run.runId)));
        evidence.put("baseline_fingerprint", baseline);
        evidence.put("baseline_unchanged", run.sourceFingerprint.equals(baseline));
        evidence.put("workspace_scope", "isolated_candidate");
        return evidence;
    }

    private static boolean validTestEvidence(Map<String, Object> validation) {
        return "passed".equals(validation.get("status")) && validation.get("exit_code") instanceof Number code
                && code.intValue() == 0 && validation.get("test_count") instanceof Number tests && tests.intValue() > 0
                && validation.get("command") instanceof List<?> command && !command.isEmpty()
                && validation.get("output_tail") instanceof String
                && validation.get("candidate_fingerprint") instanceof String
                && Boolean.TRUE.equals(validation.get("baseline_unchanged"));
    }

    private boolean validatedCandidateChanged(RunState run) {
        StageState applied = run.stage("apply_changes");
        StageState repair = run.stage("repair");
        if (!applied.status.equals("SUCCEEDED") || !repair.status.equals("SUCCEEDED") || !Files.isDirectory(candidatePath(run.runId))) return false;
        Map<String, Object> validation = map(repair.output.get("final_validation"));
        Object expected = validation.get("candidate_fingerprint");
        if (!(expected instanceof String fingerprint)) return true;
        try {
            return !fingerprint.equals(CandidateWorkspace.fingerprint(candidatePath(run.runId)));
        } catch (IOException exception) {
            return true;
        }
    }

    private void failCandidateIntegrity(RunState run, String reason) {
        run.status = "FAILED";
        run.finishedAt = WorkflowStore.now();
        run.pendingCheckpoint = null;
        store.saveRun(run);
        store.appendEvent(run.runId, "CANDIDATE_INTEGRITY_FAILURE", null, "policy", Map.of("reason", reason));
        store.appendEvent(run.runId, "SAFE_STOP", null, "policy", Map.of("reason", reason));
        removeCandidate(run.runId);
        removeStaging(run.runId);
    }

    private static String markdownStatus(Map<String, Object> documentation) {
        return documentation.get("markdown") instanceof String markdown && !markdown.isBlank() ? "present" : "missing";
    }

    private Path candidatePath(String runId) {
        return runDirectory(runId).resolve("candidate");
    }

    private void removeCandidate(String runId) {
        try {
            CandidateWorkspace.deleteTree(candidatePath(runId));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not discard the isolated candidate workspace.", exception);
        }
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private boolean handleGate(RunState run, WorkflowStage stage) {
        if (stage.id().equals("requirement_approval")) {
            Map<String, Object> intake = run.stage("intake").output;
            List<?> questions = asList(intake.get("open_questions"));
            List<?> criteria = asList(intake.get("acceptance_criteria"));
            if (questions.isEmpty() && !criteria.isEmpty()) {
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
                removeCandidate(run.runId);
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
        if (checkpoint.equals("changes")) {
            Map<String, Object> proposal = run.stage("implementation").output;
            payload.put("summary", proposal.getOrDefault("summary", "Review generated source changes."));
            payload.put("changed_files", proposal.getOrDefault("changed_files", List.of()));
            payload.put("diff", proposal.getOrDefault("diff", ""));
            payload.put("risks", proposal.getOrDefault("risks", List.of()));
            payload.put("test_plan", proposal.getOrDefault("test_plan", List.of()));
            payload.put("proposal_sha256", proposal.getOrDefault("proposal_sha256", "missing"));
        }
        if (checkpoint.equals("release")) {
            Map<String, Object> readiness = run.stage("release_readiness").output;
            payload.put("readiness", readiness);
            payload.put("changed_files", readiness.getOrDefault("changed_files", List.of()));
            payload.put("final_diff", readiness.getOrDefault("final_diff", ""));
            payload.put("risks", readiness.getOrDefault("risks", List.of()));
            payload.put("test_plan", readiness.getOrDefault("test_plan", List.of()));
            payload.put("validation", run.stage("repair").output.getOrDefault("final_validation", Map.of()));
            payload.put("limitations", asList(run.stage("security_review").output.get("limitations")));
            payload.put("candidate_fingerprint", readiness.getOrDefault("candidate_fingerprint", "missing"));
        }
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
            if (checkpoint.equals("release") && validatedCandidateChanged(run)) {
                String reason = "Candidate source changed after validation; rerun candidate checks before release approval.";
                failCandidateIntegrity(run, reason);
                throw new IllegalStateException(reason);
            }
            if (!run.sourceFingerprint.equals(sourceFingerprint())) {
                refreshSourceContext(run);
                throw new IllegalStateException("Source changed during review; downstream stages were invalidated. Resume the run.");
            }
            if (checkpoint.equals("requirements") && normalizedDecision.equals("approve")
                    && asList(run.stage("intake").output.get("acceptance_criteria")).isEmpty()) {
                throw new IllegalStateException("Add measurable acceptance criteria with revise before approving implementation.");
            }
            String stageId = switch (checkpoint) {
                case "requirements" -> "requirement_approval";
                case "changes" -> "change_approval";
                case "release" -> "release_approval";
                default -> throw new IllegalArgumentException("Unknown approval checkpoint: " + checkpoint);
            };
            String approvalDecision = normalizedDecision.equals("approve") ? "approved" : "denied";
            store.decideApproval(runId, checkpoint, approvalDecision, actor.trim(), rationale.trim());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("decision", approvalDecision);
            output.put("checkpoint", checkpoint);
            output.put("actor", actor.trim());
            output.put("rationale", rationale.trim());
            if (checkpoint.equals("changes")) output.put("proposal_sha256", run.stage("implementation").output.get("proposal_sha256"));
            if (checkpoint.equals("release")) {
                output.put("candidate_fingerprint", run.stage("release_readiness").output.get("candidate_fingerprint"));
            }
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
                removeCandidate(runId);
                removeStaging(runId);
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
        archiveRevision(run, "request-" + (run.replanCount + 1));
        run.request.putAll(request);
        run.sourceFingerprint = sourceFingerprint();
        writeJson(runDirectory(run.runId).resolve("request.json"), run.request);
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
        removeCandidate(runId);
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
            removeCandidate(runId);
            removeStaging(runId);
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
        removeCandidate(run.runId);
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
        removeCandidate(runId);
        String currentFingerprint = sourceFingerprint();
        boolean baselineVerified = run.sourceFingerprint.equals(currentFingerprint);
        store.saveRun(run);
        store.appendEvent(runId, "ROLLBACK_COMPLETED", null, actor.trim(), Map.of(
                "rationale", rationale.trim(), "restored_run_id", previousValue instanceof Map<?, ?> map ? map.get("run_id") : "none",
                "baseline_verified", baselineVerified, "expected_source_fingerprint", run.sourceFingerprint,
                "actual_source_fingerprint", currentFingerprint));
        return Map.of("rollback_status", "completed", "run_id", runId,
                "current_release", previousValue == null ? Map.of() : previousValue,
                "baseline_verified", baselineVerified, "source_fingerprint", currentFingerprint);
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
        if (encoded.length() > 3_000_000) throw new IllegalStateException("Agent output exceeds the 3 MB artifact limit.");
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
        archiveRevision(run, "source-" + (run.replanCount + 1));
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
            Path source = workspace.resolve("src");
            if (Files.exists(source)) {
                try (var stream = Files.walk(source)) {
                    paths.addAll(stream.filter(Files::isRegularFile).sorted().toList());
                }
            }
            Path pom = workspace.resolve("pom.xml");
            if (Files.exists(pom)) paths.add(pom);
            Path mavenConfig = workspace.resolve(".mvn");
            if (Files.exists(mavenConfig)) {
                try (var stream = Files.walk(mavenConfig)) {
                    paths.addAll(stream.filter(Files::isRegularFile).sorted().toList());
                }
            }
            for (String wrapper : List.of("mvnw", "mvnw.cmd")) {
                Path path = workspace.resolve(wrapper);
                if (Files.isRegularFile(path)) paths.add(path);
            }
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
        Path candidate = candidatePath(run.runId);
        if (!Files.isDirectory(candidate)) throw new IllegalStateException("Verified candidate source is missing; release bundle cannot be promoted.");
        String candidateFingerprint = CandidateWorkspace.fingerprint(candidate);
        Object approvedFingerprint = run.stage("release_approval").output.get("candidate_fingerprint");
        if (!(approvedFingerprint instanceof String approved) || !approved.equals(candidateFingerprint)) {
            throw new IllegalStateException("Release approval does not match the final candidate reviewed by the human.");
        }
        CandidateWorkspace.copyTree(candidate, staging.resolve("source"));
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
        Object rawValidation = run.stage("repair").output.getOrDefault("final_validation", Map.of());
        Map<?, ?> validation = rawValidation instanceof Map<?, ?> value ? value : Map.of();
        manifest.put("candidate_fingerprint", validation.containsKey("candidate_fingerprint") ? validation.get("candidate_fingerprint") : "missing");
        manifest.put("changed_files", readiness.getOrDefault("changed_files", List.of()));
        manifest.put("validation", rawValidation);
        manifest.put("security_review", run.stage("security_review").output);
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
        removeCandidate(run.runId);
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
        removeCandidate(runId);
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
        if (stageIds.contains("apply_changes")) removeCandidate(runId);
    }

    private void archiveRevision(RunState run, String revisionName) {
        Path runRoot = runDirectory(run.runId);
        Path revision = runRoot.resolve("revisions").resolve(revisionName);
        try {
            Files.createDirectories(revision);
            Path requestFile = runRoot.resolve("request.json");
            if (Files.isRegularFile(requestFile)) Files.copy(requestFile, revision.resolve("request.json"), StandardCopyOption.REPLACE_EXISTING);
            Path artifacts = runRoot.resolve("artifacts");
            if (Files.isDirectory(artifacts) && !Files.exists(revision.resolve("artifacts"))) {
                CandidateWorkspace.copyTree(artifacts, revision.resolve("artifacts"));
            }
            writeJson(revision.resolve("revision.json"), Map.of("revision", revisionName, "archived_at", WorkflowStore.now(),
                    "source_fingerprint", run.sourceFingerprint, "replan_count", run.replanCount,
                    "run_status", run.status));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not preserve artifacts from the superseded workflow revision.", exception);
        }
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : asList(value)) {
            if (item != null) result.add(String.valueOf(item));
        }
        return List.copyOf(result);
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static Map<String, Object> backendDetails(AgentBackend agent) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", agent instanceof OpenAiCompatibleAgentBackend model ? model.providerName() : agent.getClass().getSimpleName());
        if (agent instanceof OpenAiCompatibleAgentBackend model) details.put("model", model.modelName());
        return details;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required.");
    }

    private record StageResult(String stageId, Map<String, Object> output, long elapsedMillis,
                               int attempts, String error) { }
}
