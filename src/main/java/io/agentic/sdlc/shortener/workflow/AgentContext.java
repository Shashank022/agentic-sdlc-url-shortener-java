package io.agentic.sdlc.shortener.workflow;

import java.nio.file.Path;
import java.util.Map;

public record AgentContext(Path workspace, String runId, String scenario,
                           Map<String, Object> request, Map<String, Map<String, Object>> priorOutputs) {
}
