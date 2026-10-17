package com.idm.test.scenarios.engine;

import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.spi.StepType;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.StepRegistry;
import com.idm.test.wiring.RunInfos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs every YAML scenario under src/test/resources/scenarios as its own test, or, when
 * {@code -Dscenario=<glob>} is set, only the files it selects (see {@link ScenarioSupport#select}).
 * <p>
 * This is the adapter between the engine and JUnit. It is the only place that knows about
 * both: the engine takes its connectors by constructor, and Spring supplies them here.
 */
@DisplayName("YAML scenarios")
class YamlScenarioTest extends BaseIntegrationTest {

    private static final String SCENARIO_DIR = "scenarios";
    private static final Path RESULTS = Path.of("target", "scenario-results.json");

    @Autowired
    private Environment environment;

    @TestFactory
    @DisplayName("Each scenario file runs as its own test")
    Stream<DynamicTest> scenarios() throws Exception {
        Map<String, StepType> registry = StepRegistry.of(
                ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioValidator validator = EngineFactory.validator(registry);
        ScenarioEngine engine = EngineFactory.engine(registry, testDataGenerator, testExecutor, executorProperties);

        return ScenarioSupport.buildTests(SCENARIO_DIR, ScenarioSupport.selectedScenarioFiles(SCENARIO_DIR),
                validator, engine, RESULTS,
                scenarios -> RunInfos.of("runner.name.embedded", environment, scenarios)).stream();
    }
}
