package io.agentic.sdlc.shortener.workflow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkflowGraph {
    public static final List<WorkflowStage> STAGES = List.of(
            new WorkflowStage("intake", "Normalize requirements", List.of(), "requirements", null),
            new WorkflowStage("repo_reasoning", "Inspect codebase and data flows", List.of("intake"), "codebase", null),
            new WorkflowStage("decomposition", "Decompose work and dependencies", List.of("intake", "repo_reasoning"), "planner", null),
            new WorkflowStage("architecture", "Design components and decisions", List.of("decomposition"), "architect", null),
            new WorkflowStage("requirement_approval", "Approve assumptions and plan", List.of("architecture"), "human", "requirements"),
            new WorkflowStage("implementation", "Map requirements to implementation", List.of("requirement_approval"), "engineer", null),
            new WorkflowStage("tests", "Run unit and integration tests", List.of("implementation"), "test", null),
            new WorkflowStage("security_review", "Review security and policy", List.of("implementation", "architecture"), "security", null),
            new WorkflowStage("documentation", "Generate engineering summary", List.of("implementation", "architecture"), "docs", null),
            new WorkflowStage("release_readiness", "Evaluate release readiness", List.of("tests", "security_review", "documentation"), "release", null),
            new WorkflowStage("release_approval", "Approve release promotion", List.of("release_readiness"), "human", "release"),
            new WorkflowStage("release_promotion", "Promote reviewed artifact bundle", List.of("release_approval"), "release", null));

    static {
        validate(STAGES);
    }

    private WorkflowGraph() { }

    public static Map<String, WorkflowStage> byId() {
        Map<String, WorkflowStage> result = new LinkedHashMap<>();
        STAGES.forEach(stage -> result.put(stage.id(), stage));
        return result;
    }

    public static void validate(List<WorkflowStage> stages) {
        Map<String, WorkflowStage> byId = new LinkedHashMap<>();
        for (WorkflowStage stage : stages) {
            if (byId.put(stage.id(), stage) != null) {
                throw new IllegalArgumentException("Stage identifiers must be unique.");
            }
        }
        for (WorkflowStage stage : stages) {
            for (String dependency : stage.dependencies()) {
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException(stage.id() + " has unknown dependency " + dependency + ".");
                }
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (WorkflowStage stage : stages) {
            visit(stage.id(), byId, visiting, visited);
        }
    }

    private static void visit(String id, Map<String, WorkflowStage> byId, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(id)) {
            throw new IllegalArgumentException("Dependency cycle detected at " + id + ".");
        }
        if (visited.contains(id)) {
            return;
        }
        visiting.add(id);
        byId.get(id).dependencies().forEach(dependency -> visit(dependency, byId, visiting, visited));
        visiting.remove(id);
        visited.add(id);
    }

    public static Set<String> descendants(String stageId) {
        if (!byId().containsKey(stageId)) {
            throw new IllegalArgumentException("Unknown stage " + stageId + ".");
        }
        Set<String> result = new LinkedHashSet<>(Set.of(stageId));
        boolean changed;
        do {
            changed = false;
            for (WorkflowStage stage : STAGES) {
                if (!result.contains(stage.id()) && stage.dependencies().stream().anyMatch(result::contains)) {
                    changed |= result.add(stage.id());
                }
            }
        } while (changed);
        return result;
    }

    public static List<String> topologicalOrder(List<WorkflowStage> stages) {
        validate(stages);
        Map<String, Integer> remaining = new LinkedHashMap<>();
        stages.forEach(stage -> remaining.put(stage.id(), stage.dependencies().size()));
        List<String> order = new ArrayList<>();
        while (order.size() < stages.size()) {
            String next = remaining.entrySet().stream().filter(entry -> entry.getValue() == 0 && !order.contains(entry.getKey()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalArgumentException("Graph is cyclic."));
            order.add(next);
            for (WorkflowStage stage : stages) {
                if (stage.dependencies().contains(next)) {
                    remaining.compute(stage.id(), (ignored, count) -> count - 1);
                }
            }
        }
        return order;
    }
}
