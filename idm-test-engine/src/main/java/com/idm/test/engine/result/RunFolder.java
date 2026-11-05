package com.idm.test.engine.result;

import com.idm.test.engine.spi.JsonMappers;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A finished run read back from its folder: {@code results.json} and, beside it, {@code run.json}.
 * Folders written before {@code run.json} existed still read; what they lack is worked out from the
 * folder's name and the scenario durations.
 *
 * @param runId      the folder's name
 * @param run        the run's description; its locale is the one the report is rendered in
 * @param finishedAt when the run finished
 * @param results    the scenario results, secret parameters already masked
 */
public record RunFolder(String runId, RunInfo run, Instant finishedAt, List<ScenarioResult> results) {

    /** The folder cannot be read as a run: {@code results.json} is missing or does not parse. */
    public static final class Unreadable extends RuntimeException {

        public Unreadable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final String RESULTS = "results.json";
    static final String RUN = "run.json";

    // The writer leaves an unset flag as null (ScenarioResult.stopped); a null flag is false.
    private static final JsonMapper JSON = JsonMappers.builder()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();
    private static final Pattern NAME = Pattern.compile("(\\d{8}-\\d{6})-(.+)");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * @param override the language to force, or null to use the run's own
     * @param fallback the language when {@code override} is null and the run recorded none; English when null
     * @throws Unreadable when {@code results.json} is missing or does not parse
     */
    public static RunFolder read(Path folder, Locale override, Locale fallback) {
        String runId = String.valueOf(folder.getFileName());
        List<ScenarioResult> results = readResults(folder.resolve(RESULTS));
        RunRecord record = readRecord(folder.resolve(RUN));
        Locale defaultLocale = fallback == null ? Locale.ENGLISH : fallback;

        RunInfo run;
        Instant finishedAt;
        if (record != null) {
            run = record.run();
            finishedAt = record.finishedAt();
        } else {
            run = inferred(runId, defaultLocale);
            long total = results.stream().mapToLong(ScenarioResult::durationMillis).sum();
            finishedAt = run.startedAt().plusMillis(total);
        }
        if (override != null) {
            run = run.withLocale(override);
        } else if (record == null) {
            run = run.withLocale(defaultLocale);
        }
        return new RunFolder(runId, run, finishedAt, List.copyOf(results));
    }

    private static List<ScenarioResult> readResults(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new Unreadable("There is no " + RESULTS + " in " + file.getParent(), null);
        }
        try {
            List<ScenarioResult> results = JSON.readValue(file.toFile(), new TypeReference<List<ScenarioResult>>() {
            });
            if (results == null) {
                throw new Unreadable("Could not read " + file + ": it holds no results", null);
            }
            return results;
        } catch (JacksonException e) {
            throw new Unreadable("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    /** The {@code run.json}, or null when it is missing or does not parse: the run still reads without it. */
    private static RunRecord readRecord(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            RunRecord record = JSON.readValue(file.toFile(), RunRecord.class);
            return record == null || record.run() == null || record.run().startedAt() == null
                    || record.finishedAt() == null ? null : record;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static RunInfo inferred(String runId, Locale locale) {
        Matcher match = NAME.matcher(runId);
        if (match.matches()) {
            try {
                Instant started = LocalDateTime.parse(match.group(1), STAMP).atZone(ZoneId.systemDefault())
                        .toInstant();
                return new RunInfo(null, started, List.of(match.group(2)), null, Map.of(), locale);
            } catch (DateTimeParseException ignored) {
                // not a run folder's name; fall through
            }
        }
        return new RunInfo(null, Instant.EPOCH, List.of(), null, Map.of(), locale);
    }
}
