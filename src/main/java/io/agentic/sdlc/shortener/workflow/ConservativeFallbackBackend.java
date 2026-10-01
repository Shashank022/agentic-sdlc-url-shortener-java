package io.agentic.sdlc.shortener.workflow;

import java.util.Map;

/** Offline fallback can inspect and report, but cannot fabricate code-generation or repair success. */
public final class ConservativeFallbackBackend implements AgentBackend {
    private final LocalAgentBackend local = new LocalAgentBackend();

    @Override
    public Map<String, Object> execute(String stageId, AgentContext context) throws Exception {
        if (stageId.equals("implementation") || stageId.equals("repair")) {
            return Map.of("status", "incomplete", "reason", "No contextual code-generation provider is available; human review is required.",
                    "changes", java.util.List.of());
        }
        return local.execute(stageId, context);
    }
}
