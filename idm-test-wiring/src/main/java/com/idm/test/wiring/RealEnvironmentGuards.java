package com.idm.test.wiring;

import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.connector.ldap.config.LdapProperties;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.spi.Message;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The real-environment guards: they stop a run that tests nothing real from reporting green
 * (the embedded configuration, an empty scenario source, a placeholder, a half-given credential).
 * Shared by the JUnit real-environment runners and the end-user runner, over any
 * {@link ScenarioSource}. Guards 2, 4 and 5, and the database guard, check only what the selected
 * scenarios actually use ({@link ScenarioNeeds}). Messages name properties and variables, never
 * a value.
 */
public final class RealEnvironmentGuards {

    private RealEnvironmentGuards() {
    }

    /**
     * Every guard, in order: not the embedded profile, scenarios present, then on the needs of
     * the {@code selected} scenarios: endpoints, REST targets, databases, credentials.
     *
     * @throws RealEnvironmentGuards.Refusal naming the first problem, in {@code locale}
     */
    public static void check(Environment environment, ScenarioSource source, List<String> selected, Locale locale,
                             RestProperties rest, DatabaseProperties databases, SoapProperties soap) {
        requireNotEmbedded(environment, locale);
        List<String> all;
        try {
            all = source.names();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list scenarios in " + source.describe(), e);
        }
        if (source instanceof ClasspathScenarioSource) {
            requireScenarios(all, source.describe(), locale);
        } else if (all.isEmpty()) {
            throw refusal(locale, Message.of("runner.guard.noScenariosIn", source.describe()));
        }
        // Everything below checks only what the selected scenarios use.
        ScenarioNeeds needs = ScenarioNeeds.of(loadForGuards(source, selected));
        requireEndpoints(environment, needs, locale);
        requireRestTargets(needs.restTargets(), rest, locale);
        requireDatabases(needs.databases(), databases, locale);
        requireCredentials(needs.restTargets(), needs.soap(), rest, soap, environment, locale);
    }

    public static final String DEFAULT_SCENARIO_DIR = "scenarios-real";
    public static final String EMBEDDED_PROFILE = "test";
    public static final String LDAP_URL_PLACEHOLDER = "ldap://localhost:389";
    public static final String SOAP_URL_PLACEHOLDER = "https://idm.company.com:8543/IDMProv/role/service";

    /** application.yml's fallback LDAP bind DN and password. Pinned by RealEnvironmentGuardsTest. */
    public static final String LDAP_USER_DN_PLACEHOLDER = "cn=admin,dc=company,dc=com";
    public static final String LDAP_PASSWORD_PLACEHOLDER = "secret";

    /**
     * JDBC URL prefixes of databases that live only inside the test JVM. Nothing another system
     * provisioned can be in them, so a real run refuses them. File-based and networked embedded
     * databases are left alone: someone may run one on purpose.
     */
    public static final List<String> IN_MEMORY_JDBC_PREFIXES = List.of("jdbc:h2:mem:", "jdbc:hsqldb:mem:", "jdbc:derby:memory:");

    /** application.yml's fallback base-url for each REST target. Pinned to that file by RealEnvironmentGuardsTest. */
    public static final Map<String, String> REST_URL_PLACEHOLDERS = Map.of(
            "idm", "https://idm.company.com:8543/IDMProv",
            "sap", "https://sap.company.com/api/v1",
            "workday", "https://workday.company.com/api/v1",
            "servicenow", "https://instance.service-now.com/api");

    /** A username/password pair, or an OAuth client id/secret pair. */
    public record Credentials(String username, String password) {
    }

    /** application.yml's fallback credentials per REST target. Pinned to that file by RealEnvironmentGuardsTest. */
    public static final Map<String, Credentials> REST_CREDENTIAL_PLACEHOLDERS = Map.of(
            "idm", new Credentials("idmadmin", "idmpass"));

