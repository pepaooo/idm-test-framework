package com.idm.test.scenarios.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test
    @DisplayName("The placeholders match the fallbacks application.yml actually declares")
    void testPlaceholdersMatchApplicationYml() throws IOException {
        // If application.yml's fallbacks change and these constants don't, guard 2 would
        // silently compare against a value nothing resolves to and never fire again.
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));

        assertThat(yaml)
                .contains("${IDM_LDAP_URL:" + RealEnvironmentScenarios.LDAP_URL_PLACEHOLDER + "}")
                .contains("${IDM_SOAP_URL:" + RealEnvironmentScenarios.SOAP_URL_PLACEHOLDER + "}");
    }
}
