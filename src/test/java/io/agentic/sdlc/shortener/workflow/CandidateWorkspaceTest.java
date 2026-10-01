package io.agentic.sdlc.shortener.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CandidateWorkspaceTest {
    @TempDir Path temp;

    @Test
    void appliesCreateUpdateAndDeleteOnlyToCandidate() throws Exception {
        Path baseline = temp.resolve("baseline");
        Path candidate = temp.resolve("candidate");
        Files.createDirectories(baseline.resolve("src/main/java/example"));
        Files.createDirectories(baseline.resolve("src/test/java/example"));
        Files.writeString(baseline.resolve("pom.xml"), "<project/>\n");
        Files.writeString(baseline.resolve(".env"), "AGENT_API_KEY=do-not-copy\n");
        Files.writeString(baseline.resolve("src/main/java/example/Existing.java"), "package example; class Existing { int n = 1; }\n");
        Files.writeString(baseline.resolve("src/main/java/example/Removed.java"), "package example; class Removed {}\n");
        String original = CandidateWorkspace.fingerprint(baseline);
        CandidateWorkspace.create(baseline, candidate, false);
        CandidateWorkspace.apply(candidate, List.of(
                new ProposedChange("src/main/java/example/Existing.java", "update", "package example; class Existing { int n = 2; }\n", List.of("AC-1"), "Update existing behavior."),
                new ProposedChange("src/main/java/example/NewFeature.java", "create", "package example; class NewFeature {}\n", List.of("AC-1"), "Add feature."),
                new ProposedChange("src/main/java/example/Removed.java", "delete", "", List.of("AC-1"), "Remove obsolete implementation.")));

        assertEquals(original, CandidateWorkspace.fingerprint(baseline));
        assertFalse(Files.exists(candidate.resolve(".env")));
        assertTrue(Files.readString(candidate.resolve("src/main/java/example/Existing.java")).contains("n = 2"));
        assertTrue(Files.isRegularFile(candidate.resolve("src/main/java/example/NewFeature.java")));
        assertFalse(Files.exists(candidate.resolve("src/main/java/example/Removed.java")));
    }

    @Test
    void greenfieldStartsFromOnlyTheMavenStarterAndRollbackCanDiscardIt() throws Exception {
        Path baseline = temp.resolve("baseline");
        Path candidate = temp.resolve("run/candidate");
        Files.createDirectories(baseline.resolve("src/main/java/example"));
        Files.writeString(baseline.resolve("pom.xml"), "<project/>\n");
        Files.writeString(baseline.resolve(".env"), "AGENT_API_KEY=do-not-copy\n");
        Files.writeString(baseline.resolve("src/main/java/example/Existing.java"), "package example; class Existing {}\n");
        CandidateWorkspace.create(baseline, candidate, true);
        assertTrue(Files.isRegularFile(candidate.resolve("pom.xml")));
        assertFalse(Files.exists(candidate.resolve(".env")));
        assertFalse(Files.exists(candidate.resolve("src/main/java/example/Existing.java")));
        CandidateWorkspace.deleteTree(candidate);
        assertFalse(Files.exists(candidate));
        assertTrue(Files.isRegularFile(baseline.resolve("src/main/java/example/Existing.java")));
    }
}
