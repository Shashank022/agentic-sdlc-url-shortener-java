package io.agentic.sdlc.shortener.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenAiCompatibleAgentBackendTest {
    @TempDir Path workspace;

    @Test
    void sendsRequirementAndRepositoryContextToStructuredProvider() throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/example"));
        Files.writeString(workspace.resolve("src/main/java/example/Existing.java"), "package example; class Existing { void redirect() {} }\n");
        Files.writeString(workspace.resolve("pom.xml"), "<project><groupId>example</groupId></project>");
        AtomicReference<String> requestBody = new AtomicReference<>("");
        AtomicReference<String> authorization = new AtomicReference<>("");
        HttpServer server = server(200, "{\"status\":\"proposed\",\"summary\":\"context-aware\"}", requestBody, authorization);
        try {
            OpenAiCompatibleAgentBackend backend = new OpenAiCompatibleAgentBackend(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    "test-model", "test-token", Duration.ofSeconds(3));
            AgentContext context = new AgentContext(workspace, "run1", "brownfield",
                    Map.of("request", "Preserve redirects.", "acceptance_criteria", List.of("Keep route compatible.")),
                    Map.of("repo_reasoning", Map.of("api_routes", List.of("/r/{code}"))));
            Map<String, Object> result = backend.execute("implementation", context);

            assertEquals("context-aware", result.get("summary"));
            assertTrue(requestBody.get().contains("Preserve redirects."));
            assertTrue(requestBody.get().contains("Existing.java"));
            assertTrue(requestBody.get().contains("Keep route compatible."));
            assertEquals("Bearer test-token", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnavailableAndMalformedProviderResponses() throws Exception {
        AtomicReference<String> ignored = new AtomicReference<>("");
        HttpServer unavailable = server(503, "{}", ignored, new AtomicReference<>(""));
        try {
            OpenAiCompatibleAgentBackend backend = new OpenAiCompatibleAgentBackend(
                    URI.create("http://127.0.0.1:" + unavailable.getAddress().getPort() + "/v1"),
                    "test-model", "", Duration.ofSeconds(3));
            assertThrows(AgentUnavailable.class, () -> backend.execute("architecture", context()));
        } finally {
            unavailable.stop(0);
        }

        HttpServer malformed = server(200, "not-json", ignored, new AtomicReference<>(""));
        try {
            OpenAiCompatibleAgentBackend backend = new OpenAiCompatibleAgentBackend(
                    URI.create("http://127.0.0.1:" + malformed.getAddress().getPort() + "/v1"),
                    "test-model", "", Duration.ofSeconds(3));
            assertThrows(Exception.class, () -> backend.execute("intake", context()));
        } finally {
            malformed.stop(0);
        }
        assertThrows(IllegalArgumentException.class, () -> new OpenAiCompatibleAgentBackend(
                URI.create("http://localhost/v1"), "", "", Duration.ofSeconds(1)));
    }

    @Test
    void providesRequirementAndRepositoryContextAcrossModelBackedStages() throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/example"));
        Path source = workspace.resolve("src/main/java/example/Changed.java");
        Files.writeString(source, "package example; class Changed {}\n");
        AtomicReference<String> body = new AtomicReference<>("");
        HttpServer server = server(200, "{\"status\":\"ok\"}", body, new AtomicReference<>(""));
        try {
            OpenAiCompatibleAgentBackend backend = new OpenAiCompatibleAgentBackend(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    "test-model", "", Duration.ofSeconds(3));
            Map<String, Object> implementation = Map.of("changed_files", List.of("src/main/java/example/Changed.java"),
                    "summary", "Changed implementation", "diff", "large diff omitted");
            AgentContext context = new AgentContext(workspace, "run3", "brownfield",
                    Map.of("request", "Change the route carefully.", "acceptance_criteria", List.of("Keep the route stable.")),
                    Map.of("implementation", implementation, "tests", Map.of("status", "failed", "output_tail", "compiler output")));
            for (String stage : List.of("intake", "repo_reasoning", "decomposition", "architecture", "implementation", "repair", "documentation")) {
                assertEquals("ok", backend.execute(stage, context).get("status"));
            }
            assertTrue(body.get().contains("Changed.java"));
            assertTrue(body.get().contains("compiler output"));
            assertFalse(body.get().contains("large diff omitted"));
            assertTrue(body.get().contains("criterion IDs"));
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(int status, String content, AtomicReference<String> requestBody,
                              AtomicReference<String> authorization) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            authorization.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String body = status == 200 ? "{\"choices\":[{\"message\":{\"content\":\""
                    + content.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\"}}]}" : "{}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private AgentContext context() {
        return new AgentContext(workspace, "run2", "greenfield", Map.of("request", "Build links."), Map.of());
    }
}
