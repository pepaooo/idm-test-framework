package com.idm.test.scenarios.engine;

import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.spi.StepType;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs scenarios-directories/ against both embedded directories: the default one and
 * {@code second}. Kept out of the main corpus because ContainerScenarios runs that corpus
 * against a single OpenLDAP container.
 */
@DisplayName("YAML scenarios across two directories")
class MultiDirectoryScenarioTest extends BaseIntegrationTest {

    private static final String SCENARIO_DIR = "scenarios-directories";
    private static final Path RESULTS = Path.of("target", "multi-directory-results.json");

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("Both embedded directories are configured, and the old block's is the default")
    void testDirectoriesConfigured() {
        assertThat(ldapDirectories.names()).containsExactly("default", "second");
        assertThat(ldapDirectories.defaultName()).contains("default");
    }

    @TestFactory
    @DisplayName("Each scenario file runs as its own test")
    Stream<DynamicTest> scenarios() throws Exception {
        Map<String, StepType> registry = ScenarioSupport.registry(
                ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioValidator validator = new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS);
        ScenarioEngine engine = new ScenarioEngine(
                registry,
                new FixtureFactory(testDataGenerator),
                testExecutor,
                executorProperties.getDefaultTimeout(),
                executorProperties.getDefaultPollInterval(),
                new ExpressionEvaluator());

        return ScenarioSupport.buildTests(SCENARIO_DIR, validator, engine, RESULTS,
                scenarios -> RunInfos.of("Embedded (two directories)", environment, scenarios)).stream();
    }
}
