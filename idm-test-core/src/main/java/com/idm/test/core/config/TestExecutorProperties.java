package com.idm.test.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for TestExecutor behavior.
 * Controls the wait timeouts and poll interval for async operations.
 * <p>
 * Example configuration in application.yml:
 * <pre>
 * idm:
 *   test:
 *     executor:
 *       default-timeout-seconds: 300
 *       default-poll-interval-seconds: 5
 *       ldap-provision-timeout-seconds: 120
 *       database-provision-timeout-seconds: 180
 * </pre>
 */
@ConfigurationProperties(prefix = "idm.test.executor")
public class TestExecutorProperties {

    /**
     * Default timeout for waitUntil operations (in seconds).
     */
    private int defaultTimeoutSeconds = 300; // 5 minutes

    /**
     * Default poll interval for waitUntil operations (in seconds).
     */
    private int defaultPollIntervalSeconds = 5;

    /**
     * Timeout for LDAP provisioning operations (in seconds).
     */
    private int ldapProvisionTimeoutSeconds = 120; // 2 minutes

    /**
     * Timeout for database provisioning operations (in seconds).
     */
    private int databaseProvisionTimeoutSeconds = 180; // 3 minutes

    // Getters and setters

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public Duration getDefaultTimeout() {
        return Duration.ofSeconds(defaultTimeoutSeconds);
    }

    public int getDefaultPollIntervalSeconds() {
        return defaultPollIntervalSeconds;
    }

    public void setDefaultPollIntervalSeconds(int defaultPollIntervalSeconds) {
        this.defaultPollIntervalSeconds = defaultPollIntervalSeconds;
    }

    public Duration getDefaultPollInterval() {
        return Duration.ofSeconds(defaultPollIntervalSeconds);
    }

    public int getLdapProvisionTimeoutSeconds() {
        return ldapProvisionTimeoutSeconds;
    }

    public void setLdapProvisionTimeoutSeconds(int ldapProvisionTimeoutSeconds) {
        this.ldapProvisionTimeoutSeconds = ldapProvisionTimeoutSeconds;
    }

    public Duration getLdapProvisionTimeout() {
        return Duration.ofSeconds(ldapProvisionTimeoutSeconds);
    }

    public int getDatabaseProvisionTimeoutSeconds() {
        return databaseProvisionTimeoutSeconds;
    }

    public void setDatabaseProvisionTimeoutSeconds(int databaseProvisionTimeoutSeconds) {
        this.databaseProvisionTimeoutSeconds = databaseProvisionTimeoutSeconds;
    }

    public Duration getDatabaseProvisionTimeout() {
        return Duration.ofSeconds(databaseProvisionTimeoutSeconds);
    }
}
