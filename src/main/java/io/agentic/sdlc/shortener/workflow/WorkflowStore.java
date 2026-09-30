package io.agentic.sdlc.shortener.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** SQLite persistence for workflow projections, approval history, leases, and append-only events. */
public final class WorkflowStore {
    private final String jdbcUrl;
    private final JsonMapper json = JsonMapper.builder().build();

    public WorkflowStore(Path path) {
        Path file = path.toAbsolutePath();
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create workflow database directory.", exception);
        }
        jdbcUrl = "jdbc:sqlite:" + file;
        initialize();
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        return connection;
    }

    private void initialize() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS workflow_runs (
                      run_id TEXT PRIMARY KEY,
                      state_json TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      finished_at TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS workflow_events (
                      event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                      run_id TEXT NOT NULL,
                      event_type TEXT NOT NULL,
                      stage_id TEXT,
                      actor TEXT,
                      payload_json TEXT NOT NULL,
                      created_at TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_workflow_events_run ON workflow_events(run_id,event_id)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS workflow_approvals (
                      approval_id INTEGER PRIMARY KEY AUTOINCREMENT,
                      run_id TEXT NOT NULL,
                      checkpoint TEXT NOT NULL,
                      decision TEXT NOT NULL,
                      actor TEXT,
                      rationale TEXT,
                      created_at TEXT NOT NULL,
                      superseded INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS workflow_leases (
                      run_id TEXT PRIMARY KEY,
                      owner TEXT NOT NULL,
                      expires_at TEXT NOT NULL
                    )
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize workflow state database.", exception);
        }
    }

    public void createRun(RunState run) {
        String encoded = encode(run);
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO workflow_runs(run_id,state_json,created_at,updated_at,finished_at) VALUES(?,?,?,?,NULL)")) {
            insert.setString(1, run.runId);
            insert.setString(2, encoded);
            insert.setString(3, run.createdAt);
            insert.setString(4, run.updatedAt);
            insert.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Could not create workflow run", exception);
        }
    }

    public void saveRun(RunState run) {
        run.updatedAt = now();
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE workflow_runs SET state_json=?,updated_at=?,finished_at=? WHERE run_id=?")) {
            update.setString(1, encode(run));
            update.setString(2, run.updatedAt);
            update.setString(3, run.finishedAt);
            update.setString(4, run.runId);
            if (update.executeUpdate() != 1) {
                throw new IllegalArgumentException("Run " + run.runId + " not found.");
            }
        } catch (SQLException exception) {
            throw failure("Could not save workflow run", exception);
        }
    }

    public RunState getRun(String runId) {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT state_json FROM workflow_runs WHERE run_id=?")) {
            query.setString(1, runId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Run " + runId + " not found.");
                return decode(result.getString(1), RunState.class);
            }
        } catch (SQLException exception) {
            throw failure("Could not load workflow run", exception);
        }
    }

    public List<RunState> runs() {
        List<RunState> result = new ArrayList<>();
        try (Connection connection = connect(); Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT state_json FROM workflow_runs ORDER BY created_at DESC")) {
            while (rows.next()) result.add(decode(rows.getString(1), RunState.class));
            return result;
        } catch (SQLException exception) {
            throw failure("Could not list workflow runs", exception);
        }
    }

    public void appendEvent(String runId, String type, String stageId, String actor, Map<String, Object> payload) {
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO workflow_events(run_id,event_type,stage_id,actor,payload_json,created_at) VALUES(?,?,?,?,?,?)")) {
            insert.setString(1, runId);
            insert.setString(2, type);
            insert.setString(3, stageId);
            insert.setString(4, actor);
            insert.setString(5, encode(payload));
            insert.setString(6, now());
            insert.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Could not append workflow event", exception);
        }
    }

    public List<WorkflowEvent> events(String runId) {
        List<WorkflowEvent> events = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT event_id,run_id,event_type,stage_id,actor,payload_json,created_at FROM workflow_events WHERE run_id=? ORDER BY event_id")) {
            query.setString(1, runId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    events.add(new WorkflowEvent(rows.getLong("event_id"), rows.getString("run_id"),
                            rows.getString("event_type"), rows.getString("stage_id"), rows.getString("actor"),
                            decodeMap(rows.getString("payload_json")), rows.getString("created_at")));
                }
            }
            return events;
        } catch (SQLException exception) {
            throw failure("Could not read workflow events", exception);
        }
    }

    public void requestApproval(String runId, String checkpoint) {
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO workflow_approvals(run_id,checkpoint,decision,created_at) VALUES(?,?,?,?)")) {
            insert.setString(1, runId);
            insert.setString(2, checkpoint);
            insert.setString(3, "PENDING");
            insert.setString(4, now());
            insert.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Could not request human approval", exception);
        }
    }

    public ApprovalRecord decideApproval(String runId, String checkpoint, String decision,
                                         String actor, String rationale) {
        String timestamp = now();
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement("""
                UPDATE workflow_approvals SET decision=?,actor=?,rationale=?,created_at=?
                WHERE approval_id=(SELECT approval_id FROM workflow_approvals
                  WHERE run_id=? AND checkpoint=? AND decision='PENDING' AND superseded=0
                  ORDER BY approval_id DESC LIMIT 1)
                """)) {
            update.setString(1, decision);
            update.setString(2, actor);
            update.setString(3, rationale);
            update.setString(4, timestamp);
            update.setString(5, runId);
            update.setString(6, checkpoint);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("No pending " + checkpoint + " approval exists for run " + runId + ".");
            }
            return approval(runId, checkpoint);
        } catch (SQLException exception) {
            throw failure("Could not record approval decision", exception);
        }
    }

    public ApprovalRecord approval(String runId, String checkpoint) {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement("""
                SELECT approval_id,run_id,checkpoint,decision,actor,rationale,created_at FROM workflow_approvals
                WHERE run_id=? AND checkpoint=? AND superseded=0 ORDER BY approval_id DESC LIMIT 1
                """)) {
            query.setString(1, runId);
            query.setString(2, checkpoint);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return new ApprovalRecord(result.getLong("approval_id"), result.getString("run_id"),
                        result.getString("checkpoint"), result.getString("decision"), result.getString("actor"),
                        result.getString("rationale"), result.getString("created_at"));
            }
        } catch (SQLException exception) {
            throw failure("Could not read approval decision", exception);
        }
    }

    public void supersedeApprovals(String runId) {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE workflow_approvals SET superseded=1 WHERE run_id=?")) {
            update.setString(1, runId);
            update.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Could not supersede stale approvals", exception);
        }
    }

    public void acquireLease(String runId, String owner, int seconds) {
        String until = Instant.now().plusSeconds(seconds).toString();
        try (Connection connection = connect(); PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO workflow_leases(run_id,owner,expires_at) VALUES(?,?,?)
                ON CONFLICT(run_id) DO UPDATE SET owner=excluded.owner,expires_at=excluded.expires_at
                WHERE workflow_leases.expires_at<=? OR workflow_leases.owner=excluded.owner
                """)) {
            upsert.setString(1, runId);
            upsert.setString(2, owner);
            upsert.setString(3, until);
            upsert.setString(4, now());
            upsert.executeUpdate();
            try (PreparedStatement query = connection.prepareStatement("SELECT owner FROM workflow_leases WHERE run_id=?")) {
                query.setString(1, runId);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next() || !owner.equals(result.getString(1))) {
                        throw new IllegalStateException("Another workflow runner holds the lease for " + runId + ".");
                    }
                }
            }
        } catch (SQLException exception) {
            throw failure("Could not acquire workflow lease", exception);
        }
    }

    public void renewLease(String runId, String owner, int seconds) {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE workflow_leases SET expires_at=? WHERE run_id=? AND owner=?")) {
            update.setString(1, Instant.now().plusSeconds(seconds).toString());
            update.setString(2, runId);
            update.setString(3, owner);
            if (update.executeUpdate() != 1) throw new IllegalStateException("Workflow lease was lost for " + runId + ".");
        } catch (SQLException exception) {
            throw failure("Could not renew workflow lease", exception);
        }
    }

    public void releaseLease(String runId, String owner) {
        try (Connection connection = connect(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM workflow_leases WHERE run_id=? AND owner=?")) {
            delete.setString(1, runId);
            delete.setString(2, owner);
            delete.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Could not release workflow lease", exception);
        }
    }

    public Map<String, Object> metrics() {
        List<RunState> runs = runs();
        List<RunState> terminal = runs.stream().filter(run -> List.of("SUCCEEDED", "FAILED", "STOPPED", "ROLLED_BACK").contains(run.status)).toList();
        int retries = terminal.stream().flatMap(run -> run.stages.stream()).mapToInt(stage -> Math.max(0, stage.attempts - 1)).sum();
        long retriedRuns = terminal.stream().filter(run -> run.stages.stream().anyMatch(stage -> stage.attempts > 1)).count();
        List<Double> latencies = terminal.stream().filter(run -> run.finishedAt != null)
                .map(run -> Duration.between(Instant.parse(run.createdAt), Instant.parse(run.finishedAt)).toMillis() / 1000.0).toList();
        long rollbackCount = eventsForAll("ROLLBACK_COMPLETED");
        List<Double> recovered = new ArrayList<>();
        for (RunState run : runs) {
            for (WorkflowEvent event : events(run.runId)) {
                if (event.eventType().equals("STAGE_RECOVERED")) {
                    Object value = event.payload().get("recovery_seconds");
                    if (value instanceof Number number) recovered.add(number.doubleValue());
                }
            }
        }
        int completed = terminal.size();
        long succeeded = terminal.stream().filter(run -> run.status.equals("SUCCEEDED")).count();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("completed_runs", completed);
        metrics.put("success_rate", completed == 0 ? null : round((double) succeeded / completed, 4));
        metrics.put("retry_count", retries);
        metrics.put("retry_frequency", completed == 0 ? null : round((double) retriedRuns / completed, 4));
        metrics.put("rollback_count", rollbackCount);
        metrics.put("rollback_frequency", completed == 0 ? null : round((double) rollbackCount / completed, 4));
        metrics.put("mttr_seconds", recovered.isEmpty() ? 0.0 : round(recovered.stream().mapToDouble(Double::doubleValue).average().orElse(0.0), 3));
        metrics.put("mean_end_to_end_latency_seconds", latencies.isEmpty() ? 0.0 : round(latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0.0), 3));
        return metrics;
    }

    private long eventsForAll(String type) {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM workflow_events WHERE event_type=?")) {
            query.setString(1, type);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        } catch (SQLException exception) {
            throw failure("Could not count workflow metrics", exception);
        }
    }

    private Map<String, Object> decodeMap(String value) {
        try {
            return json.readValue(value, Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored workflow JSON could not be parsed.", exception);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Workflow state could not be serialized.", exception);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored workflow state could not be parsed.", exception);
        }
    }

    private static IllegalStateException failure(String message, SQLException cause) {
        return new IllegalStateException(message + ".", cause);
    }

    static String now() {
        return Instant.now().toString();
    }

    static String owner() {
        return UUID.randomUUID().toString();
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
