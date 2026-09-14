package com.idm.test.engine.spi;

import java.util.Map;

/**
 * One kind of step a scenario can contain.
 * <p>
 * Implementations receive parameters that are <em>already interpolated</em> — the engine
 * resolves {@code ${...}} before calling — and never handle retrying. Retrying belongs to
 * the engine so the policy lives in one place and steps stay trivially testable.
 */
public interface StepType {

    /**
     * @return the name used in scenario files, for example "ldap.create"
     */
    String name();

    /**
     * @return true when this step is an assertion the engine may retry under a {@code wait}
     */
    default boolean retryable() {
        return false;
    }

    /**
     * Runs the step. Must not throw: a failure is an outcome, not an exception.
     */
    StepOutcome execute(Map<String, Object> params);
}
