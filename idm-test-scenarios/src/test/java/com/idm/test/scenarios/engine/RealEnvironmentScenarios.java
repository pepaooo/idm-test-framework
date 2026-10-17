package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.core.config.TestExecutorProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.data.TestDataGenerator;
import com.idm.test.wiring.ClasspathScenarioSource;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.RealEnvironmentGuards;
import com.idm.test.wiring.ScenarioSource;
import com.idm.test.wiring.StepRegistry;
import com.idm.test.wiring.RunInfos;
import com.idm.test.wiring.ScenarioNeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.ldap.autoconfigure.LdapAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
 * Add {@code -Dscenario=<file or glob>} to run only matching files, as with
 * {@link YamlScenarioTest}.
 * The guards below run before any scenario. A connection error is loud on its own. The
 * failure they prevent is the quiet one: a run against the embedded configuration, a
 * placeholder, or an empty directory that reports green. Guards 2, 4 and 5, and the database
 * guard, check only what the selected scenarios actually use ({@link ScenarioNeeds}):
 * an unused LDAP, SOAP, database or REST repository is never required to be configured, and
 * is never contacted. The one exception is the embedded {@code test-db} (in-memory H2 by
 * default): it is always opened at startup, because the scenarios module's
 * {@code TestSchemaInitializer} recreates its schema there — never point {@code test-db}
 * at a real database.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class, DataSourceAutoConfiguration.class})
@DisplayName("Real-environment scenarios")
public class RealEnvironmentScenarios {

    static final String DEFAULT_SCENARIO_DIR = RealEnvironmentGuards.DEFAULT_SCENARIO_DIR;
    private static final Path RESULTS = Path.of("target", "real-environment-results.json");

    @Autowired
    private Environment environment;

    @Autowired
    private LdapDirectories ldapDirectories;

    @Autowired
    private MultiDatabaseConnector databaseConnector;

    @Autowired
    private ObjectProvider<RoleServiceConnector> roleServiceConnector;

    @Autowired
    private ObjectProvider<ResourceServiceConnector> resourceServiceConnector;

    @Autowired
    private DatabaseProperties databaseProperties;

    @Autowired
    private IdmRestConnector idmRestConnector;

    @Autowired
    private RestProperties restProperties;

    @Autowired
    private RestAuthenticator restAuthenticator;

    @Autowired
    private SoapProperties soapProperties;

    @Autowired
    private TestExecutor testExecutor;

    @Autowired
    private TestExecutorProperties executorProperties;

    @Autowired
    private TestDataGenerator testDataGenerator;

    /** The catalog key of the runner's name in the report; ContainerScenarios overrides it. */
    String runnerName() {
        return "runner.name.realEnvironment";
    }

    @TestFactory
    @DisplayName("Each real-environment scenario file runs as its own test")
    Stream<DynamicTest> scenarios() throws Exception {
        // First, so an unsupported language stops the run before anything else, and every guard
        // below speaks the run's language.
        Locale locale = RunInfos.locale(environment, System.getenv("IDM_TEST_LOCALE"), System.getProperty("idm.test.locale"));
        String scenarioDir = scenarioDirectory();
        ScenarioSource source = new ClasspathScenarioSource(scenarioDir);
        // Guards 1 and 3 ahead of the selection, so an unsupported profile or an empty directory
        // gets its own message and only a pattern that matches nothing gets the filter's;
        // check() repeats them, harmlessly, before the guards that need the selection.
        RealEnvironmentGuards.requireNotEmbedded(environment, locale);
        RealEnvironmentGuards.requireScenarios(source.names(), scenarioDir, locale);
        List<String> selected = ScenarioSupport.selectedScenarioFiles(scenarioDir);
        RealEnvironmentGuards.check(environment, source, selected, locale, restProperties, databaseProperties,
                soapProperties);

        Map<String, StepType> registry = StepRegistry.of(
                ldapDirectories, databaseConnector, roleServiceConnector.getIfAvailable(),
                resourceServiceConnector.getIfAvailable(), idmRestConnector, restProperties, restAuthenticator);
        ScenarioValidator validator = EngineFactory.validator(registry);
        ScenarioEngine engine = EngineFactory.engine(registry, testDataGenerator, testExecutor, executorProperties);

        return ScenarioSupport.buildTests(scenarioDir, selected, validator, engine, RESULTS,
                scenarios -> RunInfos.of(runnerName(), environment, scenarios)).stream();
    }

    /**
     * The classpath directory this runner reads scenarios from. {@code ContainerScenarios}
     * overrides it to run the embedded corpus against containers; configuration still comes
     * only from the environment, exactly as for a real target.
     */
    String scenarioDirectory() {
        return DEFAULT_SCENARIO_DIR;
    }
}
