package io.agentic.sdlc.shortener.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        Path databasePath,
        String publicBaseUrl,
        int createLimit,
        int createWindowSeconds,
        int maxExpirySeconds) {
}
