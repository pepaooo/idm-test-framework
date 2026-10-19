package com.idm.test.engine.spi;

import com.idm.test.engine.result.CleanupResult;
import com.idm.test.engine.result.StepResult;

/**
 * Hears a scenario's progress as it runs, so a UI can show it live: each step starting and
 * finishing, each try of a step under {@code wait:}, and each cleanup entry that ran.
 * <p>
 * Called on the thread running the scenario, in order. Implementations must be quick and must not
 * throw: what they do cannot change what the scenario does. Every method does nothing by default.
 */
public interface RunListener {

    /** A step is about to run; {@code description} is its own description, resolved, or null. */
    default void stepStarted(int index, String type, String description) {
    }

    /**
     * One try of a step under {@code wait:} ended.
     *
     * @param attempt       the try's number, from 1
     * @param elapsedMillis time since the wait began
     * @param message       what the try observed (its outcome's message), or null
     */
    default void attempt(int index, int attempt, long elapsedMillis, String message) {
    }

    /**
     * A step ended, passed, failed or stopped. A step a stop prevented from running comes here
     * without {@link #stepStarted} first.
     */
    default void stepFinished(StepResult result) {
    }

    /** A cleanup entry ran (or was skipped because what it tears down never happened). */
    default void cleanupFinished(CleanupResult result) {
    }

    /** Hears nothing. */
    RunListener NONE = new RunListener() {
    };
}
