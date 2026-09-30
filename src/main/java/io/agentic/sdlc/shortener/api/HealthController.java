package io.agentic.sdlc.shortener.api;

import io.agentic.sdlc.shortener.link.Database;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final Database database;

    public HealthController(Database database) {
        this.database = database;
    }

    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "ok");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        if (database.isReady()) {
            return ResponseEntity.ok(Map.of("status", "ready"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "unavailable"));
    }
}
