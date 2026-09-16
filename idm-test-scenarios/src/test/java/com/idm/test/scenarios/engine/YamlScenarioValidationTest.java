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
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the {@code @TestFactory} in {@link YamlScenarioTest} delivers on the framework's
 * governing principle — every file is checked before any of them run, and a bad file never
 * hides the ones that are fine.
 * <p>
 * The fixture files this exercises live under {@code scenarios-validation-fixture/}, a
 * directory the real {@code scenarios/} suite never scans, precisely so the deliberately
 * broken file here does not become a permanently-failing test in the default build. Instead
 * of letting JUnit discover these as dynamic tests, this test builds them the same way
 * {@code YamlScenarioTest} does and then drives their executables itself, asserting on the
 * outcome — so this test stays green even though one of the files it covers is invalid.
 */
@DisplayName("YAML scenario factory: validation and reporting for a mixed file set")
class YamlScenarioValidationTest extends BaseIntegrationTest {

    private static final String FIXTURE_DIR = "scenarios-validation-fixture";

    @Test
    @DisplayName("Produces a test for every file; the invalid one fails by name, the valid one still passes")
    void testFactoryHandlesMixOfValidAndInvalidFiles(@TempDir Path tempDir) throws Exception {
        Map<String, StepType> registry = YamlScenarioTest.registry(edirectoryConnector, databaseConnector);
        ScenarioValidator validator = new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS);
        ScenarioEngine engine = new ScenarioEngine(
                registry,
                new FixtureFactory(testDataGenerator),
                testExecutor,
                executorProperties.getDefaultTimeout(),
                executorProperties.getDefaultPollInterval(),
                new ExpressionEvaluator());
        Path results = tempDir.resolve("scenario-results.json");

        List<DynamicTest> tests = YamlScenarioTest.buildTests(FIXTURE_DIR, validator, engine, results);

        // Every file on disk gets a dynamic test, whether or not it is valid.
        assertThat(tests).extracting(DynamicTest::getDisplayName)
                .containsExactlyInAnyOrder("valid-user.yaml", "broken-wait.yaml");

        DynamicTest validTest = tests.stream()
                .filter(t -> t.getDisplayName().equals("valid-user.yaml"))
                .findFirst().orElseThrow();
        DynamicTest brokenTest = tests.stream()
                .filter(t -> t.getDisplayName().equals("broken-wait.yaml"))
                .findFirst().orElseThrow();

        // The invalid file fails immediately, naming itself and the bad value — it never
        // gets as far as running a single step.
        assertThatThrownBy(() -> brokenTest.getExecutable().execute())
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("broken-wait.yaml")
                .hasMessageContaining("30s");

        // The valid file is unaffected by its broken neighbor and still runs to success.
        assertThatCode(() -> validTest.getExecutable().execute()).doesNotThrowAnyException();

        // Both outcomes — including the one that never ran a step — are recorded, so a
        // report built from this file shows every scenario, not just the ones that ran.
        // Checking the name alone would pass even if the broken scenario were recorded as a
        // false success, so this also pins down that its "success" field is false.
        String json = java.nio.file.Files.readString(results);
        assertThat(json).contains("\"broken-wait.yaml\"").contains("\"valid-user.yaml\"");

        int brokenIndex = json.indexOf("\"broken-wait.yaml\"");
        assertThat(json.indexOf("\"success\" : false", brokenIndex))
                .as("broken-wait.yaml's recorded result should have success: false in %s", json)
                .isNotEqualTo(-1);
    }
}
