package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.ValidationException;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.i18n.ReportLocale;
import com.idm.test.engine.result.HtmlReportWriter;
import com.idm.test.engine.result.JsonResultWriter;
import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepType;
import com.idm.test.engine.steps.DbExecuteStep;
import com.idm.test.engine.steps.DbVerifyStep;
import com.idm.test.engine.steps.HttpRequestStep;
import com.idm.test.engine.steps.HttpVerifyStep;
import com.idm.test.engine.steps.LdapCreateStep;
import com.idm.test.engine.steps.LdapDeleteStep;
import com.idm.test.engine.steps.LdapUpdateStep;
import com.idm.test.engine.steps.LdapVerifyStep;
import com.idm.test.engine.steps.ResourcesGrantStep;
import com.idm.test.engine.steps.ResourcesRevokeStep;
import com.idm.test.engine.steps.RestRequestStep;
import com.idm.test.engine.steps.RestVerifyStep;
import com.idm.test.engine.steps.RolesGrantStep;
import com.idm.test.engine.steps.RolesRevokeStep;
import org.junit.jupiter.api.DynamicTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The scenario helpers every runner shares: the step registry, file discovery and
 * {@code -Dscenario} selection, and turning files into dynamic tests.
 * <p>
 * Extends nothing, on purpose. {@code BaseIntegrationTest} starts the embedded LDAP servers and
 * WireMock in its static initialisers, so helpers reached through a subclass of it would start
 * them for the real-environment and container runners too (ITF-17).
 * {@code ScenarioSupportIsolationTest} checks this.
 */
final class ScenarioSupport {

    private static final Logger LOG = LoggerFactory.getLogger(ScenarioSupport.class);

    private ScenarioSupport() {
    }

    /** System property naming the scenario file, or glob of files, to run. */
    static final String SCENARIO_PROPERTY = "scenario";

    /** The header used when describing the run failed; always English. */
    private static RunInfo minimalRun() {
        return RunInfo.minimal(Messages.english(Message.of("runner.name.scenarios")));
    }

    /**
     * Builds a test for every scenario file in {@code scenarioDir}, unfiltered.
     */
    static List<DynamicTest> buildTests(
            String scenarioDir, ScenarioValidator validator, ScenarioEngine engine, Path resultsPath)
            throws URISyntaxException {
        return buildTests(scenarioDir, validator, engine, resultsPath, scenarios -> minimalRun());
    }

    /** As above, describing the run in the HTML report with {@code runInfo}. */
    static List<DynamicTest> buildTests(
            String scenarioDir, ScenarioValidator validator, ScenarioEngine engine, Path resultsPath,
            Function<List<Scenario>, RunInfo> runInfo) throws URISyntaxException {
        return buildTests(scenarioDir, scenarioFiles(scenarioDir), validator, engine, resultsPath, runInfo);
    }

    static List<DynamicTest> buildTests(
            String scenarioDir, List<String> files, ScenarioValidator validator, ScenarioEngine engine,
            Path resultsPath) throws URISyntaxException {
        return buildTests(scenarioDir, files, validator, engine, resultsPath,
                scenarios -> minimalRun());
    }

