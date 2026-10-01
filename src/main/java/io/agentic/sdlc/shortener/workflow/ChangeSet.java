package io.agentic.sdlc.shortener.workflow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates model-proposed file operations before they can reach a candidate workspace. */
public final class ChangeSet {
    public static final int MAX_FILES = 40;
    public static final int MAX_BYTES = 500_000;

    private ChangeSet() { }

    public static List<ProposedChange> parse(Map<String, Object> output, List<String> acceptanceCriteria) {
        return parse(output, acceptanceCriteria, true);
    }

    public static List<ProposedChange> parse(Map<String, Object> output, List<String> acceptanceCriteria, boolean requireFullCoverage) {
        if (!"proposed".equals(output.get("status"))) {
            throw new IllegalStateException("Agent did not propose a complete implementation: " + output.getOrDefault("status", "missing status"));
        }
        Object raw = output.get("changes");
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > MAX_FILES) {
            throw new IllegalStateException("Implementation must propose 1 to " + MAX_FILES + " source or test file changes.");
        }
        Set<String> allowedCriteria = new HashSet<>();
        for (int i = 0; i < acceptanceCriteria.size(); i++) allowedCriteria.add("AC-" + (i + 1));
        Set<String> seenPaths = new HashSet<>();
        Set<String> coveredCriteria = new HashSet<>();
        List<ProposedChange> changes = new ArrayList<>();
        int bytes = 0;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> item)) throw new IllegalStateException("Each change must be a JSON object.");
            String path = text(item.get("path"), "change path");
            validatePath(path);
            String normalized = Path.of(path).normalize().toString().replace('\\', '/');
            if (!seenPaths.add(normalized)) throw new IllegalStateException("A patch may mention each path once: " + path);
            String operation = text(item.get("operation"), "change operation").toLowerCase();
            if (!Set.of("create", "update", "delete").contains(operation)) {
                throw new IllegalStateException("Unsupported operation for " + path + ".");
            }
            String content = item.get("content") == null ? "" : String.valueOf(item.get("content"));
            if (operation.equals("delete") && !content.isEmpty()) throw new IllegalStateException("Delete operations cannot include content.");
            if (!operation.equals("delete") && content.isBlank()) throw new IllegalStateException("Create and update operations need source content.");
            bytes += content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BYTES) throw new IllegalStateException("Proposed source changes exceed the 500 KB limit.");
            List<String> ids = textList(item.get("criterion_ids"));
            if (ids.isEmpty() || ids.stream().anyMatch(id -> !allowedCriteria.contains(id))) {
                throw new IllegalStateException("Every changed file must name valid acceptance criterion IDs.");
            }
            coveredCriteria.addAll(ids);
            String rationale = text(item.get("rationale"), "change rationale");
            changes.add(new ProposedChange(normalized, operation, content, ids, rationale));
        }
        if (requireFullCoverage && !coveredCriteria.containsAll(allowedCriteria)) {
            Set<String> missing = new HashSet<>(allowedCriteria);
            missing.removeAll(coveredCriteria);
            throw new IllegalStateException("No proposed file is traced to acceptance criteria " + missing + ".");
        }
        if (requireFullCoverage && changes.stream().noneMatch(change -> change.path().startsWith("src/test/java/"))) {
            throw new IllegalStateException("A generated regression test file is required.");
        }
        return List.copyOf(changes);
    }

    public static void validatePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")) {
            throw new IllegalStateException("Only normalized relative Java source paths are allowed.");
        }
        Path relative = Path.of(path);
        if (relative.isAbsolute() || relative.normalize().startsWith("..") || !relative.normalize().toString().replace('\\', '/').equals(path)) {
            throw new IllegalStateException("Path traversal and non-normalized paths are forbidden: " + path);
        }
        boolean applicationSource = path.startsWith("src/main/java/") && path.endsWith(".java");
        boolean testSource = path.startsWith("src/test/java/") && path.endsWith(".java");
        if (!applicationSource && !testSource) {
            throw new IllegalStateException("Agents may change Java application and test files only: " + path);
        }
    }

    private static String text(Object value, String label) {
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalStateException("Missing " + label + ".");
        return String.valueOf(value).trim();
    }

    private static List<String> textList(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isEmpty()).distinct().toList();
    }
}
