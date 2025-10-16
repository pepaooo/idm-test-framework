package com.idm.test.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for TestExecutor behavior.
 * Controls timeouts, retry policies, and async operation settings.
 * <p>
 * Example configuration in application.yml:
 * <pre>
 * idm:
 *   test:
 *     executor:
 *       default-timeout-seconds: 300
 *       default-poll-interval-seconds: 5
 *       max-retries: 3
 *       retry-delay-ms: 1000
 *       ldap-provision-timeout-seconds: 120
 *       database-provision-timeout-seconds: 180
 *       rest-provision-timeout-seconds: 300
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
     * Maximum number of retries for failed operations.
     */
    private int maxRetries = 3;

    /**
     * Delay between retries (in milliseconds).
     */
    private long retryDelayMs = 1000;

    /**
     * Timeout for LDAP provisioning operations (in seconds).
     */
    private int ldapProvisionTimeoutSeconds = 120; // 2 minutes

    /**
     * Timeout for database provisioning operations (in seconds).
     */
    private int databaseProvisionTimeoutSeconds = 180; // 3 minutes

    /**
     * Timeout for REST API provisioning operations (in seconds).
     */
    private int restProvisionTimeoutSeconds = 300; // 5 minutes

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

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public Duration getRetryDelay() {
        return Duration.ofMillis(retryDelayMs);
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

    public int getRestProvisionTimeoutSeconds() {
        return restProvisionTimeoutSeconds;
    }

    public void setRestProvisionTimeoutSeconds(int restProvisionTimeoutSeconds) {
        this.restProvisionTimeoutSeconds = restProvisionTimeoutSeconds;
    }

    public Duration getRestProvisionTimeout() {
        return Duration.ofSeconds(restProvisionTimeoutSeconds);
    }
}
