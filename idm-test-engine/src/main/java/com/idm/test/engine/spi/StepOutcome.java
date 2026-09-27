package com.idm.test.engine.spi;

/**
 * What happened when a step ran.
 *
 * @param success whether the step did what it was asked
 * @param message human-readable detail, shown in reports and failure messages
 * @param value   what {@code as:} binds into the scenario's variables, or {@code null} when the
 *                step produces nothing worth capturing. Use plain values — String, Number,
 *                Boolean, Map or List — so a scenario can reach into them by path.
 * @param isFinal whether waiting cannot change this failure; always false on success
 * <p>
 * A step never schedules teardown. Cleanup is written by the author in the scenario's
 * {@code cleanup:} block.
 */
public record StepOutcome(boolean success, String message, Object value, boolean isFinal) {

    /** An outcome that waiting may still change: every success, and an ordinary failure. */
    public StepOutcome(boolean success, String message, Object value) {
        this(success, message, value, false);
    }

    public static StepOutcome success(String message) {
        return new StepOutcome(true, message, null);
    }

    /** @param value what {@code as:} binds; may be null */
    public static StepOutcome success(String message, Object value) {
        return new StepOutcome(true, message, value);
    }

    public static StepOutcome failure(String message) {
        return new StepOutcome(false, message, null);
    }

    /**
     * A failure that waiting cannot change: a malformed parameter, an unknown target or
     * database, rejected or misconfigured credentials. Under {@code wait:} the engine stops at
     * once instead of polling until the timeout.
     */
    public static StepOutcome finalFailure(String message) {
        return new StepOutcome(false, message, null, true);
    }
}
