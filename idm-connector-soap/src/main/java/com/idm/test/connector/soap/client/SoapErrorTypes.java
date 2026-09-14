package com.idm.test.connector.soap.client;

import com.idm.test.core.model.ErrorType;

/**
 * Maps exceptions thrown by SOAP calls onto {@link ErrorType}.
 * <p>
 * Shared by the SOAP connectors so the classification does not drift between them.
 */
final class SoapErrorTypes {

    private SoapErrorTypes() {
    }

    static ErrorType classify(Exception e) {
        String message = e.getMessage();

        if (message != null) {
            if (message.contains("timeout") || message.contains("timed out")) {
                return ErrorType.TIMEOUT;
            } else if (message.contains("Connection") || message.contains("Network")) {
                return ErrorType.NETWORK_ERROR;
            } else if (message.contains("Authentication") || message.contains("Unauthorized")) {
                return ErrorType.AUTHENTICATION_ERROR;
            }
        }

        return ErrorType.UNKNOWN_ERROR;
    }
}
