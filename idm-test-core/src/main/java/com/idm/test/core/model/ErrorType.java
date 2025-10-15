package com.idm.test.core.model;

/**
 * Categorizes different types of errors that can occur during test operations.
 * This helps with error handling, retry logic, and debugging.
 */
public enum ErrorType {

    /**
     * Network connectivity issues (DNS, connection refused, timeout)
     */
    NETWORK_ERROR,

    /**
     * Authentication or authorization failures
     */
    AUTHENTICATION_ERROR,

    /**
     * Resource not found (404, missing LDAP entry, etc.)
     */
    NOT_FOUND,

    /**
     * Invalid data or validation failures
     */
    VALIDATION_ERROR,

    /**
     * Operation timeout
     */
    TIMEOUT,

    /**
     * Server error (5xx responses)
     */
    SERVER_ERROR,

    /**
     * Database-specific errors
     */
    DATABASE_ERROR,

    /**
     * LDAP-specific errors
     */
    LDAP_ERROR,

    /**
     * Configuration errors
     */
    CONFIGURATION_ERROR,

    /**
     * Unknown or unexpected errors
     */
    UNKNOWN_ERROR;

    /**
     * Determines if this error type is typically transient and retryable.
     *
     * @return true if the operation should be retried
     */
    public boolean isRetryable() {
        return switch (this) {
            case NETWORK_ERROR, TIMEOUT, SERVER_ERROR -> true;
            case AUTHENTICATION_ERROR, NOT_FOUND, VALIDATION_ERROR,
                 DATABASE_ERROR, LDAP_ERROR, CONFIGURATION_ERROR, UNKNOWN_ERROR -> false;
        };
    }

    /**
     * Determines if this error is likely due to a temporary condition.
     *
     * @return true if the error is transient
     */
    public boolean isTransient() {
        return switch (this) {
            case NETWORK_ERROR, TIMEOUT, SERVER_ERROR -> true;
            default -> false;
        };
    }
}
