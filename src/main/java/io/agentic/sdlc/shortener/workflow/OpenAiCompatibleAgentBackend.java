package io.agentic.sdlc.shortener.workflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Structured, context-carrying backend for OpenAI-compatible and local Ollama endpoints. */
public final class OpenAiCompatibleAgentBackend implements AgentBackend {
    private static final int MAX_CONTEXT_CHARS = 80_000;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final HttpClient client;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder().build();

    public OpenAiCompatibleAgentBackend(URI baseUrl, String model, String apiKey, Duration timeout) {
        if (model == null || model.isBlank() || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("A model and positive timeout are required.");
        }
        String base = baseUrl.toString().replaceAll("/+$", "");
        this.endpoint = URI.create(base.endsWith("/chat/completions") ? base : base + "/chat/completions");
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public static AgentBackend fromEnvironment(AgentBackend offline) {
        String baseUrl = System.getenv("AGENT_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) return offline;
        String model = System.getenv().getOrDefault("AGENT_MODEL", "qwen2.5-coder:7b");
        String key = System.getenv().getOrDefault("AGENT_API_KEY", "");
        long seconds;
        try {
            seconds = Long.parseLong(System.getenv().getOrDefault("AGENT_TIMEOUT_SECONDS", "120"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("AGENT_TIMEOUT_SECONDS must be a whole number.", exception);
        }
        return new OpenAiCompatibleAgentBackend(URI.create(baseUrl), model, key, Duration.ofSeconds(seconds));
    }

    public String modelName() {
        return model;
    }

    public String providerName() {
        return "openai-compatible@" + endpoint.getHost();
    }

    @Override
    public Map<String, Object> execute(String stageId, AgentContext context) throws Exception {
        if (stageId.equals("tests") || stageId.equals("security_review") || stageId.equals("release_promotion")) {
            return new LocalAgentBackend().execute(stageId, context);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(Map.of("role", "system", "content", systemPrompt(stageId)),
                Map.of("role", "user", "content", userPrompt(stageId, context))));
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            throw new AgentUnavailable("Contextual model endpoint could not be reached: " + exception.getClass().getSimpleName());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AgentUnavailable("Contextual model endpoint returned HTTP " + response.statusCode() + ".");
        }
        JsonNode envelope;
        JsonNode output;
        try {
            envelope = json.readTree(response.body());
            JsonNode content = envelope.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) throw new AgentUnavailable("Contextual model returned no JSON message content.");
            output = json.readTree(content.asString());
        } catch (AgentUnavailable unavailable) {
            throw unavailable;
        } catch (Exception exception) {
            throw new AgentUnavailable("Contextual model returned malformed JSON.");
        }
        if (output == null || !output.isObject()) throw new AgentUnavailable("Contextual model output must be a JSON object.");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = json.readValue(output.toString(), Map.class);
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
    }

    private String userPrompt(String stageId, AgentContext context) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stage", stageId);
        input.put("scenario", context.scenario());
        input.put("request", context.request());
        input.put("prior_stage_decisions", compactPriorOutputs(stageId, context.priorOutputs()));
        input.put("repository_files", repositoryEvidence(stageId, context));
        input.put("validation_failures", context.priorOutputs().getOrDefault("tests", Map.of()));
        String encoded = json.writeValueAsString(input);
        if (encoded.length() > MAX_CONTEXT_CHARS) encoded = encoded.substring(0, MAX_CONTEXT_CHARS) + "\n[context truncated by policy]";
        return "Use the requirement, prior decisions, and repository evidence to decide this stage. Preserve submitted acceptance criteria exactly; when they are absent, ask questions instead of inventing approved scope. "
                + "In implementation or repair, identify criteria in list order as AC-1, AC-2, and so on. Every proposed file must name the criterion IDs it implements. "
                + "Return one JSON object only, with no markdown fences. Never claim a check ran unless the supplied evidence shows it.\n\n"
                + "Required shape: " + outputContract(stageId) + "\n\nInput:\n" + encoded;
    }

    private static Map<String, Object> compactPriorOutputs(String stageId, Map<String, Map<String, Object>> outputs) {
        Map<String, Object> compact = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : outputs.entrySet()) {
            String priorStage = entry.getKey();
            Map<String, Object> value = entry.getValue();
            if ((stageId.equals("repair") || stageId.equals("documentation")) && priorStage.equals("implementation")) {
                Map<String, Object> summary = new LinkedHashMap<>();
                for (String key : List.of("summary", "changed_files", "criterion_traceability", "risks", "test_plan", "proposal_sha256")) {
                    if (value.containsKey(key)) summary.put(key, value.get(key));
                }
                compact.put(priorStage, summary);
            } else {
                compact.put(priorStage, value);
            }
        }
        return compact;
    }