    /**
     * Loads and validates each of {@code files} (names within {@code scenarioDir}) up front,
     * before any dynamic test runs, so a malformed twentieth file is reported immediately rather than
     * after its predecessors have already spent minutes waiting on provisioning.
     * <p>
     * A file that loads and validates cleanly becomes a dynamic test that runs the engine.
     * A file that fails to load or validate still becomes a dynamic test — one that fails
     * immediately with that error — so a typo in one file never hides the scenarios that are
     * fine, and every file still ends up with a {@link ScenarioResult} in the JSON output.
     * <p>
     * Catches {@link RuntimeException} broadly, not just {@code ValidationException}:
     * {@code ScenarioLoader} and {@code ScenarioValidator} only ever throw the latter today,
     * but trusting that here would mean any other failure during load or validation escapes
     * this factory method entirely — aborting the whole {@code @TestFactory} with zero
     * dynamic tests and no results file, the exact failure shape this method exists to avoid.
     * <p>
     * Every result is written as JSON and, beside it, as an HTML report (ITF-36).
     */
    static List<DynamicTest> buildTests(
            String scenarioDir, List<String> files, ScenarioValidator validator, ScenarioEngine engine,
            Path resultsPath, Function<List<Scenario>, RunInfo> runInfo) throws URISyntaxException {

        Map<String, Scenario> loaded = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        // A load or validation problem by key, so the report can show it in the run's language.
        Map<String, Message> failureDetails = new LinkedHashMap<>();
        for (String file : files) {
            try {
                Scenario scenario = ScenarioLoader.load(resource(scenarioDir, file), file);
                validator.validate(scenario, file);
                loaded.put(file, scenario);
            } catch (RuntimeException e) {
                failures.put(file, e.getMessage());
                if (e instanceof ValidationException validation && validation.detail() != null) {
                    failureDetails.put(file, validation.detail());
                }
            }
        }
        RunInfo run;
        try {
            run = runInfo.apply(List.copyOf(loaded.values()));
        } catch (ReportLocale.UnsupportedLocaleException e) {
            // A misconfigured language is a configuration error, like a bad URL: stop the run.
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Could not describe the run for the HTML report, using a minimal header: {}", e.toString());
            run = minimalRun();
        }
        RunInfo reportRun = run;
        // JUnit's failure text follows the report's language; English when the run could not be described.
        Locale locale = reportRun.locale();

        List<ScenarioResult> collected = Collections.synchronizedList(new ArrayList<>());
        List<DynamicTest> tests = new ArrayList<>();
        for (String file : files) {
            if (loaded.containsKey(file)) {
                Scenario scenario = loaded.get(file);
                tests.add(DynamicTest.dynamicTest(file, () -> {
                    ScenarioResult result = engine.run(scenario, file);
                    collected.add(result);
                    writeResults(collected, resultsPath, reportRun);

                    assertThat(result.success())
                            .as(result.failureSummary(locale))
                            .isTrue();
                }));
            } else {
                String message = failures.get(file);
                Message detail = failureDetails.get(file);
                tests.add(DynamicTest.dynamicTest(file, () -> {
                    ScenarioResult result = new ScenarioResult(file, file, false, 0L, List.of(), List.of(), message,
                            null, detail == null ? null : detail.key(), detail == null ? null : detail.args());
                    collected.add(result);
                    writeResults(collected, resultsPath, reportRun);
                    fail(detail == null ? message : Messages.render(detail, locale));
                }));
            }
        }
        return tests;
    }

    /**
     * The JSON results and, beside them, the HTML report, both of everything run so far. The JSON
     * is the record and its failure is fatal; the report is auxiliary, so a failure to write it is
     * logged and never changes a scenario's outcome.
     */
    static void writeResults(List<ScenarioResult> collected, Path resultsPath, RunInfo run) {
        List<ScenarioResult> snapshot = List.copyOf(collected);
        JsonResultWriter.write(snapshot, resultsPath);
        Path report = reportPath(resultsPath);
        try {
            HtmlReportWriter.write(run, snapshot, report);
        } catch (RuntimeException e) {
            LOG.warn("Could not write the HTML report to {}: {}", report, e.toString());
        }
    }

    /** {@code x-results.json} becomes {@code x-report.html}; any other name gets {@code .html} appended. */
    static Path reportPath(Path resultsPath) {
        String name = resultsPath.getFileName().toString();
        String suffix = "-results.json";
        String report = name.endsWith(suffix)
                ? name.substring(0, name.length() - suffix.length()) + "-report.html"
                : name + ".html";
        return resultsPath.resolveSibling(report);
    }

