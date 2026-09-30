package io.agentic.sdlc.shortener.workflow;

public class AgentUnavailable extends RuntimeException {
    public AgentUnavailable(String message) {
        super(message);
    }
}
