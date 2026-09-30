package io.agentic.sdlc.shortener.workflow;

import java.nio.file.Path;
import java.util.Map;

@FunctionalInterface
public interface TestStageRunner {
    Map<String, Object> run(Path workspace) throws Exception;
}
