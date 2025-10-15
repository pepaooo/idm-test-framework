package com.idm.test.core.exception;

import com.idm.test.core.model.ErrorType;

/**
 * Base exception for all IdM test framework exceptions.
 * Provides error categorization and context information.
 */
public class IdmTestException extends RuntimeException {

    private final ErrorType errorType;
    private final String context;

    public IdmTestException(String message) {
        this(message, ErrorType.UNKNOWN_ERROR);
    }

    public IdmTestException(String message, ErrorType errorType) {
        super(message);
        this.errorType = errorType;
        this.context = null;
    }

    public IdmTestException(String message, Throwable cause) {
        this(message, cause, ErrorType.UNKNOWN_ERROR);
    }

    public IdmTestException(String message, Throwable cause, ErrorType errorType) {
        super(message, cause);
        this.errorType = errorType;
        this.context = null;
    }

    public IdmTestException(String message, String context, ErrorType errorType) {
        super(message);
        this.errorType = errorType;
        this.context = context;
    }

    public IdmTestException(String message, Throwable cause, String context, ErrorType errorType) {
        super(message, cause);
        this.errorType = errorType;
        this.context = context;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public String getContext() {
        return context;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getClass().getSimpleName());
        sb.append("{errorType=").append(errorType);
        if (context != null) {
            sb.append(", context='").append(context).append('\'');
        }
        sb.append(", message='").append(getMessage()).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
