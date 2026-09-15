package com.idm.test.engine.spi;

/**
 * What happened when a step ran.
 *
 * @param success whether the step did what it was asked
 * @param message human-readable detail, shown in reports and failure messages
 * @param undo    how to reverse this step, or {@code null} when there is nothing to reverse.
 *                The engine collects these and unwinds them in reverse order.
 * @param value   what {@code as:} binds into the scenario's variables, or {@code null} when the
 *                step produces nothing worth capturing. Use plain values — String, Number,
 *                Boolean, Map or List — so a scenario can reach into them by path.
 */
public record StepOutcome(boolean success, String message, Runnable undo, Object value) {

    public static StepOutcome success(String message) {
        return new StepOutcome(true, message, null, null);
    }

    public static StepOutcome success(String message, Runnable undo) {
        return new StepOutcome(true, message, undo, null);
    }

    /**
     * @param undo  may be null when there is nothing to reverse
     * @param value what {@code as:} binds; may be null
     */
    public static StepOutcome success(String message, Runnable undo, Object value) {
        return new StepOutcome(true, message, undo, value);
    }

    public static StepOutcome failure(String message) {
        return new StepOutcome(false, message, null, null);
    }
}
