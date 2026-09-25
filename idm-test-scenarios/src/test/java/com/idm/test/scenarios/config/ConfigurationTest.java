package com.idm.test.scenarios.config;

import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.connector.ldap.config.LdapProperties;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.core.config.TestExecutorProperties;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that verify configuration properties are loaded correctly from application.yml.
 * This ensures all connectors can access their required configuration.
 * <p>
 * Note: Auto-configurations are excluded to test only property binding without
 * requiring actual database/LDAP connections.
 */
@SpringBootTest
@ActiveProfiles("test")  // Use test profile with H2 databases
@EnableConfigurationProperties({
        LdapProperties.class,
        DatabaseProperties.class,
        RestProperties.class,
        SoapProperties.class,
        TestExecutorProperties.class
})
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        LdapAutoConfiguration.class
})
@TestPropertySource(locations = "classpath:application.yml")
@DisplayName("Configuration Properties Tests")
class ConfigurationTest {

    @Autowired(required = false)
    private LdapProperties ldapProperties;

    @Autowired(required = false)
    private DatabaseProperties databaseProperties;

    @Autowired(required = false)
    private RestProperties restProperties;

    @Autowired(required = false)
    private SoapProperties soapProperties;

    @Autowired(required = false)
    private TestExecutorProperties executorProperties;

    @Autowired
    private RestAuthenticator restAuthenticator;

    @Test
    @DisplayName("Should load LDAP configuration properties")
    void testLdapProperties() {
        assertThat(ldapProperties).isNotNull();
        assertThat(ldapProperties.getUrl()).isNotBlank();
        assertThat(ldapProperties.getBaseDn()).isNotBlank();
        assertThat(ldapProperties.getUserDn()).isNotBlank();

        System.out.println("LDAP Configuration:");
        System.out.println("  URL: " + ldapProperties.getUrl());
        System.out.println("  Base DN: " + ldapProperties.getBaseDn());
        System.out.println("  Pooled: " + ldapProperties.isPooled());
    }

    @Test
    @DisplayName("Should load Database configuration properties")
    void testDatabaseProperties() {
        assertThat(databaseProperties).isNotNull();
        assertThat(databaseProperties.getDatabases()).isNotEmpty();

        System.out.println("\nDatabase Configurations:");
        databaseProperties.getDatabases().forEach((name, config) -> {
            System.out.println("  " + name + ":");
            System.out.println("    JDBC URL: " + config.getJdbcUrl());
            System.out.println("    Driver: " + config.getDriverClassName());
            System.out.println("    Max Pool Size: " + config.getMaxPoolSize());

            assertThat(config.getJdbcUrl()).isNotBlank();
            assertThat(config.getDriverClassName()).isNotBlank();
        });
    }

    @Test
    @DisplayName("Should load REST API configuration properties")
    void testRestProperties() {
        assertThat(restProperties).isNotNull();
        assertThat(restProperties.getRest()).isNotEmpty();

        System.out.println("\nREST API Configurations:");
        restProperties.getRest().forEach((name, config) -> {
            System.out.println("  " + name + ":");
            System.out.println("    Base URL: " + config.getBaseUrl());
            System.out.println("    Connect Timeout: " + config.getConnectTimeout() + "ms");
            System.out.println("    Read Timeout: " + config.getReadTimeout() + "ms");

            assertThat(config.getBaseUrl()).isNotBlank();
            assertThat(config.getConnectTimeout()).isPositive();
            assertThat(config.getReadTimeout()).isPositive();
        });
    }

    @Test
    @DisplayName("Should load SOAP configuration properties")
    void testSoapProperties() {
        assertThat(soapProperties).isNotNull();
        assertThat(soapProperties.getUrl()).isNotBlank();

        System.out.println("\nSOAP Configuration:");
        System.out.println("  URL: " + soapProperties.getUrl());
        System.out.println("  Connection Timeout: " + soapProperties.getConnectionTimeout() + "ms");
        System.out.println("  Read Timeout: " + soapProperties.getReadTimeout() + "ms");
        System.out.println("  Message Logging: " + soapProperties.isMessageLogging());
    }

    @Test
    @DisplayName("Should load TestExecutor configuration properties")
    void testExecutorProperties() {
        assertThat(executorProperties).isNotNull();
        assertThat(executorProperties.getDefaultTimeoutSeconds()).isPositive();
        assertThat(executorProperties.getDefaultPollIntervalSeconds()).isPositive();
        assertThat(executorProperties.getMaxRetries()).isPositive();

        System.out.println("\nTestExecutor Configuration:");
        System.out.println("  Default Timeout: " + executorProperties.getDefaultTimeoutSeconds() + "s");
        System.out.println("  Poll Interval: " + executorProperties.getDefaultPollIntervalSeconds() + "s");
        System.out.println("  Max Retries: " + executorProperties.getMaxRetries());
        System.out.println("  LDAP Provision Timeout: " + executorProperties.getLdapProvisionTimeoutSeconds() + "s");
        System.out.println("  Database Provision Timeout: " + executorProperties.getDatabaseProvisionTimeoutSeconds() + "s");
        System.out.println("  REST Provision Timeout: " + executorProperties.getRestProvisionTimeoutSeconds() + "s");
    }

    @Test
    @DisplayName("Should have configuration for all required databases")
    void testRequiredDatabases() {
        assertThat(databaseProperties.getDatabases()).containsKeys("sap", "oracle-hr", "workday", "test-db");
    }

    @Test
    @DisplayName("Should have configuration for all required REST APIs")
    void testRequiredRestApis() {
        assertThat(restProperties.getRest()).containsKeys("idm", "sap", "workday", "servicenow");
    }

    @Test
    @DisplayName("The OAuth REST targets name their own client registration")
    void testOAuthTargetsWired() {
        for (String target : List.of("sap", "workday", "servicenow")) {
            RestProperties.RestApiConfig config = restProperties.getRest().get(target);
            assertThat(config.isUseOAuth()).as(target).isTrue();
            assertThat(config.getOauthClientName()).as(target).isEqualTo(target);
        }
    }

    @Test
    @DisplayName("OAuth targets reach Spring's token client in the application context")
    void testOAuthReachesSpringTokenClient() {
        OperationResult<String> result = restAuthenticator.authorization("sap", restProperties.getRest().get("sap"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage())
                .contains("'sap'")
                .doesNotContain("no OAuth2 client registrations")
                .doesNotContain("not configured");
    }
}
