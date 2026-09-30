package io.agentic.sdlc.shortener.link;

public record LinkRecord(
        String code,
        String targetUrl,
        String createdAt,
        String expiresAt,
        long clickCount,
        String lastAccessedAt) {
}