    /**
     * The scenario files in {@code scenarioDir} selected by {@code -Dscenario}, or all of them
     * when it is not set. The only place that reads the property.
     */
    static List<String> selectedScenarioFiles(String scenarioDir) throws URISyntaxException {
        return select(scenarioFiles(scenarioDir), System.getProperty(SCENARIO_PROPERTY), scenarioDir);
    }

    /**
     * Narrows {@code files} to those matching {@code pattern}, a glob tried against each file
     * name with and without its {@code .yaml} extension. A {@code null} pattern means no
     * filter was asked for and returns {@code files} unchanged.
     * <p>
     * Every other outcome that would select nothing throws instead: a blank pattern, an
     * invalid glob, or a pattern matching no file. A filtered run that ran zero scenarios
     * and reported green would be the vacuous success this project keeps closing.
     * {@code scenarioDir} is used only in messages.
     */
    static List<String> select(List<String> files, String pattern, String scenarioDir) {
        if (pattern == null) {
            return files;
        }
        if (pattern.isBlank()) {
            throw new IllegalStateException("-D" + SCENARIO_PROPERTY + " is set but empty. Name a scenario file or"
                    + " glob, for example -D" + SCENARIO_PROPERTY + "=provisioning-user-to-ldap.yaml, or leave it"
                    + " unset to run every scenario in " + scenarioDir + ".");
        }

        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException("-D" + SCENARIO_PROPERTY + "=" + pattern
                    + " is not a valid glob: " + e.getDescription(), e);
        }

        List<String> selected = files.stream()
                .filter(file -> matcher.matches(Path.of(file)) || matcher.matches(Path.of(stripYaml(file))))
                .toList();

        if (selected.isEmpty()) {
            throw new IllegalStateException("-D" + SCENARIO_PROPERTY + "=" + pattern + " matches no scenario in "
                    + scenarioDir + ". Available: " + (files.isEmpty() ? "none" : String.join(", ", files)));
        }
        return selected;
    }

    private static String stripYaml(String file) {
        return file.endsWith(".yaml") ? file.substring(0, file.length() - ".yaml".length()) : file;
    }

    static Map<String, StepType> registry(
            LdapDirectories ldapDirectories,
            MultiDatabaseConnector databaseConnector,
            RoleServiceConnector roleServiceConnector,
            ResourceServiceConnector resourceServiceConnector,
            IdmRestConnector restConnector,
            RestProperties restProperties,
            RestAuthenticator restAuthenticator) {
        Map<String, StepType> registry = new LinkedHashMap<>();
        for (StepType type : List.of(
                new LdapCreateStep(ldapDirectories),
                new LdapVerifyStep(ldapDirectories),
                new LdapUpdateStep(ldapDirectories),
                new LdapDeleteStep(ldapDirectories),
                new DbExecuteStep(databaseConnector),
                new DbVerifyStep(databaseConnector),
                new RolesGrantStep(roleServiceConnector),
                new RolesRevokeStep(roleServiceConnector),
                new ResourcesGrantStep(resourceServiceConnector),
                new ResourcesRevokeStep(resourceServiceConnector),
                new RestRequestStep(restConnector, restProperties, restAuthenticator),
                new RestVerifyStep(restConnector, restProperties, restAuthenticator),
                new HttpRequestStep(restConnector, restProperties, restAuthenticator),
                new HttpVerifyStep(restConnector, restProperties, restAuthenticator))) {
            registry.put(type.name(), type);
        }
        return registry;
    }

    private static InputStream resource(String scenarioDir, String file) {
        return ScenarioSupport.class.getClassLoader()
                .getResourceAsStream(scenarioDir + "/" + file);
    }

    /**
     * Lists the scenario file names on the classpath, under {@code scenarioDir}.
     */
    static List<String> scenarioFiles(String scenarioDir) throws URISyntaxException {
        var url = ScenarioSupport.class.getClassLoader().getResource(scenarioDir);
        if (url == null) {
            return List.of();
        }

        Path dir = Path.of(url.toURI());
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(path -> path.toString().endsWith(".yaml"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list scenarios in " + dir, e);
        }
    }
}
