package com.idm.test.engine.result;

/** How one {@code cleanup:} entry ended. */
public enum CleanupStatus {
    /** The action ran and succeeded. */
    SUCCEEDED,
    /** The action ran and failed, or its parameters could not be resolved. Fails the scenario. */
    FAILED,
    /** It references a name that was never bound, so what it would tear down never happened. */
    SKIPPED
}
