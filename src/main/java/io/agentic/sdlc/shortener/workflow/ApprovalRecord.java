package io.agentic.sdlc.shortener.workflow;

public record ApprovalRecord(long id, String runId, String checkpoint, String decision,
                             String actor, String rationale, String createdAt) {
}
