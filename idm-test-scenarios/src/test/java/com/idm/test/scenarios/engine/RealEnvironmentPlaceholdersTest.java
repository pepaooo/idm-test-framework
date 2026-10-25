package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.wiring.ClasspathScenarioSource;
import com.idm.test.wiring.RealEnvironmentGuards;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
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
 * The guard tests that read this module's own resources: application.yml's fallbacks (the
 * placeholder tables in {@link RealEnvironmentGuards} are pinned to them) and the vocabulary
 * fixture. The rest live with the guards in
 * idm-test-wiring.
 */
@DisplayName("Real-environment placeholders and fixtures")
class RealEnvironmentPlaceholdersTest {

    @Test
    @DisplayName("The LDAP bind placeholders match the fallbacks application.yml declares")
    void testLdapBindPlaceholdersMatchApplicationYml() throws IOException {
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));
        assertThat(yaml)
                .contains("${IDM_LDAP_USER_DN:" + RealEnvironmentGuards.LDAP_USER_DN_PLACEHOLDER + "}")
                .contains("${IDM_LDAP_PASSWORD:" + RealEnvironmentGuards.LDAP_PASSWORD_PLACEHOLDER + "}");
    }

    @Test
    @DisplayName("The database placeholders match the fallbacks application.yml declares")
    void testDatabasePlaceholdersMatchApplicationYml() throws IOException {
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));
        Map<String, String> prefixes = Map.of("sap", "SAP_DB", "oracle-hr", "HR_DB", "workday", "WORKDAY_DB");

        assertThat(RealEnvironmentGuards.DATABASE_PLACEHOLDERS).containsOnlyKeys("sap", "oracle-hr", "workday");
        RealEnvironmentGuards.DATABASE_PLACEHOLDERS.forEach((name, placeholder) -> assertThat(yaml)
                .contains("${" + prefixes.get(name) + "_URL:" + placeholder.jdbcUrl() + "}")
                .contains("${" + prefixes.get(name) + "_USER:" + placeholder.username() + "}")
                .contains("${" + prefixes.get(name) + "_PASSWORD:" + placeholder.password() + "}"));
    }

    private static RestProperties restTarget(String target, String baseUrl) {
        RestProperties properties = new RestProperties();
        RestProperties.RestApiConfig config = new RestProperties.RestApiConfig();
        config.setBaseUrl(baseUrl);
        properties.getRest().put(target, config);
        return properties;
    }

    @Test
    @DisplayName("Guard 4's file loading ties into the guard: a placeholder from a real fixture is refused")
    void testRequireRestTargetsInLoadsFixtureAndRefusesPlaceholder() {
        RestProperties properties = restTarget("idm", RealEnvironmentGuards.REST_URL_PLACEHOLDERS.get("idm"));

        assertThatThrownBy(() -> RealEnvironmentGuards.requireRestTargetsIn(
                new ClasspathScenarioSource("scenarios-vocabulary-fixture"), List.of("full-vocabulary.yaml"), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDM_TEST_REST_IDM_BASEURL");
    }

    @Test
    @DisplayName("Guard 4's file loading passes a real fixture with a real base-url")
    void testRequireRestTargetsInLoadsFixtureAndPasses() {
        RestProperties properties = restTarget("idm", "https://idm.example.org/IDMProv");

        assertThatCode(() -> RealEnvironmentGuards.requireRestTargetsIn(
                new ClasspathScenarioSource("scenarios-vocabulary-fixture"), List.of("full-vocabulary.yaml"), properties))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Guard 4's file loading skips a file that does not exist")
    void testRequireRestTargetsInSkipsMissingFile() {
        // A missing file is skipped here on purpose: buildTests reports it as its own failing
        // test carrying the real error, and reporting it twice would only add noise.
        RestProperties properties = restTarget("idm", RealEnvironmentGuards.REST_URL_PLACEHOLDERS.get("idm"));

        assertThatCode(() -> RealEnvironmentGuards.requireRestTargetsIn(
                new ClasspathScenarioSource("scenarios-vocabulary-fixture"), List.of("no-such-file.yaml"), properties))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The placeholders match the fallbacks application.yml actually declares")
    void testPlaceholdersMatchApplicationYml() throws IOException {
        // If application.yml's fallbacks change and these constants don't, guard 2 would
        // silently compare against a value nothing resolves to and never fire again.
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));

        assertThat(yaml)
                .contains("${IDM_LDAP_URL:" + RealEnvironmentGuards.LDAP_URL_PLACEHOLDER + "}")
                .contains("${IDM_SOAP_URL:" + RealEnvironmentGuards.SOAP_URL_PLACEHOLDER + "}");

        Map<String, String> restVariables = Map.of(
                "idm", "IDM_REST_URL",
                "sap", "SAP_REST_URL",
                "workday", "WORKDAY_REST_URL",
                "servicenow", "SERVICENOW_REST_URL");
        assertThat(RealEnvironmentGuards.REST_URL_PLACEHOLDERS.keySet())
                .containsExactlyInAnyOrderElementsOf(restVariables.keySet());
        RealEnvironmentGuards.REST_URL_PLACEHOLDERS.forEach((target, placeholder) ->
                assertThat(yaml).contains("${" + restVariables.get(target) + ":" + placeholder + "}"));
    }

    @Test
    @DisplayName("The credential placeholders match the fallbacks application.yml declares")
    void testCredentialPlaceholdersMatchApplicationYml() throws IOException {
        String yaml = Files.readString(Path.of("src/test/resources/application.yml"));
        RealEnvironmentGuards.Credentials rest = RealEnvironmentGuards.REST_CREDENTIAL_PLACEHOLDERS.get("idm");
        RealEnvironmentGuards.Credentials soap = RealEnvironmentGuards.SOAP_CREDENTIAL_PLACEHOLDER;

        assertThat(RealEnvironmentGuards.REST_CREDENTIAL_PLACEHOLDERS).containsOnlyKeys("idm");
        assertThat(yaml)
                .contains("${IDM_REST_USER:" + rest.username() + "}")
                .contains("${IDM_REST_PASSWORD:" + rest.password() + "}")
                .contains("${IDM_SOAP_USER:" + soap.username() + "}")
                .contains("${IDM_SOAP_PASSWORD:" + soap.password() + "}");
        Map<String, String> prefixes = Map.of("sap", "SAP", "workday", "WORKDAY", "servicenow", "SERVICENOW");
        RealEnvironmentGuards.OAUTH_CLIENT_PLACEHOLDERS.forEach((name, placeholder) -> assertThat(yaml)
                .contains("${" + prefixes.get(name) + "_CLIENT_ID:" + placeholder.username() + "}")
                .contains("${" + prefixes.get(name) + "_CLIENT_SECRET:" + placeholder.password() + "}"));
    }
}