    /** application.yml's fallback SOAP credentials. Pinned to that file by RealEnvironmentGuardsTest. */
    public static final Credentials SOAP_CREDENTIAL_PLACEHOLDER = new Credentials("idmadmin", "idmpass");

    /** application.yml's fallback client id and secret per OAuth2 registration. Pinned by RealEnvironmentGuardsTest. */
    public static final Map<String, Credentials> OAUTH_CLIENT_PLACEHOLDERS = Map.of(
            "sap", new Credentials("sap-test-client", "sap-test-secret"),
            "workday", new Credentials("workday-client", "workday-secret"),
            "servicenow", new Credentials("snow-client", "snow-secret"));

    /** application.yml's fallback URL and credentials per database. Pinned by RealEnvironmentGuardsTest. */
    public record DatabasePlaceholder(String jdbcUrl, String username, String password) {
    }

    public static final Map<String, DatabasePlaceholder> DATABASE_PLACEHOLDERS = Map.of(
            "sap", new DatabasePlaceholder("jdbc:postgresql://localhost:5432/sap", "sapuser", "sappass"),
            "oracle-hr", new DatabasePlaceholder("jdbc:oracle:thin:@localhost:1521:HRDB", "hruser", "hrpass"),
            "workday", new DatabasePlaceholder("jdbc:postgresql://localhost:5432/workday", "wdayuser", "wdaypass"));

    private static final String REGISTRATION = "spring.security.oauth2.client.registration.";

    /** Guards 1 and 2 together, as the guard tests use them. */
    public static void requireRealTarget(Environment environment, ScenarioNeeds needs) {
        requireRealTarget(environment, needs, Locale.ENGLISH);
    }

    public static void requireRealTarget(Environment environment, ScenarioNeeds needs, Locale locale) {
        requireNotEmbedded(environment, locale);
        requireEndpoints(environment, needs, locale);
    }

    /** Guard 1. The embedded test configuration is never a real target. */
    public static void requireNotEmbedded(Environment environment) {
        requireNotEmbedded(environment, Locale.ENGLISH);
    }

