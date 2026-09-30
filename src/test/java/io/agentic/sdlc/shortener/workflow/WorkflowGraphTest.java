package io.agentic.sdlc.shortener.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowGraphTest {
    @Test
    void defaultGraphIsAcyclicAndJoinsIndependentValidationWork() {
        WorkflowGraph.validate(WorkflowGraph.STAGES);
        var release = WorkflowGraph.byId().get("release_readiness");
        assertEquals(Set.of("tests", "security_review", "documentation"), Set.copyOf(release.dependencies()));
        assertEquals(List.of("intake", "repo_reasoning", "decomposition", "architecture",
                "requirement_approval", "implementation", "tests", "security_review",
                "documentation", "release_readiness", "release_approval", "release_promotion"),
                WorkflowGraph.topologicalOrder(WorkflowGraph.STAGES));
        assertTrue(WorkflowGraph.descendants("implementation").containsAll(
                Set.of("tests", "security_review", "documentation", "release_readiness", "release_promotion")));
    }

    @Test
    void rejectsDuplicateUnknownAndCyclicDependencies() {
        WorkflowStage first = new WorkflowStage("first", "First", List.of(), "system", null);
        assertThrows(IllegalArgumentException.class, () -> WorkflowGraph.validate(List.of(first, first)));
        assertThrows(IllegalArgumentException.class, () -> WorkflowGraph.validate(List.of(
                new WorkflowStage("first", "First", List.of("missing"), "system", null))));
        assertThrows(IllegalArgumentException.class, () -> WorkflowGraph.validate(List.of(
                new WorkflowStage("first", "First", List.of("second"), "system", null),
                new WorkflowStage("second", "Second", List.of("first"), "system", null))));
        assertThrows(IllegalArgumentException.class, () -> WorkflowGraph.descendants("missing"));
    }
}
