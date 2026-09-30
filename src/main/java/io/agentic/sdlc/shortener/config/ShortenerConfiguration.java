package io.agentic.sdlc.shortener.config;

import io.agentic.sdlc.shortener.link.Database;
import io.agentic.sdlc.shortener.link.ShortenerService;
import io.agentic.sdlc.shortener.api.CreateRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShortenerConfiguration {
    @Bean
    Database database(ShortenerProperties properties) {
        return new Database(properties.databasePath());
    }

    @Bean
    ShortenerService shortenerService(Database database, ShortenerProperties properties) {
        return new ShortenerService(database, properties.maxExpirySeconds());
    }

    @Bean
    CreateRateLimiter createRateLimiter(ShortenerProperties properties) {
        return new CreateRateLimiter(properties.createLimit(), properties.createWindowSeconds());
    }
}
