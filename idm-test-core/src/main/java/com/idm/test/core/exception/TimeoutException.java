package com.idm.test.core.exception;

import com.idm.test.core.model.ErrorType;

/**
 * Exception thrown when an operation times out.
 */
public class TimeoutException extends IdmTestException {

    private final long timeoutMillis;

    public TimeoutException(String message, long timeoutMillis) {
        super(message, ErrorType.TIMEOUT);
        this.timeoutMillis = timeoutMillis;
    }

    public TimeoutException(String message, long timeoutMillis, Throwable cause) {
        super(message, cause, ErrorType.TIMEOUT);
        this.timeoutMillis = timeoutMillis;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    @Override
    public String toString() {
        return super.toString() + ", timeoutMillis=" + timeoutMillis;
    }
}
