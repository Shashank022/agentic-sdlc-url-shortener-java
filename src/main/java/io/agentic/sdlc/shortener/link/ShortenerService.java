package io.agentic.sdlc.shortener.link;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

public final class ShortenerService {
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_-]{3,32}");
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Database database;
    private final int maxExpirySeconds;

    public ShortenerService(Database database, int maxExpirySeconds) {
        this.database = database;
        this.maxExpirySeconds = maxExpirySeconds;
        database.initialize();
    }

    public LinkRecord create(String rawTarget, String customAlias, Integer expiresInSeconds) {
        String targetUrl = validateTarget(rawTarget);
        if (expiresInSeconds != null && (expiresInSeconds < 1 || expiresInSeconds > maxExpirySeconds)) {
            throw new InvalidRequest("Expiry must be between 1 and " + maxExpirySeconds + " seconds.");
        }
        if (customAlias != null) {
            validateAlias(customAlias);
        }
        Instant now = now();
        String createdAt = iso(now);
        String expiresAt = expiresInSeconds == null ? null : iso(now.plusSeconds(expiresInSeconds));
        try (Connection connection = database.connect()) {
            for (int attempt = 0; attempt < 6; attempt++) {
                String code = customAlias == null ? newCode() : customAlias;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO links(code,target_url,created_at,expires_at) VALUES(?,?,?,?)")) {
                    insert.setString(1, code);
                    insert.setString(2, targetUrl);
                    insert.setString(3, createdAt);
                    insert.setString(4, expiresAt);
                    insert.executeUpdate();
                    return find(connection, code);
                } catch (SQLException exception) {
                    if (customAlias != null && isConstraintViolation(exception)) {
                        throw new AliasConflict("That alias is already in use.");
                    }
                    if (!isConstraintViolation(exception) || attempt == 5) {
                        throw exception;
                    }
                }
            }
            throw new IllegalStateException("Could not allocate a unique short code.");
        } catch (SQLException exception) {
            throw new PersistenceFailure("Link could not be created.", exception);
        }
    }

    public String resolveAndRecordClick(String code) {
        try (Connection connection = database.connect(); PreparedStatement lookup = connection.prepareStatement(
                "SELECT target_url, expires_at FROM links WHERE code = ?")) {
            try (var begin = connection.createStatement()) {
                begin.execute("BEGIN IMMEDIATE");
            }
            lookup.setString(1, code);
            String target;
            String expires;
            try (ResultSet result = lookup.executeQuery()) {
                if (!result.next()) {
                    rollback(connection);
                    throw new LinkNotFound("Short link was not found.");
                }
                target = result.getString("target_url");
                expires = result.getString("expires_at");
            }
            if (expires != null && !Instant.parse(expires).isAfter(now())) {
                rollback(connection);
                throw new LinkExpired("Short link has expired.");
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE links SET click_count = click_count + 1, last_accessed_at = ? WHERE code = ?")) {
                update.setString(1, iso(now()));
                update.setString(2, code);
                update.executeUpdate();
            }
            try (var commit = connection.createStatement()) {
                commit.execute("COMMIT");
            }
            return target;
        } catch (LinkNotFound | LinkExpired exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new PersistenceFailure("Could not resolve the short link.", exception);
        }
    }

    public LinkRecord getStats(String code) {
        try (Connection connection = database.connect()) {
            return find(connection, code);
        } catch (LinkNotFound exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new PersistenceFailure("Could not read link statistics.", exception);
        }
    }

    public static String validateAlias(String alias) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new InvalidRequest("Alias must be 3-32 characters: letters, numbers, '_' or '-'.");
        }
        return alias;
    }

    public static String validateTarget(String rawTarget) {
        if (rawTarget == null || rawTarget.length() < 8 || rawTarget.length() > 2048) {
            throw new InvalidRequest("Target URL must contain 8-2048 characters.");
        }
        final URI uri;
        try {
            uri = new URI(rawTarget.trim());
            uri.toURL();
        } catch (IllegalArgumentException | URISyntaxException exception) {
            throw new InvalidRequest("Target URL is malformed.");
        } catch (java.net.MalformedURLException exception) {
            throw new InvalidRequest("Target URL is malformed.");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || host == null) {
            throw new InvalidRequest("Target URL must be an absolute HTTP or HTTPS URL.");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw new InvalidRequest("Target URL port is outside the valid range.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidRequest("Target URL must not contain embedded credentials.");
        }
        String normalized = host.replaceAll("[\\[\\]]", "").replaceFirst("\\.$", "").toLowerCase();
        if (normalized.equals("localhost") || normalized.endsWith(".localhost") || normalized.endsWith(".local")) {
            throw new InvalidRequest("Local hostnames are not accepted as redirect targets.");
        }
        if (looksLikeIpLiteral(normalized) && !isPublicIp(normalized)) {
            throw new InvalidRequest("Non-public IP addresses are not accepted as redirect targets.");
        }
        return rawTarget.trim();
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public static String iso(Instant value) {
        return value.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static boolean looksLikeIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    private static boolean isPublicIp(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                return false;
            }
            if (address instanceof Inet4Address) {
                byte[] bytes = address.getAddress();
                int first = bytes[0] & 0xff;
                int second = bytes[1] & 0xff;
                return first != 0 && first != 10 && first != 127 && first < 224
                        && !(first == 100 && second >= 64 && second <= 127)
                        && !(first == 169 && second == 254)
                        && !(first == 172 && second >= 16 && second <= 31)
                        && !(first == 192 && second == 168);
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static LinkRecord find(Connection connection, String code) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT code,target_url,created_at,expires_at,click_count,last_accessed_at FROM links WHERE code=?")) {
            query.setString(1, code);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new LinkNotFound("Short link was not found.");
                }
                return new LinkRecord(result.getString("code"), result.getString("target_url"),
                        result.getString("created_at"), result.getString("expires_at"),
                        result.getLong("click_count"), result.getString("last_accessed_at"));
            }
        }
    }

    private static boolean isConstraintViolation(SQLException exception) {
        return exception.getErrorCode() == 19 || String.valueOf(exception.getMessage()).contains("UNIQUE constraint failed");
    }

    private static String newCode() {
        StringBuilder code = new StringBuilder(7);
        for (int index = 0; index < 7; index++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private static void rollback(Connection connection) {
        try (var rollback = connection.createStatement()) {
            rollback.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // Preserve the domain error that caused the rollback.
        }
    }

    public static class InvalidRequest extends RuntimeException {
        public InvalidRequest(String message) { super(message); }
    }
    public static class AliasConflict extends RuntimeException {
        public AliasConflict(String message) { super(message); }
    }
    public static class LinkNotFound extends RuntimeException {
        public LinkNotFound(String message) { super(message); }
    }
    public static class LinkExpired extends RuntimeException {
        public LinkExpired(String message) { super(message); }
    }
    public static class PersistenceFailure extends RuntimeException {
        public PersistenceFailure(String message, Throwable cause) { super(message, cause); }
    }
}
