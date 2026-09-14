package com.idm.test.engine;

/**
 * A scenario could not be loaded or is not valid.
 * <p>
 * Always names the source file, and the step when one is implicated, because the reader is
 * often not a Java developer and cannot infer position from a stack trace.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String sourceName, String message) {
        super(sourceName + ": " + message);
    }

    public ValidationException(String sourceName, int stepIndex, String stepType, String message) {
        super(sourceName + ", step " + (stepIndex + 1) + " (" + stepType + "): " + message);
    }

    public ValidationException(String sourceName, String message, Throwable cause) {
        super(sourceName + ": " + message, cause);
    }
}
