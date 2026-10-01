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
        Map<String, Object> passed = new MavenTestStageRunner(List.of("/bin/sh", "-c",
                "echo suite passed; mkdir -p target/surefire-reports; printf '<testsuite tests=\"2\"/>' > target/surefire-reports/TEST-DemoTest.xml"),
                Duration.ofSeconds(2)).run(workspace);
        assertEquals("passed", passed.get("status"));
        assertEquals(0, passed.get("exit_code"));
        assertEquals(2, passed.get("test_count"));
        assertTrue(String.valueOf(passed.get("output_tail")).contains("suite passed"));

        Map<String, Object> failed = new MavenTestStageRunner(List.of("/bin/sh", "-c", "echo failed; exit 3"), Duration.ofSeconds(2)).run(workspace);
        assertEquals("failed", failed.get("status"));
        assertEquals(3, failed.get("exit_code"));
    }

    @Test
    void rejectsSuccessfulCommandWhenNoMavenTestsWereExecuted() throws Exception {
        java.nio.file.Path staleReports = java.nio.file.Files.createDirectories(workspace.resolve("target/surefire-reports"));
        java.nio.file.Files.writeString(staleReports.resolve("TEST-StaleTest.xml"), "<testsuite tests=\"9\"/>");
        Map<String, Object> noTests = new MavenTestStageRunner(List.of("/bin/echo", "BUILD SUCCESS"), Duration.ofSeconds(2)).run(workspace);
        assertEquals("failed", noTests.get("status"));
        assertEquals(0, noTests.get("test_count"));
        assertTrue(String.valueOf(noTests.get("output_tail")).contains("No executed Maven test cases"));
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
