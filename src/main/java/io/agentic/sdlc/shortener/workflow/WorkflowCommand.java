package io.agentic.sdlc.shortener.workflow;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Command line entry point activated by the `sdlc` application argument. */
@Component
public class WorkflowCommand implements ApplicationRunner {
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        List<String> positional = arguments.getNonOptionArgs();
        if (positional.isEmpty() || !positional.get(0).equals("sdlc")) return;
        if (positional.size() < 2) throw new IllegalArgumentException("Choose a workflow command.");
        Path workspace = Path.of(option(arguments, "workspace", ".")).toAbsolutePath().normalize();
        Orchestrator orchestrator = new Orchestrator(workspace);
        String command = positional.get(1);
        Object result = switch (command) {
            case "scenarios" -> orchestrator.scenarios();
            case "run" -> run(orchestrator, option(arguments, "scenario", null));
            case "resume" -> orchestrator.execute(positional(positional, 2, "run id"));
            case "status" -> orchestrator.summary(positional(positional, 2, "run id"));
            case "events" -> orchestrator.events(positional(positional, 2, "run id"));
            case "list" -> orchestrator.listRuns();
            case "metrics" -> orchestrator.metrics();
            case "approve" -> approve(orchestrator, arguments, positional);
            case "revise" -> revise(orchestrator, arguments, positional);
            case "stop" -> orchestrator.requestStop(positional(positional, 2, "run id"),
                    option(arguments, "actor", null), option(arguments, "reason", null));
            case "rollback-release" -> orchestrator.rollbackRelease(positional(positional, 2, "run id"),
                    option(arguments, "actor", null), option(arguments, "rationale", null));
            default -> throw new IllegalArgumentException("Unknown workflow command: " + command);
        };
        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private static Object run(Orchestrator orchestrator, String scenario) {
        if (scenario == null || scenario.isBlank()) throw new IllegalArgumentException("--scenario is required.");
        Map<String, Object> created = orchestrator.createRun(scenario, Map.of());
        return orchestrator.execute(String.valueOf(created.get("run_id")));
    }

    private static Object approve(Orchestrator orchestrator, ApplicationArguments arguments, List<String> positional) {
        return orchestrator.approve(positional(positional, 2, "run id"),
                option(arguments, "checkpoint", null), option(arguments, "actor", null),
                option(arguments, "rationale", null), option(arguments, "decision", "approve"));
    }

    private static Object revise(Orchestrator orchestrator, ApplicationArguments arguments, List<String> positional) {
        Map<String, Object> request = new LinkedHashMap<>();
        String text = option(arguments, "request", null);
        String criteria = option(arguments, "acceptance-criteria", null);
        if (text != null) request.put("request", text);
        if (criteria != null) request.put("acceptance_criteria", Arrays.stream(criteria.split("\\|\\|"))
                .map(String::trim).filter(value -> !value.isEmpty()).toList());
        return orchestrator.revise(positional(positional, 2, "run id"), request, option(arguments, "actor", null));
    }

    private static String positional(List<String> arguments, int index, String label) {
        if (arguments.size() <= index || arguments.get(index).isBlank()) {
            throw new IllegalArgumentException("A " + label + " is required.");
        }
        return arguments.get(index);
    }

    private static String option(ApplicationArguments arguments, String name, String fallback) {
        List<String> values = arguments.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }
}
