package com.idm.test.engine.spi;

/**
 * What happened when a step ran.
 *
 * @param success whether the step did what it was asked
 * @param message human-readable detail, shown in reports and failure messages
 * @param value   what {@code as:} binds into the scenario's variables, or {@code null} when the
 *                step produces nothing worth capturing. Use plain values — String, Number,
 *                Boolean, Map or List — so a scenario can reach into them by path.
 * <p>
 * A step never schedules teardown. Cleanup is written by the author in the scenario's
 * {@code cleanup:} block.
 */
public record StepOutcome(boolean success, String message, Object value) {

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
}
