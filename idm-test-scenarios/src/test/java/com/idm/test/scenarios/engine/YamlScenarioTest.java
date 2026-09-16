package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.ldap.client.EdirectoryConnector;
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
import com.idm.test.engine.steps.LdapCreateStep;
import com.idm.test.engine.steps.LdapDeleteStep;
import com.idm.test.engine.steps.LdapUpdateStep;
import com.idm.test.engine.steps.LdapVerifyStep;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs every YAML scenario under src/test/resources/scenarios as its own test.
 * <p>
 * This is the adapter between the engine and JUnit. It is the only place that knows about
 * both: the engine takes its connectors by constructor, and Spring supplies them here.
 */
@DisplayName("YAML scenarios")
class YamlScenarioTest extends BaseIntegrationTest {

    private static final String SCENARIO_DIR = "scenarios";
    private static final Path RESULTS = Path.of("target", "scenario-results.json");

    /**
     * The scenarios module configures an H2 database named test-db but no schema, so the
     * table the database scenario works against is created here. CREATE TABLE IF NOT EXISTS
     * is idempotent, which is why this can safely run per test.
     */
    @BeforeEach
    void createScenarioTable() {
        databaseConnector.executeUpdate("test-db",
                "CREATE TABLE IF NOT EXISTS sap_users ("
                        + "username VARCHAR(50), status VARCHAR(20))");
    }

    @TestFactory
    @DisplayName("Each scenario file runs as its own test")
    Stream<DynamicTest> scenarios() throws Exception {
        Map<String, StepType> registry = registry(edirectoryConnector, databaseConnector);
        ScenarioValidator validator = new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS);
        ScenarioEngine engine = new ScenarioEngine(
                registry,
                new FixtureFactory(testDataGenerator),
                testExecutor,
                executorProperties.getDefaultTimeout(),
                executorProperties.getDefaultPollInterval(),
                new ExpressionEvaluator());

        return buildTests(SCENARIO_DIR, validator, engine, RESULTS).stream();
    }

    /**
     * Loads and validates every scenario file in {@code scenarioDir} up front, before any
     * dynamic test runs, so a malformed twentieth file is reported immediately rather than
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
            String scenarioDir, ScenarioValidator validator, ScenarioEngine engine, Path resultsPath)
            throws URISyntaxException {

        List<ScenarioResult> collected = Collections.synchronizedList(new ArrayList<>());
        List<DynamicTest> tests = new ArrayList<>();

        for (String file : scenarioFiles(scenarioDir)) {
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

    static Map<String, StepType> registry(
            EdirectoryConnector edirectoryConnector, MultiDatabaseConnector databaseConnector) {
        Map<String, StepType> registry = new LinkedHashMap<>();
        for (StepType type : List.of(
                new LdapCreateStep(edirectoryConnector),
                new LdapVerifyStep(edirectoryConnector),
                new LdapUpdateStep(edirectoryConnector),
                new LdapDeleteStep(edirectoryConnector),
                new DbExecuteStep(databaseConnector),
                new DbVerifyStep(databaseConnector))) {
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
