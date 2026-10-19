package com.idm.test.runner.ui;

import com.idm.test.engine.result.CleanupResult;
import com.idm.test.engine.result.CleanupStatus;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.FailureKind;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what a workspace's runs left in {@code reports/<run-id>/results.json}. Never throws: a
 * folder or file that cannot be read, or is not a results file, is passed over.
 */
public final class LastRuns {

    private static final String RESULTS = "results.json";
    /** {@code yyyyMMdd-HHmmss-<profile>}, with {@code -2}, {@code -3} when that folder existed. */
    private static final Pattern RUN_ID = Pattern.compile("\\d{8}-\\d{6}-(.+?)(-\\d+)?");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LastRuns() {
    }

    /**
     * How one run ended, as its results file tells it. Every scenario in it counts once, as {@link
     * com.idm.test.runner.run.RunOutcome} counts it: passed, a finding or a setup problem.
     *
     * @param runId the run folder's name
     * @param at    when the results file was last written (the end of the run, or its latest scenario),
     *              ISO-8601 in UTC
     * @param profile the profile, from the run id; null when the folder name is not a run id
     */
    public record LastRun(String runId, String at, String profile, int passed, int findings, int setupProblems) {
    }

    /** The run whose {@code results.json} was written last, or empty when the workspace never ran. */
    public static Optional<LastRun> newest(Path workspace) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> runs = Files.newDirectoryStream(workspace.resolve("reports"))) {
            for (Path run : runs) {
                Path file = run.resolve(RESULTS);
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        } catch (IOException | UncheckedIOException | SecurityException e) {
            return Optional.empty();
        }
        files.sort(Comparator.comparing(LastRuns::modified).reversed());
        for (Path file : files) {
            Optional<LastRun> run = read(file, workspace);
            if (run.isPresent()) {
                return run;
            }
        }
        return Optional.empty();
    }

    /** The run of one {@code results.json}, or empty when it cannot be read as one. */
    public static Optional<LastRun> read(Path resultsJson, Path workspace) {
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(resultsJson));
            if (root == null || !root.isArray()) {
                return Optional.empty();
            }
            int passed = 0;
            int findings = 0;
            int setup = 0;
            for (JsonNode scenario : root) {
                ScenarioResult result = outline(scenario);
                if (result.success()) {
                    passed++;
                } else if (result.failureKind() == FailureKind.FINDING) {
                    findings++;
                } else {
                    setup++;
                }
            }
            String runId = resultsJson.getParent().getFileName().toString();
            return Optional.of(new LastRun(runId, modified(resultsJson).toInstant().toString(),
                    profile(runId, workspace), passed, findings, setup));
        } catch (IOException | JacksonException | IllegalArgumentException | SecurityException e) {
            return Optional.empty();
        }
    }

    /**
     * Just what {@link ScenarioResult#failureKind()} looks at — success, each step's success and kind,
     * the failure detail, each cleanup entry's status — so the rule stays in one place.
     */
    private static ScenarioResult outline(JsonNode scenario) {
        List<StepResult> steps = new ArrayList<>();
        for (JsonNode step : scenario.path("steps")) {
            String kind = step.path("failureKind").isString() ? step.path("failureKind").asString() : null;
            if (kind != null) {
                FailureKind.valueOf(kind); // an unknown kind is not a results file of ours
            }
            steps.add(new StepResult(step.path("index").asInt(), step.path("type").asString(null), null,
                    step.path("success").asBoolean(), 0, null, null, null, null, null, null, null, kind));
        }
        List<CleanupResult> cleanup = new ArrayList<>();
        for (JsonNode entry : scenario.path("cleanup")) {
            CleanupStatus status = entry.path("status").isString()
                    ? CleanupStatus.valueOf(entry.path("status").asString()) : null;
            cleanup.add(new CleanupResult(entry.path("index").asInt(), entry.path("type").asString(null), null,
                    status, 0, null));
        }
        String detail = scenario.path("failureDetail").isString() ? scenario.path("failureDetail").asString() : null;
        return new ScenarioResult(scenario.path("name").asString(null), scenario.path("sourceName").asString(null),
                scenario.path("success").asBoolean(), 0, steps, cleanup, detail);
    }

    /**
     * The profile in {@code runId}. A trailing {@code -<n>} is the collision suffix unless the whole
     * rest names a profile file of the workspace (a profile called {@code test-2}).
     */
    private static String profile(String runId, Path workspace) {
        Matcher matcher = RUN_ID.matcher(runId);
        if (!matcher.matches()) {
            return null;
        }
        String whole = runId.substring(16);
        if (matcher.group(2) != null && Files.isRegularFile(workspace.resolve("config/application-" + whole + ".yml"))) {
            return whole;
        }
        return matcher.group(1);
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException | SecurityException e) {
            return FileTime.fromMillis(0);
        }
    }
}
