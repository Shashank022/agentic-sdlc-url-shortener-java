package io.agentic.sdlc.shortener.workflow;

import java.util.Map;

public interface AgentBackend {
    Map<String, Object> execute(String stageId, AgentContext context) throws Exception;
}
