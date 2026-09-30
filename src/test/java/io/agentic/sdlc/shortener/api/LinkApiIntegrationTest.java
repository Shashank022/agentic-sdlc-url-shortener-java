package io.agentic.sdlc.shortener.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentic.sdlc.shortener.link.Database;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shortener.database-path=target/test-data/shortener-${random.uuid}.sqlite3",
        "shortener.create-limit=1000",
        "shortener.max-expiry-seconds=86400"
})
class LinkApiIntegrationTest {
    @LocalServerPort int port;
    @Autowired Database database;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void createsRedirectsAndReportsAggregateStats() throws Exception {
        String alias = "link-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = send("POST", "/api/v1/links",
                "{\"target_url\":\"https://example.com/item\",\"custom_alias\":\"" + alias + "\",\"expires_in_seconds\":3600}",
                Map.of("X-Request-ID", "request-42"));
        assertEquals(201, created.statusCode());
        assertEquals("request-42", created.headers().firstValue("X-Request-ID").orElseThrow());
        Map<?, ?> body = json.readValue(created.body(), Map.class);
        assertEquals(alias, body.get("code"));
        assertTrue(String.valueOf(body.get("short_url")).endsWith("/r/" + alias));
        assertNotNull(body.get("expires_at"));

        HttpResponse<String> redirect = send("GET", "/r/" + alias, null, Map.of());
        assertEquals(302, redirect.statusCode());
        assertEquals("https://example.com/item", redirect.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", redirect.headers().firstValue("Cache-Control").orElseThrow());

        HttpResponse<String> stats = send("GET", "/api/v1/links/" + alias + "/stats", null, Map.of());
        assertEquals(200, stats.statusCode());
        assertEquals(1, json.readValue(stats.body(), Map.class).get("click_count"));
        assertTrue(String.valueOf(stats.body()).contains("\"active\":true"));
    }

    @Test
    void validatesRequestsMapsDomainErrorsAndServesHealthChecks() throws Exception {
        assertEquals(200, send("GET", "/health/live", null, Map.of()).statusCode());
        assertEquals(200, send("GET", "/health/ready", null, Map.of()).statusCode());
        HttpResponse<String> unsafe = send("POST", "/api/v1/links", "{\"target_url\":\"javascript:alert(1)\"}", Map.of());
        assertEquals(422, unsafe.statusCode());
        String alias = "dup-" + UUID.randomUUID().toString().substring(0, 8);
        String first = "{\"target_url\":\"https://example.com/one\",\"custom_alias\":\"" + alias + "\"}";
        assertEquals(201, send("POST", "/api/v1/links", first, Map.of()).statusCode());
        String second = "{\"target_url\":\"https://example.org/two\",\"custom_alias\":\"" + alias + "\"}";
        assertEquals(409, send("POST", "/api/v1/links", second, Map.of()).statusCode());
        assertEquals(404, send("GET", "/r/unknown", null, Map.of()).statusCode());
        assertEquals(404, send("GET", "/api/v1/links/unknown/stats", null, Map.of()).statusCode());
    }

    @Test
    void expiredLinksReturnGoneWithoutIncrementingClicks() throws Exception {
        String alias = "old-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = send("POST", "/api/v1/links",
                "{\"target_url\":\"https://example.com/old\",\"custom_alias\":\"" + alias + "\"}", Map.of());
        assertEquals(201, response.statusCode());
        try (Connection connection = database.connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE links SET expires_at='2000-01-01T00:00:00Z' WHERE code=?")) {
            update.setString(1, alias);
            update.executeUpdate();
        }
        assertEquals(410, send("GET", "/r/" + alias, null, Map.of()).statusCode());
        assertEquals(0, json.readValue(send("GET", "/api/v1/links/" + alias + "/stats", null, Map.of()).body(), Map.class)
                .get("click_count"));
    }

    private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        headers.forEach(request::header);
        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
