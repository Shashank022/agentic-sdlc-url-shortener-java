package io.agentic.sdlc.shortener.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RunState {
    public String runId;
    public String traceId;
    public String scenario;
    public String workspace;
    public String status = "CREATED";
    public String createdAt;
    public String updatedAt;
    public String finishedAt;
    public String sourceFingerprint;
    public String pendingCheckpoint;
    public int replanCount;
    public boolean stopRequested;
    public Map<String, Object> request = new LinkedHashMap<>();
    public List<StageState> stages = new ArrayList<>();

    public RunState() { }

    public StageState stage(String id) {
        return stages.stream().filter(stage -> stage.stageId.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown stage " + id + " in run " + runId + "."));
    }
}
