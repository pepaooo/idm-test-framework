package com.idm.test.scenarios.engine;

import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.i18n.ReportLocale;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.spi.StepType;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.StepRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
        Map<String, StepType> registry = StepRegistry.of(
                ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioValidator validator = EngineFactory.validator(registry);
        ScenarioEngine engine = EngineFactory.engine(registry, testDataGenerator, testExecutor, executorProperties);
        Path results = tempDir.resolve("scenario-results.json");

        List<DynamicTest> tests = ScenarioSupport.buildTests(FIXTURE_DIR, validator, engine, results);

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
        // The load/validation error keeps its key, so the report can show it in another language.
        assertThat(json.indexOf("\"failureDetailKey\" : \"validation.frame.step\"", brokenIndex))
                .as("broken-wait.yaml's failure should keep its key in %s", json)
                .isNotEqualTo(-1);

        String report = java.nio.file.Files.readString(tempDir.resolve("scenario-report.html"));
        assertThat(report).contains("broken-wait.yaml").contains("valid-user.yaml")
                .contains("<details class=\"scenario failed\" open>");
    }

    @Test
    @DisplayName("A failure describing the run does not abort the factory; results and report are still written")
    void testRunInfoFailureDoesNotAbortFactory(@TempDir Path tempDir) throws Exception {
        Map<String, StepType> registry = StepRegistry.of(
                ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioValidator validator = EngineFactory.validator(registry);
        ScenarioEngine engine = EngineFactory.engine(registry, testDataGenerator, testExecutor, executorProperties);
        Path results = tempDir.resolve("scenario-results.json");

        List<DynamicTest> tests = ScenarioSupport.buildTests(FIXTURE_DIR, List.of("valid-user.yaml"), validator,
                engine, results, scenarios -> {
                    throw new IllegalStateException("cannot bind properties");
                });

        assertThat(tests).hasSize(1);
        assertThatCode(() -> tests.get(0).getExecutable().execute()).doesNotThrowAnyException();
        assertThat(java.nio.file.Files.readString(results)).contains("\"valid-user.yaml\"");
        assertThat(java.nio.file.Files.readString(tempDir.resolve("scenario-report.html")))
                .contains("valid-user.yaml");
    }

    @Test
    @DisplayName("An unsupported report locale stops the run instead of falling back to a minimal header")
    void testUnsupportedLocaleStopsTheRun(@TempDir Path tempDir) {
        Map<String, StepType> registry = StepRegistry.of(
                ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioValidator validator = EngineFactory.validator(registry);
        ScenarioEngine engine = EngineFactory.engine(registry, testDataGenerator, testExecutor, executorProperties);
        Path results = tempDir.resolve("scenario-results.json");

        assertThatThrownBy(() -> ScenarioSupport.buildTests(FIXTURE_DIR, List.of("valid-user.yaml"), validator,
                engine, results, scenarios -> {
                    ReportLocale.resolve("fr");
                    return RunInfo.minimal("unused");
                }))
                .isInstanceOf(ReportLocale.UnsupportedLocaleException.class)
                .hasMessage("unsupported report locale 'fr'; supported: en, es");
    }

    @Test
    @DisplayName("In a Spanish run, JUnit's failure text for a failed scenario and for an invalid file is Spanish")
    void testSpanishFailureText(@TempDir Path tempDir) throws Exception {
        Map<String, StepType> registry = StepRegistry.of(
                ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioValidator validator = EngineFactory.validator(registry);
        ScenarioEngine engine = EngineFactory.engine(registry, testDataGenerator, testExecutor, executorProperties);
        RunInfo spanish = RunInfo.minimal("Escenarios").withLocale(Locale.of("es"));

        List<DynamicTest> invalid = ScenarioSupport.buildTests(FIXTURE_DIR, List.of("broken-wait.yaml"), validator,
                engine, tempDir.resolve("invalid-results.json"), scenarios -> spanish);
        assertThatThrownBy(() -> invalid.get(0).getExecutable().execute())
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageStartingWith("broken-wait.yaml, paso 2 (ldap.verify): ")
                .hasMessageContaining("30s");

        List<DynamicTest> failing = ScenarioSupport.buildTests("scenarios-locale-fixture",
                List.of("missing-entry.yaml"), validator, engine, tempDir.resolve("failing-results.json"),
                scenarios -> spanish);
        assertThatThrownBy(() -> failing.get(0).getExecutable().execute())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("missing-entry.yaml, paso 1 (ldap.verify): ldap.verify esperaba que "
                        + "cn=itf46-never-created,ou=users");
    }

    @Test
    @DisplayName("A scenario using every step type should load and validate")
    void testFullVocabularyValidates() {
        Map<String, StepType> registry = StepRegistry.of(
                ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioValidator validator =
                EngineFactory.validator(registry);

        Scenario scenario;
        try (InputStream yaml = getClass().getClassLoader()
                .getResourceAsStream("scenarios-vocabulary-fixture/full-vocabulary.yaml")) {
            assertThat(yaml).isNotNull();
            scenario = ScenarioLoader.load(yaml, "full-vocabulary.yaml");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Proves the authoring contract: every step name is registered, every parameter is
        // accepted, and every ${...} reference resolves in order. The SOAP and REST steps are
        // exercised for real against WireMock in SoapStepsTest, RestStepsTest and HttpStepsTest.
        assertThatCode(() -> validator.validate(scenario, "full-vocabulary.yaml"))
                .doesNotThrowAnyException();
    }
}
