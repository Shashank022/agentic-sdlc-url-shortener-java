package io.agentic.sdlc.shortener.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

public class StageState {
    public String stageId;
    public String status = "PENDING";
    public int attempts;
    public long elapsedMillis;
    public Map<String, Object> output;
    public String error;

    public StageState() { }

    public StageState(String stageId) {
        this.stageId = stageId;
        this.output = new LinkedHashMap<>();
    }
}
