package io.agentic.sdlc.shortener.api;

public record LinkCreatedResponse(
        String code,
        String shortUrl,
        String targetUrl,
        String createdAt,
        String expiresAt) {
}