    /** Guard 1, its message in {@code locale}, as every guard below. */
    public static void requireNotEmbedded(Environment environment, Locale locale) {
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
    public static void requireEndpoints(Environment environment, ScenarioNeeds needs) {
        requireEndpoints(environment, needs, Locale.ENGLISH);
    }

    public static void requireEndpoints(Environment environment, ScenarioNeeds needs, Locale locale) {
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
    public static void requireDirectories(Environment environment, Set<String> used) {
        requireDirectories(environment, used, Locale.ENGLISH);
    }

    public static void requireDirectories(Environment environment, Set<String> used, Locale locale) {
        Map<String, LdapProperties> named = Binder.get(environment)
                .bind("idm.test.directories", Bindable.mapOf(String.class, LdapProperties.class))
                .orElse(Map.of());
        Set<String> names = directoryCandidates(named.keySet(), environment.getProperty("idm.test.ldap.url"));
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
                    Object reason = e instanceof Refusal inner ? inner.detail() : e.getMessage();
                    throw refusal(locale, Message.of("runner.guard.legacyFallback", reason,
                            new TreeSet<>(named.keySet())), e);
                }
            } else {
                requireNamedDirectory(name, named.get(name), locale);
            }
        }
    }

    /**
     * The directories a run can use, sorted: the named ones ({@code idm.test.directories.<name>}), plus
     * {@value LdapDirectories#DEFAULT} (the older {@code idm.test.ldap} block) when none is named or
     * {@code legacyUrl} is set. With no named directories, the old block is the only candidate, and its
     * own checks explain an unset URL better than "not configured" would. The lab panel resolves the
     * default directory from the same set, so it asks for exactly what a run would refuse.
     */
    public static Set<String> directoryCandidates(Set<String> named, String legacyUrl) {
        Set<String> names = new TreeSet<>(named);
        if (named.isEmpty() || (legacyUrl != null && !legacyUrl.isBlank())) {
            names.add(LdapDirectories.DEFAULT);
        }
        return names;
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
    public static void requireScenarios(List<String> scenarioFiles, String scenarioDir) {
        requireScenarios(scenarioFiles, scenarioDir, Locale.ENGLISH);
    }

    public static void requireScenarios(List<String> scenarioFiles, String scenarioDir, Locale locale) {
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
    public static void requireRestTargets(Set<String> targets, RestProperties properties) {
        requireRestTargets(targets, properties, Locale.ENGLISH);
    }

    public static void requireRestTargets(Set<String> targets, RestProperties properties, Locale locale) {
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
    public static Set<String> restTargets(List<Scenario> scenarios) {
        return ScenarioNeeds.of(scenarios).restTargets();
    }

    /**
     * Guard 4's entry point: loads {@code files} from {@code source} and applies
     * {@link #requireRestTargets(Set, RestProperties)} to the REST targets they use.
     * Static so the file-to-guard wiring itself is testable without a Spring context.
     */
    public static void requireRestTargetsIn(ScenarioSource source, List<String> files, RestProperties properties) {
        requireRestTargets(restTargets(loadForGuards(source, files)), properties);
    }

    /**
     * Guard DB. For each database the selected scenarios use, refuses one that is not
     * configured, one whose jdbc-url is an in-memory database, and one whose jdbc-url, username
     * or password is still application.yml's placeholder. Databases no scenario uses are not
     * checked, and are never contacted, because their pools open on first use. Messages name
     * properties and variables, never a value.
     */
    public static void requireDatabases(Set<String> databases, DatabaseProperties properties) {
        requireDatabases(databases, properties, Locale.ENGLISH);
    }

    public static void requireDatabases(Set<String> databases, DatabaseProperties properties, Locale locale) {
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
    public static void requireCredentials(Set<String> restTargets, boolean usesSoap, RestProperties rest,
                                   SoapProperties soap, Environment environment) {
        requireCredentials(restTargets, usesSoap, rest, soap, environment, Locale.ENGLISH);
    }

    public static void requireCredentials(Set<String> restTargets, boolean usesSoap, RestProperties rest,
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
    public static void requireCredentialsIn(ScenarioSource source, List<String> files, RestProperties rest,
                                     SoapProperties soap, Environment environment) {
        ScenarioNeeds needs = ScenarioNeeds.of(loadForGuards(source, files));
        requireCredentials(needs.restTargets(), needs.soap(), rest, soap, environment);
    }

    /** Whether any scenario has a roles.* or resources.* step, in steps or cleanup. Delegates to {@link ScenarioNeeds}. */
    public static boolean usesSoap(List<Scenario> scenarios) {
        return ScenarioNeeds.of(scenarios).soap();
    }

    /**
     * The environment variable that sets {@code property} in every profile, by Spring's relaxed
     * binding: upper case, dashes removed, dots to underscores.
     */
    public static String variableFor(String property) {
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
    public static final class Refusal extends IllegalStateException {

        private final transient Message detail;

        private Refusal(Message detail, Locale locale, Throwable cause) {
            super(Messages.render(detail, locale), cause);
            this.detail = detail;
        }

        /** The refusal's message by key, so another refusal can quote it. */
        public Message detail() {
            return detail;
        }
    }

    /**
     * Loads the selected files to compute {@link ScenarioNeeds} and drive guards 2, 4, 5 and the
     * database guard, so each checks only what the selected scenarios use. A file that fails to
     * load is skipped here on purpose: {@code buildTests} turns it into its own failing test
     * carrying the real error, and reporting it twice would only add noise.
     */
    private static List<Scenario> loadForGuards(ScenarioSource source, List<String> files) {
        List<Scenario> scenarios = new ArrayList<>();
        for (String file : files) {
            try (InputStream in = source.open(file)) {
                if (in != null) {
                    scenarios.add(ScenarioLoader.load(in, file));
                }
            } catch (RuntimeException | IOException e) {
                // Reported by buildTests; see above.
            }
        }
        return scenarios;
    }
}
