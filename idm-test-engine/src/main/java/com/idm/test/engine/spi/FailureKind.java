package com.idm.test.engine.spi;

import com.idm.test.core.model.ErrorType;

/**
 * Which of two kinds a failure is, so a report, the console and the workbench can tell them apart.
 * <ul>
 *   <li>{@link #FINDING}: the system answered, just not as the scenario expects — a check read back
 *       another value, or the system rejected or failed an action. The lab did not do what the step
 *       expects.</li>
 *   <li>{@link #SETUP}: the scenario or its setup needs fixing — a malformed parameter, an unknown
 *       target, a system that could not be reached, credentials it refused, an unexpected exception.</li>
 * </ul>
 */
public enum FailureKind {
    FINDING,
    SETUP;

    /**
     * The kind of a failure a connector reported: unreachable, timed out, refused credentials or
     * misconfigured is {@link #SETUP}; anything else means the system answered, a {@link #FINDING}.
     * A connector that named no type ({@code null}) counts as {@link ErrorType#UNKNOWN_ERROR}.
     */
    public static FailureKind of(ErrorType type) {
        if (type == null) {
            return FINDING;
        }
        return switch (type) {
            case NETWORK_ERROR, TIMEOUT, AUTHENTICATION_ERROR, CONFIGURATION_ERROR -> SETUP;
            case NOT_FOUND, VALIDATION_ERROR, SERVER_ERROR, DATABASE_ERROR, LDAP_ERROR, UNKNOWN_ERROR -> FINDING;
        };
    }
}
