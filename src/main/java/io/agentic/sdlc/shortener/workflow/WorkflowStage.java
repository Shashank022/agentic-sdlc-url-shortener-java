package io.agentic.sdlc.shortener.workflow;

import java.util.List;

public record WorkflowStage(String id, String label, List<String> dependencies, String agent, String checkpoint) {
    public WorkflowStage {
        dependencies = List.copyOf(dependencies);
    }
}
