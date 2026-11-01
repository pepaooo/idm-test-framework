package com.idm.test.scenarios.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileConfigurationTest {

    private static final Path RESOURCES = Path.of("src/test/resources");

    @TempDir
    Path temp;

    @Configuration
    static class Config {
    }

    @Test
    void profileFilesReplaceActivationDocuments() throws IOException {
        String shared = Files.readString(RESOURCES.resolve("application.yml"));
        assertThat(shared).doesNotContain("spring.config.activate.on-profile", "\n---");
        for (String profile : new String[]{"dev", "test", "prod"}) {
            assertThat(RESOURCES.resolve("application-" + profile + ".yml")).exists();
        }
    }

    @Test
    void committedProfilesKeepExistingConnectionAndExecutionFallbacks() throws IOException {
        assertProperties("default", Map.of(
                "idm.test.ldap.url", "ldap://localhost:389",
                "idm.test.databases.sap.jdbc-url", "jdbc:postgresql://localhost:5432/sap",
                "idm.test.soap.url", "https://idm.company.com:8543/IDMProv/role/service",
                "idm.test.executor.default-timeout-seconds", "300",
                "logging.level.com.idm.test", "DEBUG"));
        assertProperties("dev", Map.of(
                "idm.test.ldap.url", "ldap://dev-edirectory.company.com:389",
                "idm.test.ldap.base-dn", "dc=dev,dc=company,dc=com",
                "idm.test.databases.sap.jdbc-url", "jdbc:postgresql://dev-db.company.com:5432/sap_dev",
                "idm.test.soap.url", "https://dev-idm.company.com:8543/IDMProv/role/service",
                "idm.test.rest.idm.base-url", "https://dev-idm.company.com:8543/IDMProv",
                "idm.test.executor.default-timeout-seconds", "300",
                "logging.level.com.idm.test", "TRACE"));
        assertProperties("test", Map.of(
                "idm.test.ldap.url", "ldap://localhost:10389",
                "idm.test.ldap.base-dn", "dc=test,dc=com",
                "idm.test.databases.sap.jdbc-url", "jdbc:h2:mem:sap_test;MODE=PostgreSQL",
                "idm.test.databases.sap.username", "sa",
                "idm.test.databases.sap.driver-class-name", "org.h2.Driver",
                "idm.test.soap.url", "http://localhost:8090/IDMProv/role/service",
                "idm.test.executor.default-timeout-seconds", "60",
                "logging.level.com.idm.test", "DEBUG"));
        assertProperties("prod", Map.of(
                "idm.test.ldap.url", "ldaps://prod-edirectory.company.com:636",
                "idm.test.databases.sap.jdbc-url", "jdbc:postgresql://prod-db.company.com:5432/sap",
                "idm.test.databases.sap.max-pool-size", "10",
                "idm.test.soap.url", "https://idm.company.com:8543/IDMProv/role/service",
                "idm.test.rest.servicenow.base-url", "https://company.service-now.com/api",
                "idm.test.executor.default-timeout-seconds", "300",
                "logging.level.com.idm.test", "INFO"));
    }

    @Test
    void legacyVariablesOverrideConnectionsInEachProfile() throws IOException {
        Map<String, Object> variables = Map.of(
                "IDM_LDAP_URL", "ldaps://override.example:636",
                "SAP_DB_URL", "jdbc:postgresql://override.example:5432/sap",
                "IDM_SOAP_URL", "https://override.example/role",
                "IDM_REST_URL", "https://override.example/rest");
        for (String profile : new String[]{"dev", "test", "prod"}) {
            assertProperties(profile, variables, Map.of(
                    "idm.test.ldap.url", "ldaps://override.example:636",
                    "idm.test.databases.sap.jdbc-url", "jdbc:postgresql://override.example:5432/sap",
                    "idm.test.soap.url", "https://override.example/role",
                    "idm.test.rest.idm.base-url", "https://override.example/rest"));
        }
    }

    @Test
    void directPropertyVariableWinsOverLegacyPlaceholder() throws IOException {
        assertProperties("dev", Map.of(
                "IDM_LDAP_URL", "ldaps://legacy.example:636",
                "IDM_TEST_LDAP_URL", "ldaps://direct.example:636"), Map.of(
                "idm.test.ldap.url", "ldaps://direct.example:636"));
    }

    @Test
    void localExampleOverridesConnectionsAndInheritsSharedSettings() throws IOException {
        assertThat(RESOURCES.resolve("application-local.yml.example")).exists();
        assertProperties("local", Map.of(), Map.of(
                "idm.test.directories.edirectory.url", "ldaps://directory.example.invalid:636",
                "idm.test.executor.default-timeout-seconds", "300",
                "logging.level.com.idm.test", "DEBUG"), true);
    }

    @Test
    void localProfileWithoutFileUsesSharedDefaults() throws IOException {
        assertProperties("local", Map.of(
                "idm.test.ldap.url", "ldap://localhost:389",
                "idm.test.executor.default-timeout-seconds", "300",
                "logging.level.com.idm.test", "DEBUG"));
    }

    private void assertProperties(String profile, Map<String, String> expected) throws IOException {
        assertProperties(profile, Map.of(), expected);
    }

    private void assertProperties(String profile, Map<String, Object> variables,
                                  Map<String, String> expected) throws IOException {
        assertProperties(profile, variables, expected, false);
    }

    private void assertProperties(String profile, Map<String, Object> variables,
                                  Map<String, String> expected, boolean useLocalExample) throws IOException {
        Path config = temp.resolve(profile);
        Files.createDirectories(config);
        Files.copy(RESOURCES.resolve("application.yml"), config.resolve("application.yml"));
        Path source = RESOURCES.resolve(useLocalExample
                ? "application-local.yml.example" : "application-" + profile + ".yml");
        if (profile.equals("local") && !useLocalExample) {
            source = RESOURCES.resolve("missing-local.yml");
        }
        if (Files.exists(source)) {
            Files.copy(source, config.resolve("application-" + profile + ".yml"));
        }

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("systemEnvironment", variables));
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        SpringApplication application = new SpringApplication(Config.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setEnvironment(environment);
        try (ConfigurableApplicationContext context = application.run(
                "--spring.config.location=" + config.toUri(),
                "--spring.profiles.active=" + profile,
                "--spring.main.banner-mode=off")) {
            ConfigurableEnvironment actual = context.getEnvironment();
            expected.forEach((key, value) -> assertThat(actual.getProperty(key)).as(profile + " " + key)
                    .isEqualTo(value));
        }
    }
}
