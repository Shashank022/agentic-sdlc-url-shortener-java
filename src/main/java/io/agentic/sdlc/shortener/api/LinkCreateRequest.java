package io.agentic.sdlc.shortener.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LinkCreateRequest(
        @NotBlank @Size(min = 8, max = 2048) String targetUrl,
        @Size(max = 32) String customAlias,
        @Positive Integer expiresInSeconds) {
}
