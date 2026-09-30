package io.agentic.sdlc.shortener.workflow;

import java.util.List;
import java.util.Map;

public record Scenario(String name, String title, String request, List<String> acceptanceCriteria, String mode) {
    public Scenario {
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
    }

    public Map<String, Object> asRequest() {
        return Map.of("title", title, "request", request, "acceptance_criteria", acceptanceCriteria, "mode", mode);
    }
}
