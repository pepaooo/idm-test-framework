package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.core.config.TestExecutorProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * The five guards below run before any scenario. A connection error is loud on its own. The
 * failure they prevent is the quiet one: a run against the embedded configuration, a
 * placeholder, or an empty directory that reports green.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = {LdapAutoConfiguration.class})
@DisplayName("Real-environment scenarios")
public class RealEnvironmentScenarios {

    static final String DEFAULT_SCENARIO_DIR = "scenarios-real";
    static final String EMBEDDED_PROFILE = "test";
    static final String LDAP_URL_PLACEHOLDER = "ldap://localhost:389";
    static final String SOAP_URL_PLACEHOLDER = "https://idm.company.com:8543/IDMProv/role/service";

    /** application.yml's fallback base-url for each REST target. Pinned to that file by RealEnvironmentGuardsTest. */
    static final Map<String, String> REST_URL_PLACEHOLDERS = Map.of(
            "idm", "https://idm.company.com:8543/IDMProv",
            "sap", "https://sap.company.com/api/v1",
            "workday", "https://workday.company.com/api/v1",
            "servicenow", "https://instance.service-now.com/api");

    /** A username/password pair, or an OAuth client id/secret pair. */
    record Credentials(String username, String password) {
    }

    /** application.yml's fallback credentials per REST target. Pinned to that file by RealEnvironmentGuardsTest. */
    static final Map<String, Credentials> REST_CREDENTIAL_PLACEHOLDERS = Map.of(
            "idm", new Credentials("idmadmin", "idmpass"));

    /** application.yml's fallback SOAP credentials. Pinned to that file by RealEnvironmentGuardsTest. */
    static final Credentials SOAP_CREDENTIAL_PLACEHOLDER = new Credentials("idmadmin", "idmpass");

    /** application.yml's fallback client id and secret per OAuth2 registration. Pinned by RealEnvironmentGuardsTest. */
    static final Map<String, Credentials> OAUTH_CLIENT_PLACEHOLDERS = Map.of(
            "sap", new Credentials("sap-test-client", "sap-test-secret"),
            "workday", new Credentials("workday-client", "workday-secret"),
            "servicenow", new Credentials("snow-client", "snow-secret"));

    private static final String REGISTRATION = "spring.security.oauth2.client.registration.";

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
    private RestAuthenticator restAuthenticator;

    @Autowired
    private SoapProperties soapProperties;

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
        String scenarioDir = scenarioDirectory();
        requireScenarios(YamlScenarioTest.scenarioFiles(scenarioDir), scenarioDir);
        // After guard 3, so an empty directory gets its README message and only a pattern
        // that matches nothing gets the filter's message.
        List<String> selected = YamlScenarioTest.selectedScenarioFiles(scenarioDir);
        requireRestTargetsIn(scenarioDir, selected, restProperties);
        requireCredentialsIn(scenarioDir, selected, restProperties, soapProperties, environment);

        Map<String, StepType> registry = YamlScenarioTest.registry(
                edirectoryConnector, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioValidator validator = new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS);
        ScenarioEngine engine = new ScenarioEngine(
                registry,
                new FixtureFactory(testDataGenerator),
                testExecutor,
                executorProperties.getDefaultTimeout(),
                executorProperties.getDefaultPollInterval(),
                new ExpressionEvaluator());

