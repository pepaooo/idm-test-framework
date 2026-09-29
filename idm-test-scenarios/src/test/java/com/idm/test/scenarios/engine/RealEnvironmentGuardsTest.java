package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.model.Scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guards in {@link RealEnvironmentScenarios} exist to stop a run that tests nothing real
 * from reporting green. Each one is checked here without a Spring context, so these run in
 * every build even though the runner itself does not.
 * <p>
 * {@link #testRealTargetPasses()} is the positive control. Without it, every refusal below
 * would still pass if the guards simply refused everything.
 */
@DisplayName("Real-environment runner guards")
class RealEnvironmentGuardsTest {

    private static final String REAL_LDAP = "ldap://edir.example.org:389";
    private static final String REAL_SOAP = "https://idm.example.org:8543/IDMProv/role/service";

    /** What the existing guard-2 tests assumed: LDAP and the Role service are used. */
    private static final ScenarioNeeds LDAP_AND_ROLES = new ScenarioNeeds(Set.of(ScenarioNeeds.DEFAULT_DIRECTORY), Set.of(), true, false, Set.of());
    private static final ScenarioNeeds NOTHING = new ScenarioNeeds(Set.of(), Set.of(), false, false, Set.of());
    private static final ScenarioNeeds LDAP_ONLY = new ScenarioNeeds(Set.of(ScenarioNeeds.DEFAULT_DIRECTORY), Set.of(), false, false, Set.of());

    private static final ScenarioNeeds AD_ONLY = new ScenarioNeeds(Set.of("ad"), Set.of(), false, false, Set.of());
    private static final ScenarioNeeds DEFAULT_ONLY =
            new ScenarioNeeds(Set.of(ScenarioNeeds.DEFAULT_DIRECTORY), Set.of(), false, false, Set.of());

    private static MockEnvironment target(String ldapUrl, String soapUrl, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        if (ldapUrl != null) {
            environment.setProperty("idm.test.ldap.url", ldapUrl);
        }
        if (soapUrl != null) {
            environment.setProperty("idm.test.soap.url", soapUrl);
        }
        return environment;
    }

    private static MockEnvironment ldapTarget(String userDn, String password) {
        MockEnvironment environment = target(REAL_LDAP, null, "dev");
        if (userDn != null) {
            environment.setProperty("idm.test.ldap.user-dn", userDn);
        }
        if (password != null) {
            environment.setProperty("idm.test.ldap.password", password);
        }
        return environment;
    }

    @Test
    @DisplayName("A named, non-embedded target passes")
    void testRealTargetPasses() {
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, REAL_SOAP, "dev"), LDAP_AND_ROLES))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The embedded 'test' profile is refused")
    void testEmbeddedProfileRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, REAL_SOAP, "test"), LDAP_AND_ROLES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'test'")
                .hasMessageContaining("IDM_TEST_PROFILE");
    }

    @Test
    @DisplayName("The embedded 'test' profile is refused even alongside another profile")
    void testEmbeddedProfileRefusedAmongOthers() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, REAL_SOAP, "dev", "test"), LDAP_AND_ROLES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'test'");
    }

    @Test
    @DisplayName("An unset LDAP URL is refused")
    void testUnsetLdapUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(null, REAL_SOAP, "dev"), LDAP_AND_ROLES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.url")
                .hasMessageContaining("IDM_TEST_LDAP_URL");
    }

    @Test
    @DisplayName("A blank LDAP URL is refused")
    void testBlankLdapUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target("  ", REAL_SOAP, "dev"), LDAP_AND_ROLES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.url");
    }

    @Test
    @DisplayName("The LDAP URL placeholder from application.yml is refused")
    void testLdapPlaceholderRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                target(RealEnvironmentScenarios.LDAP_URL_PLACEHOLDER, REAL_SOAP, "dev"), LDAP_AND_ROLES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.url")
                .hasMessageContaining("placeholder")
                .hasMessageContaining("IDM_TEST_LDAP_URL");
    }

    @Test
    @DisplayName("An unset SOAP URL is refused")
    void testUnsetSoapUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, null, "dev"), LDAP_AND_ROLES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.soap.url")
                .hasMessageContaining("IDM_TEST_SOAP_URL");
    }

    @Test
    @DisplayName("The SOAP URL placeholder from application.yml is refused")
    void testSoapPlaceholderRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                target(REAL_LDAP, RealEnvironmentScenarios.SOAP_URL_PLACEHOLDER, "dev"), LDAP_AND_ROLES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.soap.url")
                .hasMessageContaining("placeholder")
                .hasMessageContaining("IDM_TEST_SOAP_URL");
    }

    private static DatabaseProperties databases(String name, String url, String user, String password) {
        DatabaseProperties.DatabaseConfig config = new DatabaseProperties.DatabaseConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        DatabaseProperties properties = new DatabaseProperties();
        properties.setDatabases(Map.of(name, config));
        return properties;
    }

    @Test
    @DisplayName("Guard 2 ignores LDAP and SOAP URLs that no selected scenario uses, even placeholders or unset")
    void testUnusedEndpointsIgnored() {
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(
                target(RealEnvironmentScenarios.LDAP_URL_PLACEHOLDER, null, "dev"), NOTHING))
                .doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(
                target(null, RealEnvironmentScenarios.SOAP_URL_PLACEHOLDER, "dev"),
                new ScenarioNeeds(Set.of(), Set.of(), false, false, Set.of("idm"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Guard 1 still refuses the embedded profile when nothing is used")
    void testEmbeddedRefusedEvenWithNoNeeds() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(null, null, "test"), NOTHING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'test'");
    }

    @Test
    @DisplayName("Guard 2 checks the LDAP URL only when ldap.* is used, and the SOAP URL only when roles.* is")
    void testEndpointsCheckedOnlyWhenUsed() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(null, REAL_SOAP, "dev"),
                new ScenarioNeeds(Set.of(ScenarioNeeds.DEFAULT_DIRECTORY), Set.of(), false, false, Set.of())))
                .hasMessageContaining("IDM_TEST_LDAP_URL");
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, null, "dev"),
                new ScenarioNeeds(Set.of(ScenarioNeeds.DEFAULT_DIRECTORY), Set.of(), false, false, Set.of())))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(null, null, "dev"),
                new ScenarioNeeds(Set.of(), Set.of(), true, false, Set.of())))
                .hasMessageContaining("IDM_TEST_SOAP_URL");
    }

    @Test
    @DisplayName("Guard 2 requires the resource URL only when resources.* is used")
    void testResourceUrlOnlyWhenUsed() {
        ScenarioNeeds resources = new ScenarioNeeds(Set.of(), Set.of(), false, true, Set.of());
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(null, null, "dev"), resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.soap.resource-url")
                .hasMessageContaining("IDM_TEST_SOAP_RESOURCEURL");

        MockEnvironment configured = target(null, null, "dev");
        configured.setProperty("idm.test.soap.resource-url", "https://idm.example.org/IDMProv/resource/service");
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(configured, resources))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The DB guard refuses a used database that is not configured, listing the configured ones")
    void testUnconfiguredDatabaseRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("hr"),
                databases("sap", "jdbc:postgresql://db.example.org/sap", "svc", "real")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'hr'")
                .hasMessageContaining("[sap]");
    }

    @Test
    @DisplayName("The DB guard refuses a blank or placeholder URL and placeholder credentials, never echoing a credential")
    void testDatabasePlaceholdersRefused() {
        RealEnvironmentScenarios.DatabasePlaceholder sap = RealEnvironmentScenarios.DATABASE_PLACEHOLDERS.get("sap");

        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("sap"),
                databases("sap", " ", "svc", "real")))
                .hasMessageContaining("IDM_TEST_DATABASES_SAP_JDBCURL");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("sap"),
                databases("sap", sap.jdbcUrl(), "svc", "real")))
                .hasMessageContaining("idm.test.databases.sap.jdbc-url")
                .hasMessageContaining("placeholder");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("sap"),
                databases("sap", "jdbc:postgresql://db.example.org/sap", sap.username(), "real")))
                .hasMessageContaining("IDM_TEST_DATABASES_SAP_USERNAME");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("sap"),
                databases("sap", "jdbc:postgresql://db.example.org/sap", "svc", sap.password())))
                .hasMessageContaining("IDM_TEST_DATABASES_SAP_PASSWORD")
                .hasMessageNotContaining(sap.password());
    }

    @Test
    @DisplayName("The DB guard passes a real configuration and ignores databases no scenario uses")
    void testDatabaseGuardPasses() {
        DatabaseProperties placeholders = databases("sap",
                RealEnvironmentScenarios.DATABASE_PLACEHOLDERS.get("sap").jdbcUrl(), "sapuser", "sappass");
        assertThatCode(() -> RealEnvironmentScenarios.requireDatabases(Set.of(), placeholders))
                .doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireDatabases(Set.of("sap"),
                databases("sap", "jdbc:postgresql://db.example.org/sap", "svc", "real")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The DB guard refuses each in-memory database a scenario uses, naming the prefix but never the URL")
    void testInMemoryDatabasesRefused() {
        Map<String, String> urls = Map.of(
                "jdbc:h2:mem:x", "jdbc:h2:mem:",
                "JDBC:H2:MEM:x", "jdbc:h2:mem:",
                "jdbc:hsqldb:mem:x", "jdbc:hsqldb:mem:",
                "jdbc:derby:memory:x;create=true", "jdbc:derby:memory:");
        urls.forEach((url, prefix) -> assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(
                Set.of("hr"), databases("hr", url, "svc", "real")))
                .as(url)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("idm.test.databases.hr.jdbc-url points at an in-memory database (" + prefix
                        + "), which exists only inside this test run, so nothing another system provisioned"
                        + " could be checked there. Point it at a real database: set IDM_TEST_DATABASES_HR_JDBCURL."));
    }

    @Test
    @DisplayName("An in-memory URL's own password parameter never reaches the message")
    void testInMemoryUrlNotEchoed() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireDatabases(Set.of("hr"),
                databases("hr", "jdbc:h2:mem:x;PASSWORD=hunter2", "svc", "real")))
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("jdbc:h2:mem:x");
    }

    @Test
    @DisplayName("File-based, networked and real database URLs pass; an unused in-memory database passes")
    void testNonMemoryDatabasesPass() {
        for (String url : List.of("jdbc:h2:file:/tmp/x", "jdbc:h2:tcp://db.example.org/x",
                "jdbc:postgresql://db.example.org:5432/hr")) {
            assertThatCode(() -> RealEnvironmentScenarios.requireDatabases(Set.of("hr"),
                    databases("hr", url, "svc", "real"))).as(url).doesNotThrowAnyException();
        }
        assertThatCode(() -> RealEnvironmentScenarios.requireDatabases(Set.of(),
                databases("test-db", "jdbc:h2:mem:testdb", "sa", ""))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("When ldap.* is used, the placeholder bind DN and password are refused, never echoing the password")
    void testLdapBindPlaceholdersRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                ldapTarget(RealEnvironmentScenarios.LDAP_USER_DN_PLACEHOLDER, "real-pass"), LDAP_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.user-dn")
                .hasMessageContaining("IDM_TEST_LDAP_USERDN");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                ldapTarget("cn=svc,dc=example,dc=org", RealEnvironmentScenarios.LDAP_PASSWORD_PLACEHOLDER), LDAP_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.password")
                .hasMessageContaining("IDM_TEST_LDAP_PASSWORD")
                .hasMessageNotContaining(RealEnvironmentScenarios.LDAP_PASSWORD_PLACEHOLDER);
    }

    @Test
    @DisplayName("LDAP bind placeholders pass when ldap.* is not used; blank or unset ones pass when it is")
    void testLdapBindChecksConditional() {
        MockEnvironment placeholders = ldapTarget(RealEnvironmentScenarios.LDAP_USER_DN_PLACEHOLDER,
                RealEnvironmentScenarios.LDAP_PASSWORD_PLACEHOLDER);
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(placeholders, NOTHING))
                .doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(ldapTarget(null, null), LDAP_ONLY))
                .doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(ldapTarget("", ""), LDAP_ONLY))
                .doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(
                ldapTarget("cn=svc,dc=example,dc=org", "real-pass"), LDAP_ONLY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The LDAP bind placeholders match the fallbacks application.yml declares")
    void testLdapBindPlaceholdersMatchApplicationYml() throws IOException {
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));
        assertThat(yaml)
                .contains("${IDM_LDAP_USER_DN:" + RealEnvironmentScenarios.LDAP_USER_DN_PLACEHOLDER + "}")
                .contains("${IDM_LDAP_PASSWORD:" + RealEnvironmentScenarios.LDAP_PASSWORD_PLACEHOLDER + "}");
    }

    @Test
    @DisplayName("The database placeholders match the fallbacks application.yml declares")
    void testDatabasePlaceholdersMatchApplicationYml() throws IOException {
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));
        Map<String, String> prefixes = Map.of("sap", "SAP_DB", "oracle-hr", "HR_DB", "workday", "WORKDAY_DB");

        assertThat(RealEnvironmentScenarios.DATABASE_PLACEHOLDERS).containsOnlyKeys("sap", "oracle-hr", "workday");
        RealEnvironmentScenarios.DATABASE_PLACEHOLDERS.forEach((name, placeholder) -> assertThat(yaml)
                .contains("${" + prefixes.get(name) + "_URL:" + placeholder.jdbcUrl() + "}")
                .contains("${" + prefixes.get(name) + "_USER:" + placeholder.username() + "}")
                .contains("${" + prefixes.get(name) + "_PASSWORD:" + placeholder.password() + "}"));
    }

    @Test
    @DisplayName("An empty scenario directory is refused")
    void testEmptyScenarioDirectoryRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireScenarios(List.of(), RealEnvironmentScenarios.DEFAULT_SCENARIO_DIR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scenarios-real");
    }

    @Test
    @DisplayName("The empty-directory message names the directory that was actually searched")
    void testEmptyScenarioDirectoryMessageNamesDirectory() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireScenarios(List.of(), "some-other-dir"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("src/test/resources/some-other-dir");
    }

    @Test
    @DisplayName("A scenario directory with at least one file passes")
    void testNonEmptyScenarioDirectoryPasses() {
        assertThatCode(() -> RealEnvironmentScenarios.requireScenarios(List.of("create-user.yaml"), RealEnvironmentScenarios.DEFAULT_SCENARIO_DIR))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The shipped scenarios-real directory is empty, so a first run hits the guard")
    void testShippedDirectoryHasNoScenarios() throws Exception {
        // Pins spec section 7: the directory ships with a README only. If someone copies the
        // embedded corpus in, which writes straight to LDAP and would create entries IdM does
        // not know about, this fails and makes them read the README first.
        // This is a deliberate tripwire, not a permanent invariant: when the first real
        // scenario is added here, delete this test.
        assertThat(YamlScenarioTest.scenarioFiles(RealEnvironmentScenarios.DEFAULT_SCENARIO_DIR)).isEmpty();
        assertThat(getClass().getClassLoader().getResource(RealEnvironmentScenarios.DEFAULT_SCENARIO_DIR + "/README.md"))
                .as("scenarios-real/README.md must ship so the directory exists on the classpath")
                .isNotNull();
    }

    private static Scenario scenario(String yaml) {
        return ScenarioLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "inline.yaml");
    }

    private static RestProperties restTarget(String target, String baseUrl) {
        RestProperties properties = new RestProperties();
        RestProperties.RestApiConfig config = new RestProperties.RestApiConfig();
        config.setBaseUrl(baseUrl);
        properties.getRest().put(target, config);
        return properties;
    }

    @Test
    @DisplayName("Guard 4 collects the literal targets of rest.* steps, and nothing else")
    void testRestTargetsCollected() {
        Scenario scenario = scenario("""
                name: rest targets
                steps:
                  - rest.request:
                      target: idm
                      method: GET
                      path: /users
                  - rest.verify:
                      target: sap
                      path: /accounts/1
                      expect:
                        status: 200
                  - roles.grant:
                      target: hr
                  - rest.request:
                      target: "${chosen}"
                      method: GET
                      path: /x
                  - rest.request:
                      target: idm
                      method: GET
                      path: /again
                """);

        // idm and sap from both REST step types, once each; hr is not a REST step; the
        // expression target is only known at run time.
        assertThat(RealEnvironmentScenarios.restTargets(List.of(scenario))).containsExactly("idm", "sap");
    }

    @Test
    @DisplayName("Scenarios with no REST steps use no REST targets")
    void testNoRestTargetsWithoutRestSteps() {
        Scenario scenario = scenario("""
                name: ldap only
                steps:
                  - ldap.create:
                      dn: cn=x,ou=users
                      attributes:
                        cn: x
                """);

        assertThat(RealEnvironmentScenarios.restTargets(List.of(scenario))).isEmpty();
    }

    @Test
    @DisplayName("Guard 4 refuses a REST target that is not configured")
    void testUnknownRestTargetRefused() {
        RestProperties properties = restTarget("idm", "https://idm.example.org/IDMProv");

        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRestTargets(Set.of("sapp"), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'sapp'")
                .hasMessageContaining("not configured")
                .hasMessageContaining("[idm]");
    }

    @Test
    @DisplayName("Guard 4 refuses a blank REST base-url")
    void testBlankRestBaseUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRestTargets(Set.of("idm"), restTarget("idm", "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.rest.idm.base-url is not set")
                .hasMessageContaining("IDM_TEST_REST_IDM_BASEURL");
    }

    @Test
    @DisplayName("Guard 4 refuses a missing REST base-url")
    void testMissingRestBaseUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRestTargets(Set.of("idm"), restTarget("idm", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.rest.idm.base-url is not set");
    }

    @Test
    @DisplayName("Guard 4 refuses each REST target's application.yml placeholder")
    void testRestPlaceholdersRefused() {
        assertThat(RealEnvironmentScenarios.REST_URL_PLACEHOLDERS).hasSize(4);

        RealEnvironmentScenarios.REST_URL_PLACEHOLDERS.forEach((target, placeholder) ->
                assertThatThrownBy(() -> RealEnvironmentScenarios.requireRestTargets(
                        Set.of(target), restTarget(target, placeholder)))
                        .as("target %s", target)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("placeholder")
                        .hasMessageContaining("IDM_TEST_REST_" + target.toUpperCase(Locale.ROOT) + "_BASEURL"));
    }

    @Test
    @DisplayName("Guard 4 accepts a REST target with a real base-url")
    void testRealRestBaseUrlPasses() {
        assertThatCode(() -> RealEnvironmentScenarios.requireRestTargets(
                Set.of("idm"), restTarget("idm", "https://idm.example.org/IDMProv")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A run that uses no REST target makes no REST demand, even with every placeholder configured")
    void testNoRestTargetsNoDemand() {
        RestProperties properties = new RestProperties();
        RealEnvironmentScenarios.REST_URL_PLACEHOLDERS.forEach((target, placeholder) ->
                properties.getRest().putAll(restTarget(target, placeholder).getRest()));

        assertThatCode(() -> RealEnvironmentScenarios.requireRestTargets(Set.of(), properties))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Guard 4's file loading ties into the guard: a placeholder from a real fixture is refused")
    void testRequireRestTargetsInLoadsFixtureAndRefusesPlaceholder() {
        RestProperties properties = restTarget("idm", RealEnvironmentScenarios.REST_URL_PLACEHOLDERS.get("idm"));

        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRestTargetsIn(
                "scenarios-vocabulary-fixture", List.of("full-vocabulary.yaml"), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDM_TEST_REST_IDM_BASEURL");
    }

    @Test
    @DisplayName("Guard 4's file loading passes a real fixture with a real base-url")
    void testRequireRestTargetsInLoadsFixtureAndPasses() {
        RestProperties properties = restTarget("idm", "https://idm.example.org/IDMProv");

        assertThatCode(() -> RealEnvironmentScenarios.requireRestTargetsIn(
                "scenarios-vocabulary-fixture", List.of("full-vocabulary.yaml"), properties))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Guard 4's file loading skips a file that does not exist")
    void testRequireRestTargetsInSkipsMissingFile() {
        // A missing file is skipped here on purpose: buildTests reports it as its own failing
        // test carrying the real error, and reporting it twice would only add noise.
        RestProperties properties = restTarget("idm", RealEnvironmentScenarios.REST_URL_PLACEHOLDERS.get("idm"));

        assertThatCode(() -> RealEnvironmentScenarios.requireRestTargetsIn(
                "scenarios-vocabulary-fixture", List.of("no-such-file.yaml"), properties))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Guard 4's target variable name strips hyphens from hyphenated target names")
    void testHyphenatedRestTargetMessage() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRestTargets(
                Set.of("my-api"), restTarget("my-api", "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDM_TEST_REST_MYAPI_BASEURL");
    }

    @Test
    @DisplayName("Guard 4 also collects REST targets used in cleanup")
    void testRestTargetsCollectedFromCleanup() {
        Scenario scenario = scenario("""
                name: rest in cleanup
                steps:
                  - ldap.create:
                      dn: cn=x,ou=users
                cleanup:
                  - rest.request:
                      target: servicenow
                      method: DELETE
                      path: /records/1
                """);

        assertThat(RealEnvironmentScenarios.restTargets(List.of(scenario))).containsExactly("servicenow");
    }

    @Test
    @DisplayName("Guard 4 also collects http.request targets")
    void testHttpTargetsCollected() {
        Scenario scenario = scenario("""
                name: http target
                steps:
                  - http.request:
                      target: roleservice
                      method: POST
                      path: /IDMProv/role/service
                      body: "<Envelope/>"
                """);

        assertThat(RealEnvironmentScenarios.restTargets(List.of(scenario))).containsExactly("roleservice");
    }

    @Test
    @DisplayName("Guard 4 collects a non-string REST target as its String.valueOf form")
    void testNonStringRestTargetCollected() {
        Scenario scenario = scenario("""
                name: numeric target
                steps:
                  - rest.request:
                      target: 7
                      method: GET
                      path: /x
                """);

        assertThat(RealEnvironmentScenarios.restTargets(List.of(scenario))).containsExactly("7");
    }

    @Test
    @DisplayName("The placeholders match the fallbacks application.yml actually declares")
    void testPlaceholdersMatchApplicationYml() throws IOException {
        // If application.yml's fallbacks change and these constants don't, guard 2 would
        // silently compare against a value nothing resolves to and never fire again.
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));

        assertThat(yaml)
                .contains("${IDM_LDAP_URL:" + RealEnvironmentScenarios.LDAP_URL_PLACEHOLDER + "}")
                .contains("${IDM_SOAP_URL:" + RealEnvironmentScenarios.SOAP_URL_PLACEHOLDER + "}");

        Map<String, String> restVariables = Map.of(
                "idm", "IDM_REST_URL",
                "sap", "SAP_REST_URL",
                "workday", "WORKDAY_REST_URL",
                "servicenow", "SERVICENOW_REST_URL");
        assertThat(RealEnvironmentScenarios.REST_URL_PLACEHOLDERS.keySet())
                .containsExactlyInAnyOrderElementsOf(restVariables.keySet());
        RealEnvironmentScenarios.REST_URL_PLACEHOLDERS.forEach((target, placeholder) ->
                assertThat(yaml).contains("${" + restVariables.get(target) + ":" + placeholder + "}"));
    }

    private static RestProperties credentialed(String target, Consumer<RestProperties.RestApiConfig> settings) {
        RestProperties properties = restTarget(target, "https://api.example.org");
        settings.accept(properties.getRest().get(target));
        return properties;
    }

    private static SoapProperties soap(String username, String password) {
        SoapProperties soap = new SoapProperties();
        soap.setUsername(username);
        soap.setPassword(password);
        return soap;
    }

    private static final SoapProperties NO_SOAP = soap(null, null);

    private static MockEnvironment registration(String name, String clientId, String secret) {
        MockEnvironment environment = new MockEnvironment();
        String prefix = "spring.security.oauth2.client.registration." + name + ".";
        if (clientId != null) {
            environment.setProperty(prefix + "client-id", clientId);
        }
        if (secret != null) {
            environment.setProperty(prefix + "client-secret", secret);
        }
        return environment;
    }

    private static void refuses(Runnable guard, String... fragments) {
        assertThatThrownBy(guard::run).isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains(fragments));
    }

    @Test
    @DisplayName("Guard 5 lets real basic credentials, an unauthenticated target, and a real OAuth client through")
    void testRealCredentialsPass() {
        assertThatCode(() -> RealEnvironmentScenarios.requireCredentials(Set.of("idm"), false,
                credentialed("idm", c -> { c.setUsername("svc-real"); c.setPassword("real-pass"); }),
                NO_SOAP, new MockEnvironment())).doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireCredentials(Set.of("open"), false,
                credentialed("open", c -> { }), NO_SOAP, new MockEnvironment())).doesNotThrowAnyException();
        assertThatCode(() -> RealEnvironmentScenarios.requireCredentials(Set.of("sap"), false,
                credentialed("sap", c -> { c.setUseOAuth(true); c.setOauthClientName("sap"); }),
                NO_SOAP, registration("sap", "real-id", "real-secret"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Guard 5 refuses the REST credential placeholders, naming the variable and not the value")
    void testRestCredentialPlaceholdersRefused() {
        RealEnvironmentScenarios.Credentials placeholder = RealEnvironmentScenarios.REST_CREDENTIAL_PLACEHOLDERS.get("idm");

        refuses(() -> RealEnvironmentScenarios.requireCredentials(Set.of("idm"), false,
                        credentialed("idm", c -> { c.setUsername(placeholder.username()); c.setPassword("real-pass"); }),
                        NO_SOAP, new MockEnvironment()),
                "idm.test.rest.idm.username", "IDM_TEST_REST_IDM_USERNAME");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireCredentials(Set.of("idm"), false,
                credentialed("idm", c -> { c.setUsername("svc-real"); c.setPassword(placeholder.password()); }),
                NO_SOAP, new MockEnvironment()))
                .hasMessageContaining("IDM_TEST_REST_IDM_PASSWORD")
                .hasMessageNotContaining(placeholder.password());
    }

    @Test
    @DisplayName("Guard 5 refuses a REST username without a password and the reverse, without the value")
    void testRestHalfCredentialsRefused() {
        refuses(() -> RealEnvironmentScenarios.requireCredentials(Set.of("idm"), false,
                        credentialed("idm", c -> c.setUsername("svc-real")), NO_SOAP, new MockEnvironment()),
                "IDM_TEST_REST_IDM_PASSWORD");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireCredentials(Set.of("idm"), false,
                credentialed("idm", c -> c.setPassword("real-pass")), NO_SOAP, new MockEnvironment()))
                .hasMessageContaining("IDM_TEST_REST_IDM_USERNAME")
                .hasMessageNotContaining("real-pass");
    }

    @Test
    @DisplayName("Guard 5 refuses use-oauth without a name, or naming an unknown registration")
    void testOAuthRegistrationRefused() {
        refuses(() -> RealEnvironmentScenarios.requireCredentials(Set.of("sap"), false,
                        credentialed("sap", c -> c.setUseOAuth(true)), NO_SOAP, new MockEnvironment()),
                "idm.test.rest.sap.oauth-client-name", "IDM_TEST_REST_SAP_OAUTHCLIENTNAME");
        refuses(() -> RealEnvironmentScenarios.requireCredentials(Set.of("sap"), false,
                        credentialed("sap", c -> { c.setUseOAuth(true); c.setOauthClientName("nope"); }),
                        NO_SOAP, new MockEnvironment()),
                "'nope'", "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_NOPE_CLIENTID");
    }

    @Test
    @DisplayName("Guard 5 refuses each OAuth client placeholder")
    void testOAuthPlaceholdersRefused() {
        assertThat(RealEnvironmentScenarios.OAUTH_CLIENT_PLACEHOLDERS).containsOnlyKeys("sap", "workday", "servicenow");
        RealEnvironmentScenarios.OAUTH_CLIENT_PLACEHOLDERS.forEach((name, placeholder) -> {
            String variable = "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_" + name.toUpperCase(Locale.ROOT);
            RestProperties target = credentialed(name, c -> { c.setUseOAuth(true); c.setOauthClientName(name); });
            refuses(() -> RealEnvironmentScenarios.requireCredentials(Set.of(name), false, target, NO_SOAP,
                    registration(name, placeholder.username(), "real-secret")), variable + "_CLIENTID");
            assertThatThrownBy(() -> RealEnvironmentScenarios.requireCredentials(Set.of(name), false, target, NO_SOAP,
                    registration(name, "real-id", placeholder.password())))
                    .hasMessageContaining(variable + "_CLIENTSECRET")
                    .hasMessageNotContaining(placeholder.password());
        });
    }

    @Test
    @DisplayName("Guard 5 checks SOAP credentials only when roles.* or resources.* are used")
    void testSoapCredentialsOnlyWhenUsed() {
        RealEnvironmentScenarios.Credentials placeholder = RealEnvironmentScenarios.SOAP_CREDENTIAL_PLACEHOLDER;
        SoapProperties placeholders = soap(placeholder.username(), placeholder.password());

        assertThatCode(() -> RealEnvironmentScenarios.requireCredentials(Set.of(), false,
                new RestProperties(), placeholders, new MockEnvironment())).doesNotThrowAnyException();
        refuses(() -> RealEnvironmentScenarios.requireCredentials(Set.of(), true,
                        new RestProperties(), placeholders, new MockEnvironment()),
                "IDM_TEST_SOAP_USERNAME");
        refuses(() -> RealEnvironmentScenarios.requireCredentials(Set.of(), true,
                        new RestProperties(), soap("svc-real", null), new MockEnvironment()),
                "IDM_TEST_SOAP_PASSWORD");
        assertThatCode(() -> RealEnvironmentScenarios.requireCredentials(Set.of(), true,
                new RestProperties(), soap("svc-real", "real-pass"), new MockEnvironment())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("usesSoap sees roles.* and resources.* in steps and in cleanup, and nothing else")
    void testUsesSoap() {
        assertThat(RealEnvironmentScenarios.usesSoap(List.of(scenario("""
                name: ldap only
                steps:
                  - ldap.delete:
                      dn: cn=x,ou=users
                """)))).isFalse();
        assertThat(RealEnvironmentScenarios.usesSoap(List.of(scenario("""
                name: grant
                steps:
                  - roles.grant:
                      user: cn=x,ou=users
                      roles: [cn=r,ou=roles]
                """)))).isTrue();
        assertThat(RealEnvironmentScenarios.usesSoap(List.of(scenario("""
                name: cleanup revoke
                steps:
                  - ldap.delete:
                      dn: cn=x,ou=users
                cleanup:
                  - resources.revoke:
                      user: cn=x,ou=users
                      resource: cn=res,ou=resources
                """)))).isTrue();
    }

    @Test
    @DisplayName("The credential placeholders match the fallbacks application.yml declares")
    void testCredentialPlaceholdersMatchApplicationYml() throws IOException {
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));
        RealEnvironmentScenarios.Credentials rest = RealEnvironmentScenarios.REST_CREDENTIAL_PLACEHOLDERS.get("idm");
        RealEnvironmentScenarios.Credentials soap = RealEnvironmentScenarios.SOAP_CREDENTIAL_PLACEHOLDER;

        assertThat(RealEnvironmentScenarios.REST_CREDENTIAL_PLACEHOLDERS).containsOnlyKeys("idm");
        assertThat(yaml)
                .contains("${IDM_REST_USER:" + rest.username() + "}")
                .contains("${IDM_REST_PASSWORD:" + rest.password() + "}")
                .contains("${IDM_SOAP_USER:" + soap.username() + "}")
                .contains("${IDM_SOAP_PASSWORD:" + soap.password() + "}");
        Map<String, String> prefixes = Map.of("sap", "SAP", "workday", "WORKDAY", "servicenow", "SERVICENOW");
        RealEnvironmentScenarios.OAUTH_CLIENT_PLACEHOLDERS.forEach((name, placeholder) -> assertThat(yaml)
                .contains("${" + prefixes.get(name) + "_CLIENT_ID:" + placeholder.username() + "}")
                .contains("${" + prefixes.get(name) + "_CLIENT_SECRET:" + placeholder.password() + "}"));
    }

    /** A dev target with the old block pointing at a real directory, plus the given named-directory properties. */
    private static MockEnvironment directoriesTarget(String... keyValues) {
        MockEnvironment environment = target(REAL_LDAP, null, "dev");
        for (int i = 0; i < keyValues.length; i += 2) {
            environment.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return environment;
    }

    @Test
    @DisplayName("A configured named directory passes")
    void testNamedDirectoryPasses() {
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(
                directoriesTarget("idm.test.directories.ad.url", "ldaps://dc01.example.org:636"), AD_ONLY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A named directory without a url, or with the placeholder, is refused")
    void testNamedDirectoryUrl() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                directoriesTarget("idm.test.directories.ad.base-dn", "dc=corp"), AD_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.directories.ad.url")
                .hasMessageContaining("IDM_TEST_DIRECTORIES_AD_URL");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                directoriesTarget("idm.test.directories.ad.url", "ldap://localhost:389"), AD_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.directories.ad.url")
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("A named directory's placeholder bind DN or password is refused")
    void testNamedDirectoryCredentials() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(directoriesTarget(
                "idm.test.directories.ad.url", "ldaps://dc01.example.org:636",
                "idm.test.directories.ad.user-dn", "cn=admin,dc=company,dc=com"), AD_ONLY))
                .hasMessageContaining("idm.test.directories.ad.user-dn");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(directoriesTarget(
                "idm.test.directories.ad.url", "ldaps://dc01.example.org:636",
                "idm.test.directories.ad.password", "secret"), AD_ONLY))
                .hasMessageContaining("idm.test.directories.ad.password");
    }

    @Test
    @DisplayName("A directory the scenarios use but nobody configured is refused")
    void testUnconfiguredDirectory() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(directoriesTarget(
                "idm.test.directories.ad.url", "ldaps://dc01.example.org:636"),
                new ScenarioNeeds(Set.of("adx"), Set.of(), false, false, Set.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Scenarios use directory 'adx', which is not configured. Configured directories: [ad, default]");
    }

    @Test
    @DisplayName("A step without directory: is refused when there is no default")
    void testNoDefaultDirectory() {
        MockEnvironment environment = target(null, null, "dev");
        environment.setProperty("idm.test.directories.ad.url", "ldaps://dc01.example.org:636");
        environment.setProperty("idm.test.directories.edir.url", "ldaps://edir.example.org:636");

        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(environment, DEFAULT_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Scenarios use an ldap.* step without directory:, and there is no default directory."
                        + " Set idm.test.default-directory, or name a directory on each step."
                        + " Configured directories: [ad, edir]");
    }

    @Test
    @DisplayName("A step without directory: that falls back to the old block says how to pick a named directory")
    void testOldBlockDefaultHint() {
        MockEnvironment environment = target(RealEnvironmentScenarios.LDAP_URL_PLACEHOLDER, null, "dev");
        environment.setProperty("idm.test.directories.ad.url", "ldaps://dc01.example.org:636");

        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(environment, DEFAULT_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDM_TEST_LDAP_URL")
                .hasMessageContaining("set idm.test.default-directory")
                .hasMessageContaining("[ad]");
    }

    @Test
    @DisplayName("default-directory decides what a step without directory: is checked against")
    void testDefaultDirectorySetting() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(directoriesTarget(
                "idm.test.default-directory", "ad",
                "idm.test.directories.ad.url", "ldap://localhost:389"), DEFAULT_ONLY))
                .hasMessageContaining("idm.test.directories.ad.url");
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(directoriesTarget(
                "idm.test.default-directory", "nope",
                "idm.test.directories.ad.url", "ldaps://dc01.example.org:636"), DEFAULT_ONLY))
                .hasMessageContaining("idm.test.default-directory is 'nope'");
    }

    @Test
    @DisplayName("Directories no scenario uses are not checked")
    void testUnusedDirectoryNotChecked() {
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(
                directoriesTarget("idm.test.directories.ad.url", "ldap://localhost:389"), DEFAULT_ONLY))
                .doesNotThrowAnyException();
    }
}
