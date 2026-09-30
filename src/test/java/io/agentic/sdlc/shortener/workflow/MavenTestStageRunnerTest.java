package io.agentic.sdlc.shortener.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenTestStageRunnerTest {
    @TempDir Path workspace;

    @Test
    void returnsCapturedOutputAndTheActualExitStatus() throws Exception {
        Map<String, Object> passed = new MavenTestStageRunner(List.of("/bin/echo", "suite passed"), Duration.ofSeconds(2)).run(workspace);
        assertEquals("passed", passed.get("status"));
        assertEquals(0, passed.get("exit_code"));
        assertTrue(String.valueOf(passed.get("output_tail")).contains("suite passed"));

        Map<String, Object> failed = new MavenTestStageRunner(List.of("/bin/sh", "-c", "echo failed; exit 3"), Duration.ofSeconds(2)).run(workspace);
        assertEquals("failed", failed.get("status"));
        assertEquals(3, failed.get("exit_code"));
    }

    @Test
    void reportsMissingCommandsAndEnforcesTheExecutionTimeout() throws Exception {
        Map<String, Object> missing = new MavenTestStageRunner(List.of("missing-maven-executable"), Duration.ofSeconds(1)).run(workspace);
        assertEquals(127, missing.get("exit_code"));
        Map<String, Object> timeout = new MavenTestStageRunner(List.of("/bin/sleep", "2"), Duration.ofMillis(25)).run(workspace);
        assertEquals(124, timeout.get("exit_code"));
    }

    @Test
    void rejectsAnEmptyCommandOrNonPositiveTimeout() {
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MavenTestStageRunner(List.of(), Duration.ofSeconds(1))).getMessage().contains("command"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MavenTestStageRunner(List.of("mvn"), Duration.ZERO));
    }
}
