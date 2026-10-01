package io.agentic.sdlc.shortener.workflow;

import java.util.List;

/** A complete-file operation proposed by an agent and validated by orchestration policy. */
public record ProposedChange(String path, String operation, String content,
                             List<String> criterionIds, String rationale) {
    public ProposedChange {
        criterionIds = List.copyOf(criterionIds);
    }
}
