package io.agentic.sdlc.shortener.workflow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Compiles and runs every Maven test in the isolated candidate with a fixed executable and bounded timeout. */
public class MavenTestStageRunner implements TestStageRunner {
    private static final List<String> COMMAND = List.of("mvn", "--batch-mode", "--no-transfer-progress", "test");
    private final List<String> command;
    private final Duration timeout;

    public MavenTestStageRunner() {
        this(COMMAND, Duration.ofMinutes(3));
    }

    MavenTestStageRunner(List<String> command, Duration timeout) {
        if (command.isEmpty() || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("A command and positive timeout are required.");
        this.command = List.copyOf(command);
        this.timeout = timeout;
    }

    @Override
    public Map<String, Object> run(Path workspace) throws Exception {
        long started = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
        } catch (IOException exception) {
            return Map.of("status", "failed", "exit_code", 127, "duration_seconds", 0.0,
                    "command", command, "output_tail", "Maven is required to run the workflow test stage: " + exception.getMessage());
        }
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copyOutput(process, captured), "workflow-test-output");
        reader.setDaemon(true);
        reader.start();
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            reader.join(1000);
            return Map.of("status", "failed", "exit_code", 124, "duration_seconds", secondsSince(started),
                    "command", command, "output_tail", "Workflow test stage exceeded its timeout.");
        }
        reader.join(1000);
        String output = captured.toString(StandardCharsets.UTF_8);
        if (output.length() > 5000) output = output.substring(output.length() - 5000);
        return Map.of("status", process.exitValue() == 0 ? "passed" : "failed",
                "exit_code", process.exitValue(), "duration_seconds", secondsSince(started),
                "command", command, "output_tail", output);
    }

    private static double secondsSince(long started) {
        return Math.round((System.nanoTime() - started) / 1_000_000.0) / 1000.0;
    }

    private static void copyOutput(Process process, ByteArrayOutputStream target) {
        try (var input = process.getInputStream()) {
            input.transferTo(target);
        } catch (IOException ignored) {
            // The process exit code remains authoritative; captured output is diagnostic only.
        }
    }
}
