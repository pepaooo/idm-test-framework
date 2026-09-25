package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.result.JsonResultWriter;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.StepType;
import com.idm.test.engine.steps.DbExecuteStep;
import com.idm.test.engine.steps.DbVerifyStep;
import com.idm.test.engine.steps.HttpRequestStep;
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
import com.idm.test.scenarios.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

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
import java.util.Map;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs every YAML scenario under src/test/resources/scenarios as its own test, or, when
 * {@code -Dscenario=<glob>} is set, only the files it selects (see {@link #select}).
 * <p>
 * This is the adapter between the engine and JUnit. It is the only place that knows about
 * both: the engine takes its connectors by constructor, and Spring supplies them here.
 */
@DisplayName("YAML scenarios")
class YamlScenarioTest extends BaseIntegrationTest {

    private static final String SCENARIO_DIR = "scenarios";
    private static final Path RESULTS = Path.of("target", "scenario-results.json");

    /** System property naming the scenario file, or glob of files, to run. */
    static final String SCENARIO_PROPERTY = "scenario";

    @TestFactory
    @DisplayName("Each scenario file runs as its own test")
    Stream<DynamicTest> scenarios() throws Exception {
        Map<String, StepType> registry = registry(
                edirectoryConnector, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties);
        ScenarioValidator validator = new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS);
        ScenarioEngine engine = new ScenarioEngine(
                registry,
                new FixtureFactory(testDataGenerator),
                testExecutor,
                executorProperties.getDefaultTimeout(),
                executorProperties.getDefaultPollInterval(),
                new ExpressionEvaluator());

        return buildTests(SCENARIO_DIR, selectedScenarioFiles(SCENARIO_DIR), validator, engine, RESULTS).stream();
    }

    /**
     * Builds a test for every scenario file in {@code scenarioDir}, unfiltered.
     */
    static List<DynamicTest> buildTests(
            String scenarioDir, ScenarioValidator validator, ScenarioEngine engine, Path resultsPath)
            throws URISyntaxException {
        return buildTests(scenarioDir, scenarioFiles(scenarioDir), validator, engine, resultsPath);
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
     */
    static List<DynamicTest> buildTests(
            String scenarioDir, List<String> files, ScenarioValidator validator, ScenarioEngine engine,
            Path resultsPath) throws URISyntaxException {

        List<ScenarioResult> collected = Collections.synchronizedList(new ArrayList<>());
        List<DynamicTest> tests = new ArrayList<>();

        for (String file : files) {
            DynamicTest test;
            try {
                Scenario scenario = ScenarioLoader.load(resource(scenarioDir, file), file);
                validator.validate(scenario, file);

                test = DynamicTest.dynamicTest(file, () -> {
                    ScenarioResult result = engine.run(scenario, file);
                    collected.add(result);
                    JsonResultWriter.write(List.copyOf(collected), resultsPath);

                    assertThat(result.success())
                            .as(result.failureSummary())
                            .isTrue();
                });
            } catch (RuntimeException e) {
                String message = e.getMessage();
                test = DynamicTest.dynamicTest(file, () -> {
                    ScenarioResult result = new ScenarioResult(file, file, false, 0L, List.of(), message);
                    collected.add(result);
                    JsonResultWriter.write(List.copyOf(collected), resultsPath);
                    fail(message);
                });
            }
            tests.add(test);
        }
        return tests;
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
            EdirectoryConnector edirectoryConnector,
            MultiDatabaseConnector databaseConnector,
            RoleServiceConnector roleServiceConnector,
            ResourceServiceConnector resourceServiceConnector,
            IdmRestConnector restConnector,
            RestProperties restProperties) {
        Map<String, StepType> registry = new LinkedHashMap<>();
        for (StepType type : List.of(
                new LdapCreateStep(edirectoryConnector),
                new LdapVerifyStep(edirectoryConnector),
                new LdapUpdateStep(edirectoryConnector),
                new LdapDeleteStep(edirectoryConnector),
                new DbExecuteStep(databaseConnector),
                new DbVerifyStep(databaseConnector),
                new RolesGrantStep(roleServiceConnector),
                new RolesRevokeStep(roleServiceConnector),
                new ResourcesGrantStep(resourceServiceConnector),
                new ResourcesRevokeStep(resourceServiceConnector),
                new RestRequestStep(restConnector, restProperties),
                new RestVerifyStep(restConnector, restProperties),
                new HttpRequestStep(restConnector, restProperties))) {
            registry.put(type.name(), type);
        }
        return registry;
    }

    private static InputStream resource(String scenarioDir, String file) {
        return YamlScenarioTest.class.getClassLoader()
                .getResourceAsStream(scenarioDir + "/" + file);
    }

    /**
     * Lists the scenario file names on the classpath, under {@code scenarioDir}.
     */
    static List<String> scenarioFiles(String scenarioDir) throws URISyntaxException {
        var url = YamlScenarioTest.class.getClassLoader().getResource(scenarioDir);
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
