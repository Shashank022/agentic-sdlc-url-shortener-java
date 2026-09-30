package io.agentic.sdlc.shortener.workflow;

import java.util.Map;

public record WorkflowEvent(long id, String runId, String eventType, String stageId,
                            String actor, Map<String, Object> payload, String createdAt) {
}
