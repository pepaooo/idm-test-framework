package com.idm.test.scenarios.engine;

import com.idm.test.connector.rest.config.RestProperties;
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

    @Test
    @DisplayName("A named, non-embedded target passes")
    void testRealTargetPasses() {
        assertThatCode(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, REAL_SOAP, "dev")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The embedded 'test' profile is refused")
    void testEmbeddedProfileRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, REAL_SOAP, "test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'test'")
                .hasMessageContaining("IDM_TEST_PROFILE");
    }

    @Test
    @DisplayName("The embedded 'test' profile is refused even alongside another profile")
    void testEmbeddedProfileRefusedAmongOthers() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, REAL_SOAP, "dev", "test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'test'");
    }

    @Test
    @DisplayName("An unset LDAP URL is refused")
    void testUnsetLdapUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(null, REAL_SOAP, "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.url")
                .hasMessageContaining("IDM_TEST_LDAP_URL");
    }

    @Test
    @DisplayName("A blank LDAP URL is refused")
    void testBlankLdapUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target("  ", REAL_SOAP, "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.url");
    }

    @Test
    @DisplayName("The LDAP URL placeholder from application.yml is refused")
    void testLdapPlaceholderRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                target(RealEnvironmentScenarios.LDAP_URL_PLACEHOLDER, REAL_SOAP, "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.ldap.url")
                .hasMessageContaining("placeholder")
                .hasMessageContaining("IDM_TEST_LDAP_URL");
    }

    @Test
    @DisplayName("An unset SOAP URL is refused")
    void testUnsetSoapUrlRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(target(REAL_LDAP, null, "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.soap.url")
                .hasMessageContaining("IDM_TEST_SOAP_URL");
    }

    @Test
    @DisplayName("The SOAP URL placeholder from application.yml is refused")
    void testSoapPlaceholderRefused() {
        assertThatThrownBy(() -> RealEnvironmentScenarios.requireRealTarget(
                target(REAL_LDAP, RealEnvironmentScenarios.SOAP_URL_PLACEHOLDER, "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idm.test.soap.url")
                .hasMessageContaining("placeholder")
                .hasMessageContaining("IDM_TEST_SOAP_URL");
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
}
