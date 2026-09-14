package com.idm.test.scenarios.engine;

import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.result.JsonResultWriter;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.StepType;
import com.idm.test.engine.steps.LdapCreateStep;
import com.idm.test.engine.steps.LdapVerifyStep;
import com.idm.test.scenarios.base.BaseIntegrationTest;
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

    @TestFactory
    @DisplayName("Each scenario file runs as its own test")
    Stream<DynamicTest> scenarios() throws Exception {
        Map<String, StepType> registry = registry();
        ScenarioValidator validator = new ScenarioValidator(registry);
        ScenarioEngine engine = new ScenarioEngine(
                registry,
                new FixtureFactory(testDataGenerator),
                testExecutor,
                executorProperties.getDefaultTimeout(),
                executorProperties.getDefaultPollInterval());

        List<ScenarioResult> collected = Collections.synchronizedList(new ArrayList<>());

        return scenarioFiles().stream().map(file -> DynamicTest.dynamicTest(file, () -> {
            Scenario scenario = ScenarioLoader.load(resource(file), file);
            validator.validate(scenario, file);

            ScenarioResult result = engine.run(scenario, file);
            collected.add(result);
            JsonResultWriter.write(List.copyOf(collected), RESULTS);

            assertThat(result.success())
                    .as(result.failureSummary())
                    .isTrue();
        }));
    }

    private Map<String, StepType> registry() {
        Map<String, StepType> registry = new LinkedHashMap<>();
        for (StepType type : List.of(
                new LdapCreateStep(edirectoryConnector),
                new LdapVerifyStep(edirectoryConnector))) {
            registry.put(type.name(), type);
        }
        return registry;
    }

    private static InputStream resource(String file) {
        return YamlScenarioTest.class.getClassLoader()
                .getResourceAsStream(SCENARIO_DIR + "/" + file);
    }

    /**
     * Lists the scenario file names on the classpath.
     */
    private static List<String> scenarioFiles() throws URISyntaxException {
        var url = YamlScenarioTest.class.getClassLoader().getResource(SCENARIO_DIR);
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
