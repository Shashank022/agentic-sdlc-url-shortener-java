package io.agentic.sdlc.shortener.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CreateRateLimiterTest {
    @Test
    void enforcesLimitsPerClientAndRejectsInvalidConfiguration() {
        CreateRateLimiter limiter = new CreateRateLimiter(1, 60);
        assertTrue(limiter.allow("client-a"));
        assertFalse(limiter.allow("client-a"));
        assertTrue(limiter.allow("client-b"));
        assertThrows(IllegalArgumentException.class, () -> new CreateRateLimiter(0, 60));
        assertThrows(IllegalArgumentException.class, () -> new CreateRateLimiter(2, 0));
    }
}
