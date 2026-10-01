package io.agentic.sdlc.shortener.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangeSetTest {
    @Test
    void acceptsTraceableSourceAndTestChangesForEveryCriterion() {
        Map<String, Object> proposal = Map.of("status", "proposed", "changes", List.of(
                change("src/main/java/example/Feature.java", "create", "package example; class Feature {}", List.of("AC-1", "AC-2")),
                change("src/test/java/example/FeatureTest.java", "create", "package example; class FeatureTest {}", List.of("AC-1", "AC-2"))));
        List<ProposedChange> changes = ChangeSet.parse(proposal, List.of("Add feature.", "Preserve old behavior."));
        assertEquals(2, changes.size());
        assertEquals("src/test/java/example/FeatureTest.java", changes.get(1).path());
    }

    @Test
    void rejectsTraversalUnmappedCriteriaAndProposalsWithoutRegressionTests() {
        Map<String, Object> traversal = Map.of("status", "proposed", "changes", List.of(
                change("../../pom.xml", "update", "<project/>", List.of("AC-1"))));
        assertThrows(IllegalStateException.class, () -> ChangeSet.parse(traversal, List.of("Add feature.")));

        Map<String, Object> unmapped = Map.of("status", "proposed", "changes", List.of(
                change("src/main/java/example/Feature.java", "create", "package example; class Feature {}", List.of("AC-1")),
                change("src/test/java/example/FeatureTest.java", "create", "package example; class FeatureTest {}", List.of("AC-1"))));
        assertThrows(IllegalStateException.class, () -> ChangeSet.parse(unmapped, List.of("First criterion.", "Second criterion.")));

        Map<String, Object> sourceOnly = Map.of("status", "proposed", "changes", List.of(
                change("src/main/java/example/Feature.java", "create", "package example; class Feature {}", List.of("AC-1"))));
        assertThrows(IllegalStateException.class, () -> ChangeSet.parse(sourceOnly, List.of("Add feature.")));
    }

    private static Map<String, Object> change(String path, String operation, String content, List<String> criteria) {
        return Map.of("path", path, "operation", operation, "content", content,
                "criterion_ids", criteria, "rationale", "Implement and verify the criterion.");
    }
}