        return YamlScenarioTest.buildTests(scenarioDir, selected, validator, engine, RESULTS).stream();
    }

    /**
     * The classpath directory this runner reads scenarios from. {@code ContainerScenarios}
     * overrides it to run the embedded corpus against containers; configuration still comes
     * only from the environment, exactly as for a real target.
     */
    String scenarioDirectory() {
        return DEFAULT_SCENARIO_DIR;
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
        requireNamedValue(property, environment.getProperty(property), variable, placeholder);
    }

    private static void requireNamedValue(String property, String value, String variable, String placeholder) {
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
    static void requireScenarios(List<String> scenarioFiles, String scenarioDir) {
        if (scenarioFiles.isEmpty()) {
            throw new IllegalStateException("No .yaml scenarios found in src/test/resources/" + scenarioDir
                    + ". Add a scenario there; see " + DEFAULT_SCENARIO_DIR + "/README.md for what belongs in it.");
        }
    }

    /**
     * Guard 4. Refuses a REST target the run is about to use when it is not configured, its
     * base-url is blank, or it is still application.yml's placeholder. Only targets the
     * selected scenarios use are checked, so a run with no REST steps makes no REST demand.
     * Checked in sorted order, so the message is deterministic.
     */
    static void requireRestTargets(Set<String> targets, RestProperties properties) {
        for (String target : new TreeSet<>(targets)) {
            RestProperties.RestApiConfig config = properties.getRest().get(target);
            if (config == null) {
                throw new IllegalStateException("Scenarios use REST target '" + target
                        + "', which is not configured. Configured targets: "
                        + new TreeSet<>(properties.getRest().keySet()));
            }
            requireNamedValue("idm.test.rest." + target + ".base-url", config.getBaseUrl(),
                    "IDM_TEST_REST_" + target.toUpperCase(Locale.ROOT).replace("-", "") + "_BASEURL",
                    REST_URL_PLACEHOLDERS.get(target));
        }
    }

    /**
     * The literal {@code target} of every {@code rest.*} or {@code http.*} step in a scenario's
     * {@code steps} or {@code cleanup}, sorted. A target containing {@code ${} is only known at
     * run time, so it is skipped, as is a {@code null} target. Any other non-null value is
     * converted with {@code String.valueOf}, matching {@code RestRequestStep}/{@code
     * RestVerifyStep}/{@code HttpRequestStep}, so a non-string target such as {@code target: 1}
     * is still caught here instead of only failing at run time.
     */
    static Set<String> restTargets(List<Scenario> scenarios) {
        Set<String> targets = new TreeSet<>();
        for (Scenario scenario : scenarios) {
            List<StepSpec> entries = new ArrayList<>(scenario.steps());
            entries.addAll(scenario.cleanup());
            for (StepSpec step : entries) {
                if (!step.type().startsWith("rest.") && !step.type().startsWith("http.")) {
                    continue;
                }
                Object target = step.params().get("target");
                if (target == null) {
                    continue;
                }
                String value = String.valueOf(target);
                if (!value.contains("${")) {
                    targets.add(value);
                }
            }
        }
        return targets;
    }

    /**
     * Guard 4's entry point: loads {@code files} from {@code scenarioDir} and applies
     * {@link #requireRestTargets(Set, RestProperties)} to the REST targets they use.
     * Package-private and static so the file-to-guard wiring itself is testable without
     * building the rest of {@link #scenarios()}.
     */
    static void requireRestTargetsIn(String scenarioDir, List<String> files, RestProperties properties) {
        requireRestTargets(restTargets(loadForGuards(scenarioDir, files)), properties);
    }

    /**
     * Guard 5. Refuses credentials the run is about to use when they are still application.yml's
     * placeholders or only half given: for each REST target the selected scenarios use, and for
     * SOAP when they use roles.* or resources.*. An unauthenticated target (nothing set) passes.
     * Messages name the property and the variable that sets it in every profile, never a value.
     * Runs after guard 4, so every target here is configured.
     */
    static void requireCredentials(Set<String> restTargets, boolean usesSoap, RestProperties rest,
                                   SoapProperties soap, Environment environment) {
        for (String target : new TreeSet<>(restTargets)) {
            RestProperties.RestApiConfig config = rest.getRest().get(target);
            if (config == null) {
                continue;
            }
            String prefix = "idm.test.rest." + target + ".";
            if (config.isUseOAuth()) {
                requireOAuthClient(target, config.getOauthClientName(), environment);
            } else {
                requirePair(prefix + "username", config.getUsername(), prefix + "password", config.getPassword(),
                        REST_CREDENTIAL_PLACEHOLDERS.get(target));
            }
        }
        if (usesSoap) {
            requirePair("idm.test.soap.username", soap.getUsername(), "idm.test.soap.password", soap.getPassword(),
                    SOAP_CREDENTIAL_PLACEHOLDER);
        }
    }

    /** Guard 5's entry point: loads {@code files} and checks the credentials they need. */
    static void requireCredentialsIn(String scenarioDir, List<String> files, RestProperties rest,
                                     SoapProperties soap, Environment environment) {
        List<Scenario> scenarios = loadForGuards(scenarioDir, files);
        requireCredentials(restTargets(scenarios), usesSoap(scenarios), rest, soap, environment);
    }

    /** Whether any scenario has a roles.* or resources.* step, in steps or cleanup. */
    static boolean usesSoap(List<Scenario> scenarios) {
        for (Scenario scenario : scenarios) {
            List<StepSpec> entries = new ArrayList<>(scenario.steps());
            entries.addAll(scenario.cleanup());
            for (StepSpec step : entries) {
                if (step.type().startsWith("roles.") || step.type().startsWith("resources.")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The environment variable that sets {@code property} in every profile, by Spring's relaxed
     * binding: upper case, dashes removed, dots to underscores.
     */
    static String variableFor(String property) {
        return property.toUpperCase(Locale.ROOT).replace("-", "").replace('.', '_');
    }

    private static void requirePair(String userProperty, String user, String passwordProperty, String password,
                                    Credentials placeholder) {
        boolean hasUser = user != null && !user.isBlank();
        boolean hasPassword = password != null && !password.isBlank();
        if (hasUser != hasPassword) {
            String present = hasUser ? userProperty : passwordProperty;
            String missing = hasUser ? passwordProperty : userProperty;
            throw new IllegalStateException(present + " is set but " + missing + " is not. Set "
                    + variableFor(missing) + " as well, or give neither for an unauthenticated target.");
        }
        if (placeholder == null) {
            return;
        }
        if (hasUser && user.equals(placeholder.username())) {
            refusePlaceholder(userProperty);
        }
        if (hasPassword && password.equals(placeholder.password())) {
            refusePlaceholder(passwordProperty);
        }
    }

    private static void requireOAuthClient(String target, String name, Environment environment) {
        String nameProperty = "idm.test.rest." + target + ".oauth-client-name";
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("idm.test.rest." + target + ".use-oauth is true but " + nameProperty
                    + " is not set. Set " + variableFor(nameProperty) + " to a " + REGISTRATION + "<name> entry.");
        }
        String idProperty = REGISTRATION + name + ".client-id";
        String secretProperty = REGISTRATION + name + ".client-secret";
        String clientId = environment.getProperty(idProperty);
        String secret = environment.getProperty(secretProperty);
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("REST target '" + target + "' uses OAuth2 registration '" + name
                    + "', which is not configured: " + idProperty + " is not set. Set " + variableFor(idProperty) + ".");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("REST target '" + target + "' uses OAuth2 registration '" + name
                    + "', but " + secretProperty + " is not set. Set " + variableFor(secretProperty) + ".");
        }
        Credentials placeholder = OAUTH_CLIENT_PLACEHOLDERS.get(name);
        if (placeholder != null && clientId.equals(placeholder.username())) {
            refusePlaceholder(idProperty);
        }
        if (placeholder != null && secret.equals(placeholder.password())) {
            refusePlaceholder(secretProperty);
        }
    }

    private static void refusePlaceholder(String property) {
        throw new IllegalStateException(property + " is still application.yml's placeholder, so no real credential"
                + " was given. Set " + variableFor(property) + ".");
    }

    /**
     * Loads the selected files for guards 4 and 5. A file that fails to load is skipped here on
     * purpose: {@code buildTests} turns it into its own failing test carrying the real
     * error, and reporting it twice would only add noise.
     */
    private static List<Scenario> loadForGuards(String scenarioDir, List<String> files) {
        List<Scenario> scenarios = new ArrayList<>();
        for (String file : files) {
            try (InputStream in = RealEnvironmentScenarios.class.getClassLoader()
                    .getResourceAsStream(scenarioDir + "/" + file)) {
                scenarios.add(ScenarioLoader.load(in, file));
            } catch (RuntimeException | IOException e) {
                // Reported by buildTests; see above.
            }
        }
        return scenarios;
    }
}
