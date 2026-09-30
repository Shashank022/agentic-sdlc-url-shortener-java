package io.agentic.sdlc.shortener.api;

public record LinkStatsResponse(
        String code,
        String targetUrl,
        String createdAt,
        String expiresAt,
        long clickCount,
        String lastAccessedAt,
        boolean active) {
}
