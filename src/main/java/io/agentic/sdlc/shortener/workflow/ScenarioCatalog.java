package io.agentic.sdlc.shortener.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScenarioCatalog {
    private static final Map<String, Scenario> SCENARIOS = new LinkedHashMap<>();

    static {
        SCENARIOS.put("greenfield", new Scenario("greenfield", "Build a URL shortener from scratch",
                "Build a reliable HTTP URL shortener with click analytics, expiration, and health checks.", List.of(
                "Create a short link through a versioned HTTP API.",
                "Redirect to the original HTTP or HTTPS destination and count successful clicks.",
                "Support optional expiry and provide click-count analytics.",
                "Reject malformed targets, unsafe schemes, and duplicate custom aliases.",
                "Expose liveness and readiness checks; cover behavior with automated tests."), "greenfield"));
        SCENARIOS.put("brownfield", new Scenario("brownfield", "Add expiry and analytics without breaking redirects",
                "Extend the existing shortener to support expiration and click analytics while preserving redirect behavior.", List.of(
                "Keep GET /r/{code} redirects compatible with existing links.",
                "Add optional link expiry and return 410 for expired links.",
                "Add per-link click statistics without storing visitor IP addresses.",
                "Add regression tests for redirects, expiration, and analytics."), "brownfield"));
        SCENARIOS.put("ambiguous", new Scenario("ambiguous", "Make short links safer and faster for our customers",
                "Make the short links safer and faster for our customers.", List.of(), "ambiguous"));
    }

    private ScenarioCatalog() { }

    public static Scenario get(String name) {
        Scenario result = SCENARIOS.get(name);
        if (result == null) {
            throw new IllegalArgumentException("Unknown scenario " + name + "; choose one of " + String.join(", ", SCENARIOS.keySet()) + ".");
        }
        return result;
    }

    public static Map<String, Scenario> all() {
        return Map.copyOf(SCENARIOS);
    }
}
