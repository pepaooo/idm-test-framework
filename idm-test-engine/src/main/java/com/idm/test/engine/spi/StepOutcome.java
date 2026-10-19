package com.idm.test.engine.spi;

import com.idm.test.core.model.ErrorType;
import com.idm.test.engine.i18n.Messages;

import java.util.List;

/**
 * What happened when a step ran.
 *
 * @param success whether the step did what it was asked
 * @param message human-readable detail, shown in reports and failure messages
 * @param value   what {@code as:} binds into the scenario's variables, or {@code null} when the
 *                step produces nothing worth capturing. Use plain values — String, Number,
 *                Boolean, Map or List — so a scenario can reach into them by path.
 * @param isFinal whether waiting cannot change this failure; always false on success
 * @param evidence what the step sent and received, or null (see Evidence)
 * @param detail  the message by key, so it can be shown in another language; null when the step
 *                built plain text. When set, {@code message} is its English rendering.
 * @param kind    whether a failure is a finding or a setup problem; always null on success. Left
 *                null on a failure, it is {@link FailureKind#SETUP} when final (waiting cannot change
 *                a malformed parameter or refused credentials) and {@link FailureKind#FINDING}
 *                otherwise (the step's own comparison failed). A step failing from a connector's
 *                result passes that result's {@link ErrorType} instead (see {@link FailureKind#of}).
 * @param checks  each comparison the step made, expected against found (see {@link Check}); empty
 *                when the step compared nothing, never null
 * <p>
 * A step never schedules teardown. Cleanup is written by the author in the scenario's
 * {@code cleanup:} block.
 */
public record StepOutcome(boolean success, String message, Object value, boolean isFinal, Evidence evidence,
                          Message detail, FailureKind kind, List<Check> checks) {

    public StepOutcome {
        if (success) {
            kind = null;
        } else if (kind == null) {
            kind = isFinal ? FailureKind.SETUP : FailureKind.FINDING;
        }
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    /** An outcome without checks. */
    public StepOutcome(boolean success, String message, Object value, boolean isFinal, Evidence evidence,
                       Message detail, FailureKind kind) {
        this(success, message, value, isFinal, evidence, detail, kind, null);
    }

    /** An outcome whose failure, if any, takes its default kind. */
    public StepOutcome(boolean success, String message, Object value, boolean isFinal, Evidence evidence,
                       Message detail) {
        this(success, message, value, isFinal, evidence, detail, null);
    }

    /** An outcome whose message is plain text. */
    public StepOutcome(boolean success, String message, Object value, boolean isFinal, Evidence evidence) {
        this(success, message, value, isFinal, evidence, null);
    }

    /** An outcome without evidence. */
    public StepOutcome(boolean success, String message, Object value, boolean isFinal) {
        this(success, message, value, isFinal, null);
    }

    /** An outcome that waiting may still change: every success, and an ordinary failure. */
    public StepOutcome(boolean success, String message, Object value) {
        this(success, message, value, false);
    }

    /** This outcome, carrying what the step sent and received. */
    public StepOutcome withEvidence(Evidence evidence) {
        return new StepOutcome(success, message, value, isFinal, evidence, detail, kind, checks);
    }

    /** This outcome, carrying the comparisons the step made. */
    public StepOutcome withChecks(List<Check> checks) {
        return new StepOutcome(success, message, value, isFinal, evidence, detail, kind, checks);
    }

    public static StepOutcome success(Message detail) {
        return success(detail, null);
    }

    /** @param value what {@code as:} binds; may be null */
    public static StepOutcome success(Message detail, Object value) {
        return new StepOutcome(true, Messages.english(detail), value, false, null, detail);
    }

    /** A failure from the step's own comparison: a {@link FailureKind#FINDING}. */
    public static StepOutcome failure(Message detail) {
        return new StepOutcome(false, Messages.english(detail), null, false, null, detail);
    }

    /**
     * A failure from a connector's result, its kind from that result's {@code type}
     * (see {@link FailureKind#of}). Waiting may still change it.
     */
    public static StepOutcome failure(Message detail, ErrorType type) {
        return new StepOutcome(false, Messages.english(detail), null, false, null, detail, FailureKind.of(type));
    }

    /** A failure that waiting cannot change; see {@link #finalFailure(String)}. */
    public static StepOutcome finalFailure(Message detail) {
        return new StepOutcome(false, Messages.english(detail), null, true, null, detail);
    }

    public static StepOutcome success(String message) {
        return new StepOutcome(true, message, null);
    }

    /** @param value what {@code as:} binds; may be null */
    public static StepOutcome success(String message, Object value) {
        return new StepOutcome(true, message, value);
    }

    /** A failure from the step's own comparison: a {@link FailureKind#FINDING}. */
    public static StepOutcome failure(String message) {
        return new StepOutcome(false, message, null);
    }

    /** A failure from a connector's result; see {@link #failure(Message, ErrorType)}. */
    public static StepOutcome failure(String message, ErrorType type) {
        return new StepOutcome(false, message, null, false, null, null, FailureKind.of(type));
    }

    /**
     * A failure that waiting cannot change: a malformed parameter, an unknown target or
     * database, rejected or misconfigured credentials. Under {@code wait:} the engine stops at
     * once instead of polling until the timeout. Always a {@link FailureKind#SETUP} problem.
     */
    public static StepOutcome finalFailure(String message) {
        return new StepOutcome(false, message, null, true);
    }
}
