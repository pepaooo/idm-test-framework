package com.idm.test.engine.spi;

/**
 * What happened when a step ran.
 *
 * @param success whether the step did what it was asked
 * @param message human-readable detail, shown in reports and failure messages
 * @param undo    how to reverse this step, or {@code null} when there is nothing to reverse.
 *                The engine collects these and unwinds them in reverse order.
 */
public record StepOutcome(boolean success, String message, Runnable undo) {

    public static StepOutcome success(String message) {
        return new StepOutcome(true, message, null);
    }

    public static StepOutcome success(String message, Runnable undo) {
        return new StepOutcome(true, message, undo);
    }

    public static StepOutcome failure(String message) {
        return new StepOutcome(false, message, null);
    }
}
