package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.core.config.TestExecutorProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs the YAML scenarios under src/test/resources/scenarios-real against whatever
 * environment the active profile names: a real eDirectory, IdM and databases.
 * <p>
 * Deliberately unlike {@link YamlScenarioTest}. It extends nothing, pins no profile and
 * overrides no property, so every {@code idm.test.*} value comes from application.yml and
 * {@code IDM_TEST_PROFILE}. {@code BaseIntegrationTest} cannot be used here, because it pins
 * the embedded infrastructure three ways and a profile change defeats none of them.
 * {@code RealEnvironmentScenariosStructureTest} keeps it that way.
 * <p>
 * Named without a {@code Test} suffix so surefire's includes skip it in the default build.
 * Run it explicitly:
 * <pre>
 * IDM_TEST_PROFILE=dev ./mvnw test -pl idm-test-scenarios -am \
 *   -Dtest=RealEnvironmentScenarios -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * The guards below run before any scenario. A connection error is loud on its own. The
 * failure they prevent is the quiet one: a run against the embedded configuration, a
 * placeholder, or an empty directory that reports green.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class})
@DisplayName("Real-environment scenarios")
public class RealEnvironmentScenarios {

    static final String SCENARIO_DIR = "scenarios-real";
    static final String EMBEDDED_PROFILE = "test";
    static final String LDAP_URL_PLACEHOLDER = "ldap://localhost:389";
    static final String SOAP_URL_PLACEHOLDER = "https://idm.company.com:8543/IDMProv/role/service";

    private static final Path RESULTS = Path.of("target", "real-environment-results.json");

    @Autowired
    private Environment environment;

    @Autowired
    private EdirectoryConnector edirectoryConnector;

    @Autowired
    private MultiDatabaseConnector databaseConnector;

    @Autowired
    private RoleServiceConnector roleServiceConnector;

    @Autowired
    private ResourceServiceConnector resourceServiceConnector;

    @Autowired
    private IdmRestConnector idmRestConnector;

    @Autowired
    private RestProperties restProperties;

    @Autowired
    private TestExecutor testExecutor;

    @Autowired
    private TestExecutorProperties executorProperties;

    @Autowired
    private TestDataGenerator testDataGenerator;

    @TestFactory
    @DisplayName("Each real-environment scenario file runs as its own test")
    Stream<DynamicTest> scenarios() throws Exception {
        requireRealTarget(environment);
        requireScenarios(YamlScenarioTest.scenarioFiles(SCENARIO_DIR));

        Map<String, StepType> registry = YamlScenarioTest.registry(
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

        return YamlScenarioTest.buildTests(SCENARIO_DIR, validator, engine, RESULTS).stream();
    }

    /**
     * Guards 1 and 2. Refuses the embedded profile, and refuses a directory or role-service
     * URL that nobody actually named.
     */
    static void requireRealTarget(Environment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains(EMBEDDED_PROFILE)) {
            throw new IllegalStateException("Active profiles " + Arrays.toString(environment.getActiveProfiles())
                    + " include '" + EMBEDDED_PROFILE + "', the embedded test configuration. Set IDM_TEST_PROFILE"
                    + " to the profile that names your real environment, for example IDM_TEST_PROFILE=dev.");
        }
        requireNamed(environment, "idm.test.ldap.url", "IDM_TEST_LDAP_URL", LDAP_URL_PLACEHOLDER);
        requireNamed(environment, "idm.test.soap.url", "IDM_TEST_SOAP_URL", SOAP_URL_PLACEHOLDER);
    }

    private static void requireNamed(Environment environment, String property, String variable, String placeholder) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is not set. Set " + variable
                    + " or activate a profile that defines it.");
        }
        if (value.equals(placeholder)) {
            throw new IllegalStateException(property + " is still application.yml's placeholder (" + placeholder
                    + "), so no real target was named. Set " + variable
                    + " or activate a profile that defines it.");
        }
    }

    /**
     * Guard 3. A run that discovers nothing, executes nothing and reports success is the
     * worst outcome this runner can have. The directory ships empty, so this is the most
     * likely thing to happen on a first attempt.
     */
    static void requireScenarios(List<String> scenarioFiles) {
        if (scenarioFiles.isEmpty()) {
            throw new IllegalStateException("No .yaml scenarios found in src/test/resources/" + SCENARIO_DIR
                    + ". Add a scenario there; see " + SCENARIO_DIR + "/README.md for what belongs in it.");
        }
    }
}
