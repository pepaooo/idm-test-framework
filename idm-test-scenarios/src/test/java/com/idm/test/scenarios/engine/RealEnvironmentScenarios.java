package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.connector.ldap.config.LdapProperties;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.core.config.TestExecutorProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.data.TestDataGenerator;
import com.idm.test.wiring.EngineFactory;
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
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
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
import java.util.Optional;
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

    static final String DEFAULT_SCENARIO_DIR = "scenarios-real";
    static final String EMBEDDED_PROFILE = "test";
    static final String LDAP_URL_PLACEHOLDER = "ldap://localhost:389";
    static final String SOAP_URL_PLACEHOLDER = "https://idm.company.com:8543/IDMProv/role/service";

    /** application.yml's fallback LDAP bind DN and password. Pinned by RealEnvironmentGuardsTest. */
    static final String LDAP_USER_DN_PLACEHOLDER = "cn=admin,dc=company,dc=com";
    static final String LDAP_PASSWORD_PLACEHOLDER = "secret";

    /**
     * JDBC URL prefixes of databases that live only inside the test JVM. Nothing another system
     * provisioned can be in them, so a real run refuses them. File-based and networked embedded
     * databases are left alone: someone may run one on purpose.
     */
    static final List<String> IN_MEMORY_JDBC_PREFIXES = List.of("jdbc:h2:mem:", "jdbc:hsqldb:mem:", "jdbc:derby:memory:");

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

    /** application.yml's fallback URL and credentials per database. Pinned by RealEnvironmentGuardsTest. */
    record DatabasePlaceholder(String jdbcUrl, String username, String password) {
    }

    static final Map<String, DatabasePlaceholder> DATABASE_PLACEHOLDERS = Map.of(
            "sap", new DatabasePlaceholder("jdbc:postgresql://localhost:5432/sap", "sapuser", "sappass"),
            "oracle-hr", new DatabasePlaceholder("jdbc:oracle:thin:@localhost:1521:HRDB", "hruser", "hrpass"),
            "workday", new DatabasePlaceholder("jdbc:postgresql://localhost:5432/workday", "wdayuser", "wdaypass"));

    private static final String REGISTRATION = "spring.security.oauth2.client.registration.";

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
        requireNotEmbedded(environment, locale);
        String scenarioDir = scenarioDirectory();
        requireScenarios(ScenarioSupport.scenarioFiles(scenarioDir), scenarioDir, locale);
        // After guard 3, so an empty directory gets its README message and only a pattern
        // that matches nothing gets the filter's message.
        List<String> selected = ScenarioSupport.selectedScenarioFiles(scenarioDir);
        // Everything below checks only what the selected scenarios use.
        ScenarioNeeds needs = ScenarioNeeds.of(loadForGuards(scenarioDir, selected));
        requireEndpoints(environment, needs, locale);
        requireRestTargets(needs.restTargets(), restProperties, locale);
        requireDatabases(needs.databases(), databaseProperties, locale);
        requireCredentials(needs.restTargets(), needs.soap(), restProperties, soapProperties, environment, locale);

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

    /** Guards 1 and 2 together, as the guard tests use them. */
    static void requireRealTarget(Environment environment, ScenarioNeeds needs) {
        requireRealTarget(environment, needs, Locale.ENGLISH);
    }

    static void requireRealTarget(Environment environment, ScenarioNeeds needs, Locale locale) {
        requireNotEmbedded(environment, locale);
        requireEndpoints(environment, needs, locale);
    }

    /** Guard 1. The embedded test configuration is never a real target. */
    static void requireNotEmbedded(Environment environment) {
        requireNotEmbedded(environment, Locale.ENGLISH);
    }

    /** Guard 1, its message in {@code locale}, as every guard below. */
    static void requireNotEmbedded(Environment environment, Locale locale) {
        if (Arrays.asList(environment.getActiveProfiles()).contains(EMBEDDED_PROFILE)) {
            throw refusal(locale, Message.of("runner.guard.embedded",
                    Arrays.toString(environment.getActiveProfiles()), EMBEDDED_PROFILE));
        }
    }

    /**
     * Guard 2. Refuses an LDAP or SOAP endpoint that the selected scenarios use but nobody
     * named: the LDAP URL when ldap.* is used, the Role service URL when roles.* is, and a
     * Resource service URL when resources.* is. application.yml has no placeholder for the
     * resource URL, so there it is only required to be set. The LDAP part is per directory (see
     * {@link #requireDirectories}). Endpoints no scenario uses are not checked.
     */
    static void requireEndpoints(Environment environment, ScenarioNeeds needs) {
        requireEndpoints(environment, needs, Locale.ENGLISH);
    }

    static void requireEndpoints(Environment environment, ScenarioNeeds needs, Locale locale) {
        if (needs.ldap()) {
            requireDirectories(environment, needs.directories(), locale);
        }
        if (needs.roles()) {
            requireNamed(environment, "idm.test.soap.url", "IDM_TEST_SOAP_URL", SOAP_URL_PLACEHOLDER, locale);
        }
        if (needs.resources()) {
            requireNamed(environment, "idm.test.soap.resource-url", "IDM_TEST_SOAP_RESOURCEURL", null, locale);
        }
    }

    /**
     * Guard 2 for LDAP, per directory the selected scenarios use. The directory named
     * {@code default} that the older {@code idm.test.ldap} block configures keeps its original
     * checks; a named directory ({@code idm.test.directories.<name>}) must have a url that is not
     * the placeholder, and neither the placeholder bind DN nor password. A step without
     * {@code directory:} is checked against the default directory. Directories no scenario uses
     * are not checked.
     */
    static void requireDirectories(Environment environment, Set<String> used) {
        requireDirectories(environment, used, Locale.ENGLISH);
    }

    static void requireDirectories(Environment environment, Set<String> used, Locale locale) {
        Map<String, LdapProperties> named = Binder.get(environment)
                .bind("idm.test.directories", Bindable.mapOf(String.class, LdapProperties.class))
                .orElse(Map.of());
        String legacyUrl = environment.getProperty("idm.test.ldap.url");
        Set<String> names = new TreeSet<>(named.keySet());
        // With no named directories, the old block is the only candidate, and its own checks
        // explain an unset URL better than "not configured" would.
        if (named.isEmpty() || (legacyUrl != null && !legacyUrl.isBlank())) {
            names.add(LdapDirectories.DEFAULT);
        }
        Optional<String> defaultName =
                LdapDirectories.resolveDefault(environment.getProperty("idm.test.default-directory"), names);

        for (String requested : new TreeSet<>(used)) {
            String name = requested;
            if (ScenarioNeeds.DEFAULT_DIRECTORY.equals(requested)) {
                name = defaultName.orElseThrow(() -> refusal(locale, Message.of("runner.guard.noDefaultDirectory", names)));
            }
            if (!names.contains(name)) {
                throw refusal(locale, Message.of("runner.guard.unknownDirectory", name, names));
            }
            if (LdapDirectories.DEFAULT.equals(name) && !named.containsKey(LdapDirectories.DEFAULT)) {
                try {
                    requireLegacyDirectory(environment, locale);
                } catch (IllegalStateException e) {
                    String configuredDefault = environment.getProperty("idm.test.default-directory");
                    boolean fellBackToOldBlock = ScenarioNeeds.DEFAULT_DIRECTORY.equals(requested)
                            && !named.isEmpty()
                            && (configuredDefault == null || configuredDefault.isBlank());
                    if (!fellBackToOldBlock) {
                        throw e;
                    }
                    Object reason = e instanceof Refusal inner ? inner.detail : e.getMessage();
                    throw refusal(locale, Message.of("runner.guard.legacyFallback", reason,
                            new TreeSet<>(named.keySet())), e);
                }
            } else {
                requireNamedDirectory(name, named.get(name), locale);
            }
        }
    }

    /** The older idm.test.ldap block's checks, unchanged. */
    private static void requireLegacyDirectory(Environment environment, Locale locale) {
        requireNamed(environment, "idm.test.ldap.url", "IDM_TEST_LDAP_URL", LDAP_URL_PLACEHOLDER, locale);
        // A blank bind DN or password is anonymous bind, which is legitimate; only the
        // placeholders are refused. The dev profile overrides the DN but not the password.
        if (LDAP_USER_DN_PLACEHOLDER.equals(environment.getProperty("idm.test.ldap.user-dn"))) {
            refusePlaceholder("idm.test.ldap.user-dn", locale);
        }
        if (LDAP_PASSWORD_PLACEHOLDER.equals(environment.getProperty("idm.test.ldap.password"))) {
            refusePlaceholder("idm.test.ldap.password", locale);
        }
    }

    private static void requireNamedDirectory(String name, LdapProperties directory, Locale locale) {
        String prefix = "idm.test.directories." + name + ".";
        requireNamedValue(prefix + "url", directory.getUrl(),
                "IDM_TEST_DIRECTORIES_" + name.toUpperCase(Locale.ROOT).replace("-", "") + "_URL",
                LDAP_URL_PLACEHOLDER, locale);
        if (LDAP_USER_DN_PLACEHOLDER.equals(directory.getUserDn())) {
            refusePlaceholder(prefix + "user-dn", locale);
        }
        if (LDAP_PASSWORD_PLACEHOLDER.equals(directory.getPassword())) {
            refusePlaceholder(prefix + "password", locale);
        }
    }

    private static void requireNamed(Environment environment, String property, String variable, String placeholder,
                                     Locale locale) {
        requireNamedValue(property, environment.getProperty(property), variable, placeholder, locale);
    }

    private static void requireNamedValue(String property, String value, String variable, String placeholder,
                                          Locale locale) {
        if (value == null || value.isBlank()) {
            throw refusal(locale, Message.of("runner.guard.notSet", property, variable));
        }
        if (value.equals(placeholder)) {
            throw refusal(locale, Message.of("runner.guard.placeholder", property, placeholder, variable));
        }
    }

    /**
     * Guard 3. A run that discovers nothing, executes nothing and reports success is the
     * worst outcome this runner can have. The directory ships empty, so this is the most
     * likely thing to happen on a first attempt.
     */
    static void requireScenarios(List<String> scenarioFiles, String scenarioDir) {
        requireScenarios(scenarioFiles, scenarioDir, Locale.ENGLISH);
    }

    static void requireScenarios(List<String> scenarioFiles, String scenarioDir, Locale locale) {
        if (scenarioFiles.isEmpty()) {
            throw refusal(locale, Message.of("runner.guard.noScenarios", scenarioDir, DEFAULT_SCENARIO_DIR));
        }
    }

    /**
     * Guard 4. Refuses a REST target the run is about to use when it is not configured, its
     * base-url is blank, or it is still application.yml's placeholder. Only targets the
     * selected scenarios use are checked, so a run with no REST steps makes no REST demand.
     * Checked in sorted order, so the message is deterministic.
     */
    static void requireRestTargets(Set<String> targets, RestProperties properties) {
        requireRestTargets(targets, properties, Locale.ENGLISH);
    }

    static void requireRestTargets(Set<String> targets, RestProperties properties, Locale locale) {
        for (String target : new TreeSet<>(targets)) {
            RestProperties.RestApiConfig config = properties.getRest().get(target);
            if (config == null) {
                throw refusal(locale, Message.of("runner.guard.unknownRestTarget", target,
                        new TreeSet<>(properties.getRest().keySet())));
            }
            requireNamedValue("idm.test.rest." + target + ".base-url", config.getBaseUrl(),
                    "IDM_TEST_REST_" + target.toUpperCase(Locale.ROOT).replace("-", "") + "_BASEURL",
                    REST_URL_PLACEHOLDERS.get(target), locale);
        }
    }

    /** The REST targets the given scenarios use, in {@code steps} or {@code cleanup}. Delegates to {@link ScenarioNeeds}. */
    static Set<String> restTargets(List<Scenario> scenarios) {
        return ScenarioNeeds.of(scenarios).restTargets();
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
     * Guard DB. For each database the selected scenarios use, refuses one that is not
     * configured, one whose jdbc-url is an in-memory database, and one whose jdbc-url, username
     * or password is still application.yml's placeholder. Databases no scenario uses are not
     * checked, and are never contacted, because their pools open on first use. Messages name
     * properties and variables, never a value.
     */
    static void requireDatabases(Set<String> databases, DatabaseProperties properties) {
        requireDatabases(databases, properties, Locale.ENGLISH);
    }

    static void requireDatabases(Set<String> databases, DatabaseProperties properties, Locale locale) {
        for (String name : new TreeSet<>(databases)) {
            DatabaseProperties.DatabaseConfig config = properties.getDatabases().get(name);
            if (config == null) {
                throw refusal(locale, Message.of("runner.guard.unknownDatabase", name,
                        new TreeSet<>(properties.getDatabases().keySet())));
            }
            String prefix = "idm.test.databases." + name + ".";
            DatabasePlaceholder placeholder = DATABASE_PLACEHOLDERS.get(name);
            requireNamedValue(prefix + "jdbc-url", config.getJdbcUrl(), variableFor(prefix + "jdbc-url"),
                    placeholder == null ? null : placeholder.jdbcUrl(), locale);
            String jdbcUrl = config.getJdbcUrl().toLowerCase(Locale.ROOT);
            for (String inMemory : IN_MEMORY_JDBC_PREFIXES) {
                if (jdbcUrl.startsWith(inMemory)) {
                    throw refusal(locale, Message.of("runner.guard.inMemory", prefix + "jdbc-url", inMemory,
                            variableFor(prefix + "jdbc-url")));
                }
            }
            if (placeholder != null && placeholder.username().equals(config.getUsername())) {
                refusePlaceholder(prefix + "username", locale);
            }
            if (placeholder != null && placeholder.password().equals(config.getPassword())) {
                refusePlaceholder(prefix + "password", locale);
            }
        }
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
        requireCredentials(restTargets, usesSoap, rest, soap, environment, Locale.ENGLISH);
    }

    static void requireCredentials(Set<String> restTargets, boolean usesSoap, RestProperties rest,
                                   SoapProperties soap, Environment environment, Locale locale) {
        for (String target : new TreeSet<>(restTargets)) {
            RestProperties.RestApiConfig config = rest.getRest().get(target);
            if (config == null) {
                continue;
            }
            String prefix = "idm.test.rest." + target + ".";
            if (config.isUseOAuth()) {
                requireOAuthClient(target, config.getOauthClientName(), environment, locale);
            } else {
                requirePair(prefix + "username", config.getUsername(), prefix + "password", config.getPassword(),
                        REST_CREDENTIAL_PLACEHOLDERS.get(target), locale);
            }
        }
        if (usesSoap) {
            requirePair("idm.test.soap.username", soap.getUsername(), "idm.test.soap.password", soap.getPassword(),
                    SOAP_CREDENTIAL_PLACEHOLDER, locale);
        }
    }

    /** Guard 5's entry point: loads {@code files} and checks the credentials they need. */
    static void requireCredentialsIn(String scenarioDir, List<String> files, RestProperties rest,
                                     SoapProperties soap, Environment environment) {
        ScenarioNeeds needs = ScenarioNeeds.of(loadForGuards(scenarioDir, files));
        requireCredentials(needs.restTargets(), needs.soap(), rest, soap, environment);
    }

    /** Whether any scenario has a roles.* or resources.* step, in steps or cleanup. Delegates to {@link ScenarioNeeds}. */
    static boolean usesSoap(List<Scenario> scenarios) {
        return ScenarioNeeds.of(scenarios).soap();
    }

    /**
     * The environment variable that sets {@code property} in every profile, by Spring's relaxed
     * binding: upper case, dashes removed, dots to underscores.
     */
    static String variableFor(String property) {
        return property.toUpperCase(Locale.ROOT).replace("-", "").replace('.', '_');
    }

    private static void requirePair(String userProperty, String user, String passwordProperty, String password,
                                    Credentials placeholder, Locale locale) {
        boolean hasUser = user != null && !user.isBlank();
        boolean hasPassword = password != null && !password.isBlank();
        if (hasUser != hasPassword) {
            String present = hasUser ? userProperty : passwordProperty;
            String missing = hasUser ? passwordProperty : userProperty;
            throw refusal(locale, Message.of("runner.guard.halfPair", present, missing, variableFor(missing)));
        }
        if (placeholder == null) {
            return;
        }
        if (hasUser && user.equals(placeholder.username())) {
            refusePlaceholder(userProperty, locale);
        }
        if (hasPassword && password.equals(placeholder.password())) {
            refusePlaceholder(passwordProperty, locale);
        }
    }

    private static void requireOAuthClient(String target, String name, Environment environment, Locale locale) {
        String nameProperty = "idm.test.rest." + target + ".oauth-client-name";
        if (name == null || name.isBlank()) {
            throw refusal(locale, Message.of("runner.guard.oauthNoClientName", target, nameProperty,
                    variableFor(nameProperty), REGISTRATION));
        }
        String idProperty = REGISTRATION + name + ".client-id";
        String secretProperty = REGISTRATION + name + ".client-secret";
        String clientId = environment.getProperty(idProperty);
        String secret = environment.getProperty(secretProperty);
        if (clientId == null || clientId.isBlank()) {
            throw refusal(locale, Message.of("runner.guard.oauthNoClientId", target, name, idProperty,
                    variableFor(idProperty)));
        }
        if (secret == null || secret.isBlank()) {
            throw refusal(locale, Message.of("runner.guard.oauthNoSecret", target, name, secretProperty,
                    variableFor(secretProperty)));
        }
        Credentials placeholder = OAUTH_CLIENT_PLACEHOLDERS.get(name);
        if (placeholder != null && clientId.equals(placeholder.username())) {
            refusePlaceholder(idProperty, locale);
        }
        if (placeholder != null && secret.equals(placeholder.password())) {
            refusePlaceholder(secretProperty, locale);
        }
    }

    private static void refusePlaceholder(String property, Locale locale) {
        throw refusal(locale, Message.of("runner.guard.placeholderCredential", property, variableFor(property)));
    }

    /** A guard's refusal, its message rendered in the run's language. Names properties, never values. */
    private static Refusal refusal(Locale locale, Message detail) {
        return new Refusal(detail, locale, null);
    }

    private static Refusal refusal(Locale locale, Message detail, Throwable cause) {
        return new Refusal(detail, locale, cause);
    }

    /** An {@link IllegalStateException} that keeps its message by key, so another refusal can quote it. */
    private static final class Refusal extends IllegalStateException {

        private final transient Message detail;

        private Refusal(Message detail, Locale locale, Throwable cause) {
            super(Messages.render(detail, locale), cause);
            this.detail = detail;
        }
    }

    /**
     * Loads the selected files to compute {@link ScenarioNeeds} and drive guards 2, 4, 5 and the
     * database guard, so each checks only what the selected scenarios use. A file that fails to
     * load is skipped here on purpose: {@code buildTests} turns it into its own failing test
     * carrying the real error, and reporting it twice would only add noise.
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