    private static String systemPrompt(String stageId) {
        return "You are an engineering workflow agent for a Java 17 Spring Boot codebase. "
                + "Keep outputs tied to acceptance criteria and inspected code. Do not invoke tools, run commands, deploy, "
                + "or claim to have changed files. The orchestrator applies complete-file operations in an isolated candidate "
                + "workspace only after a human approves the exact proposal. Current stage: " + stageId + ".";
    }

    private static String outputContract(String stageId) {
        return switch (stageId) {
            case "intake" -> "{title, normalized_problem, acceptance_criteria:[...], open_questions:[...], assumptions:[...], ambiguity:'low|high'}";
            case "repo_reasoning" -> "{mode, baseline_found, modules:[{path,classes:[...],methods:[...],tests:[...],reason}], dependency_paths:[...], risks:[...], api_compatibility:[...]}";
            case "decomposition" -> "{tasks:[{id,task,depends_on:[...],owner,deliverable,criterion_ids:[...]}], critical_path:[...], decisions:[...]}";
            case "architecture" -> "{components:[{name,responsibility}], decisions:[{id,decision,tradeoff}], test_plan:[...], risks:[...]}";
            case "implementation" -> "{status:'proposed',summary,changes:[{path,operation:'create|update|delete',content,criterion_ids:['AC-1'],rationale}],risks:[...],test_plan:[...]}";
            case "repair" -> "{status:'proposed',diagnosis,changes:[{path,operation:'create|update|delete',content,criterion_ids:['AC-1'],rationale}],risks:[...]}";
            case "documentation" -> "{path:'engineering_summary.md',markdown:'...'}";
            default -> "{status,summary,evidence:[...]}";
        };
    }

    private static List<Map<String, String>> repositoryEvidence(String stageId, AgentContext context) throws Exception {
        if (context.scenario().equals("greenfield") && stageId.equals("implementation")) return List.of();
        Path root = context.workspace();
        Path javaRoot = root.resolve("src");
        List<Map<String, String>> evidence = new ArrayList<>();
        List<String> prioritized = new ArrayList<>();
        Map<String, Object> implementation = context.priorOutputs().getOrDefault("implementation", Map.of());
        Object changedFiles = implementation.get("changed_files");
        if (changedFiles instanceof List<?> files) files.forEach(file -> prioritized.add(String.valueOf(file)));
        if (Files.exists(javaRoot)) {
            try (var files = Files.walk(javaRoot)) {
                List<Path> candidates = files.filter(path -> !Files.isSymbolicLink(path)).filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java")).sorted().toList();
                List<Path> ordered = new ArrayList<>();
                for (String path : prioritized) {
                    Path candidate = root.resolve(path).normalize();
                    if (candidate.startsWith(root) && candidates.contains(candidate)) ordered.add(candidate);
                }
                for (Path file : candidates) if (!ordered.contains(file)) ordered.add(file);
                for (Path file : ordered.stream().limit(40).toList()) {
                    String content = Files.readString(file);
                    if (content.length() > 3000) content = content.substring(0, 3000) + "\n[source truncated]";
                    evidence.add(Map.of("path", root.relativize(file).toString().replace('\\', '/'), "content", content));
                }
            }
        }
        Path pom = root.resolve("pom.xml");
        if (Files.isRegularFile(pom)) evidence.add(Map.of("path", "pom.xml", "content", Files.readString(pom)));
        return List.copyOf(evidence);
    }
}
