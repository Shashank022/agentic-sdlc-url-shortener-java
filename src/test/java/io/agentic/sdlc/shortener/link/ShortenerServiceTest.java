package io.agentic.sdlc.shortener.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentic.sdlc.shortener.link.ShortenerService.AliasConflict;
import io.agentic.sdlc.shortener.link.ShortenerService.InvalidRequest;
import io.agentic.sdlc.shortener.link.ShortenerService.LinkExpired;
import io.agentic.sdlc.shortener.link.ShortenerService.LinkNotFound;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShortenerServiceTest {
    @TempDir Path temp;
    private Database database;
    private ShortenerService service;

    @BeforeEach
    void setUp() {
        database = new Database(temp.resolve("links.sqlite3"));
        service = new ShortenerService(database, 31_536_000);
    }

    @Test
    void validatesAbsoluteHttpTargetsAndRejectsUnsafeTargets() {
        assertEquals("https://example.com/path", ShortenerService.validateTarget(" https://example.com/path "));
        for (String target : List.of("javascript:alert(1)", "https://localhost/admin",
                "https://node.local/resource", "http://127.0.0.1/private", "http://10.1.2.3/private",
                "https://user:secret@example.com/path", "ftp://example.com/file", "https://example.com:99999/")) {
            assertThrows(InvalidRequest.class, () -> ShortenerService.validateTarget(target), target);
        }
        assertThrows(InvalidRequest.class, () -> ShortenerService.validateTarget(""));
    }

    @Test
    void validatesAliasesAndExpiryBounds() {
        assertEquals("a_1-b", ShortenerService.validateAlias("a_1-b"));
        for (String alias : List.of("ab", "bad!", "has space", "x".repeat(33))) {
            assertThrows(InvalidRequest.class, () -> ShortenerService.validateAlias(alias), alias);
        }
        assertThrows(InvalidRequest.class, () -> service.create("https://example.com", null, 0));
        assertThrows(InvalidRequest.class, () -> service.create("https://example.com", null, 31_536_001));
    }

    @Test
    void createsShortLinksTracksClicksAndReturnsPrivacySafeStats() {
        LinkRecord created = service.create("https://example.com/product/42", "product-42", 3600);
        assertEquals("product-42", created.code());
        assertEquals(0, created.clickCount());
        assertNotNull(created.expiresAt());
        assertEquals("https://example.com/product/42", service.resolveAndRecordClick(created.code()));
        LinkRecord stats = service.getStats(created.code());
        assertEquals(1, stats.clickCount());
        assertNotNull(stats.lastAccessedAt());
        assertTrue(Instant.parse(stats.expiresAt()).isAfter(Instant.now()));
        assertTrue(database.isReady());
    }

    @Test
    void generatedCodesAreSevenCharactersAndAliasesMustBeUnique() {
        LinkRecord generated = service.create("https://example.com/one", null, null);
        assertEquals(7, generated.code().length());
        assertNull(generated.expiresAt());
        service.create("https://example.com/first", "unique", null);
        assertThrows(AliasConflict.class, () -> service.create("https://example.org/second", "unique", null));
    }

    @Test
    void unknownAndExpiredLinksDoNotIncrementClicks() throws Exception {
        assertThrows(LinkNotFound.class, () -> service.resolveAndRecordClick("missing"));
        LinkRecord link = service.create("https://example.com/expire", "expiring", null);
        try (Connection connection = database.connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE links SET expires_at=? WHERE code=?")) {
            update.setString(1, "2000-01-01T00:00:00Z");
            update.setString(2, link.code());
            update.executeUpdate();
        }
        assertThrows(LinkExpired.class, () -> service.resolveAndRecordClick(link.code()));
        assertEquals(0, service.getStats(link.code()).clickCount());
    }

    @Test
    void concurrentRedirectsDoNotLoseClickIncrements() throws Exception {
        LinkRecord link = service.create("https://example.com/popular", "popular", null);
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                futures.add(executor.submit(() -> service.resolveAndRecordClick(link.code())));
            }
            for (var future : futures) future.get();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(20, service.getStats(link.code()).clickCount());
    }

    @Test
    void sharedInMemoryDatabaseSurvivesIndependentConnections() {
        try (Database memory = new Database(":memory:")) {
            ShortenerService isolated = new ShortenerService(memory, 3600);
            LinkRecord link = isolated.create("https://example.com/memory", "memory", null);
            assertEquals("https://example.com/memory", isolated.resolveAndRecordClick(link.code()));
            assertEquals(1, isolated.getStats(link.code()).clickCount());
        }
    }
}
