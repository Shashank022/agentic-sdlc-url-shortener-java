package io.agentic.sdlc.shortener.link;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public final class Database implements AutoCloseable {
    private final String jdbcUrl;
    private final boolean memory;
    private final Connection memoryAnchor;

    public Database(Path path) {
        this(path.toString());
    }

    public Database(String path) {
        if (path.equals(":memory:")) {
            memory = true;
            jdbcUrl = "jdbc:sqlite:file:shortener-" + UUID.randomUUID() + "?mode=memory&cache=shared";
            try {
                memoryAnchor = DriverManager.getConnection(jdbcUrl);
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not initialize in-memory SQLite.", exception);
            }
        } else {
            memory = false;
            memoryAnchor = null;
            Path file = Path.of(path).toAbsolutePath();
            try {
                Files.createDirectories(file.getParent());
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot create the database directory.", exception);
            }
            jdbcUrl = "jdbc:sqlite:" + file;
        }
    }

    public Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            if (!memory) {
                statement.execute("PRAGMA journal_mode = WAL");
            }
        }
        return connection;
    }

    public void initialize() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS links (
                      code TEXT PRIMARY KEY,
                      target_url TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      expires_at TEXT,
                      click_count INTEGER NOT NULL DEFAULT 0 CHECK (click_count >= 0),
                      last_accessed_at TEXT
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_links_created_at ON links(created_at)");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize the link database.", exception);
        }
    }

    public boolean isReady() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT COUNT(*) FROM links").close();
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    @Override
    public void close() {
        if (memoryAnchor != null) {
            try {
                memoryAnchor.close();
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not close in-memory SQLite.", exception);
            }
        }
    }
}
