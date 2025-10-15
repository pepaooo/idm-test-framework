package com.idm.test.core.exception;

import com.idm.test.core.model.ErrorType;

/**
 * Exception thrown when connection to external system fails.
 */
public class ConnectionException extends IdmTestException {

    public ConnectionException(String message) {
        super(message, ErrorType.NETWORK_ERROR);
    }

    public ConnectionException(String message, Throwable cause) {
        super(message, cause, ErrorType.NETWORK_ERROR);
    }

    public ConnectionException(String message, String context) {
        super(message, context, ErrorType.NETWORK_ERROR);
    }
}
