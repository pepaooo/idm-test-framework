package com.idm.test.engine;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.Message;

/**
 * A scenario could not be loaded or is not valid.
 * <p>
 * Always names the source file, and the step when one is implicated, because the reader is
 * often not a Java developer and cannot infer position from a stack trace.
 * <p>
 * The {@link Message} constructors keep the framed problem by key ({@link #detail()}), so it can be
 * shown in the run's language; {@link #getMessage()} is its English text.
 */
public class ValidationException extends RuntimeException {

    private final transient Message detail;

    public ValidationException(String sourceName, String message) {
        super(sourceName + ": " + message);
        this.detail = null;
    }

    public ValidationException(String sourceName, int stepIndex, String stepType, String message) {
        super(sourceName + ", step " + (stepIndex + 1) + " (" + stepType + "): " + message);
        this.detail = null;
    }

    public ValidationException(String sourceName, String message, Throwable cause) {
        super(sourceName + ": " + message, cause);
        this.detail = null;
    }

    public ValidationException(String sourceName, Message problem) {
        this(Message.of("validation.frame.file", sourceName, problem), (Throwable) null);
    }

    public ValidationException(String sourceName, int stepIndex, String stepType, Message problem) {
        this(Message.of("validation.frame.step", sourceName, stepIndex + 1, stepType, problem), (Throwable) null);
    }

    public ValidationException(String sourceName, Message problem, Throwable cause) {
        this(Message.of("validation.frame.file", sourceName, problem), cause);
    }

    private ValidationException(Message framed, Throwable cause) {
        super(Messages.english(framed), cause);
        this.detail = framed;
    }

    /** For a {@code cleanup:} entry: "{source}: cleanup entry N (type): message". */
    public static ValidationException forCleanup(String sourceName, int index, String stepType, String message) {
        return new ValidationException(sourceName, "cleanup entry " + (index + 1) + " (" + stepType + "): " + message);
    }

    /** As {@link #forCleanup(String, int, String, String)}, with the problem by key. */
    public static ValidationException forCleanup(String sourceName, int index, String stepType, Message problem) {
        return new ValidationException(
                Message.of("validation.frame.cleanup", sourceName, index + 1, stepType, problem), null);
    }

    /** The framed problem by key, or null when it was given as plain text. */
    public Message detail() {
        return detail;
    }
}
