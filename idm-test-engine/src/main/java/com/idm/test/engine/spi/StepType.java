package com.idm.test.engine.spi;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
     * @return parameter names this step cannot run without. {@code ScenarioValidator} rejects
     * a scenario missing any of these before anything executes, so a typo'd or omitted
     * parameter is caught up front instead of silently defaulting away at execute() time.
     */
    default Set<String> requiredParams() {
        return Set.of();
    }

    /**
     * @return every parameter name this step accepts, used by {@code ScenarioValidator} to
     * reject unknown keys (typos) instead of silently dropping them.
     * <p>
     * Defaults to {@link #requiredParams()} plus {@code wait} when {@link #retryable()} is
     * true — {@code wait} is only ever meaningful on a retryable step, and never on a
     * non-retryable one, so the default keeps that rule in one place. Override when the step
     * also has optional parameters beyond what it requires.
     */
    default Set<String> acceptedParams() {
        Set<String> accepted = new LinkedHashSet<>(requiredParams());
        if (retryable()) {
            accepted.add("wait");
        }
        return accepted;
    }

    /**
     * Runs the step. Must not throw: a failure is an outcome, not an exception.
     */
    StepOutcome execute(Map<String, Object> params);
}
