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
 * <p>
 * The engine strips the two reserved keys, {@code as} and {@code wait}, out of the parameter
 * map before a step ever sees it: neither is ever present in {@link #execute}'s {@code params}.
 * {@code wait} is retry policy, decided entirely by the engine. {@code as} is the name under
 * which the engine will bind {@link StepOutcome#value()} into scenario scope — a step is told
 * only <em>whether</em> a capture was requested (see {@link #execute(Map, boolean)}), never
 * what it will be called, so a step can never choose or influence its own variable name.
 * <p>
 * <b>The {@code value()} contract:</b> {@link StepOutcome#value()} is what {@code as:} binds.
 * Returning {@code null} there is only correct when nothing was requested to capture — the
 * engine treats a null value from a step whose {@code as:} name was declared as a bug in that
 * step and fails the scenario rather than binding the literal text {@code "null"} into scope.
 * A step that computes something worth capturing only when asked (see {@code LdapVerifyStep}
 * for the canonical example, which skips a follow-up read entirely when no capture was
 * requested) should look at {@code captureRequested} before doing that extra work, and must
 * still return a non-null value once it does.
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
     * <p>
     * <b>An override need not (and should not try to) repeat {@link #requiredParams()} or
     * {@code wait}.</b> {@code ScenarioValidator} never trusts this method alone: it always
     * treats every name in {@link #requiredParams()}, and {@code wait} when
     * {@link #retryable()} is true, as accepted regardless of what an override returns here.
     * That means an override that lists only its own extra optional parameters (as
     * {@code LdapCreateStep} does with a hardcoded {@code Set.of(...)}) cannot accidentally
     * cause a required parameter, or {@code wait} on a retryable step, to be rejected as
     * unknown.
     */
    default Set<String> acceptedParams() {
        Set<String> accepted = new LinkedHashSet<>(requiredParams());
        if (retryable()) {
            accepted.add("wait");
        }
        return accepted;
    }

    /**
     * Parameters the engine must not resolve before {@link #execute}: each arrives as a
     * {@link DeferredExpression} (or a list of them) that the step evaluates itself. Each value
     * must be a whole-value {@code js:{ … }} expression, which the validator enforces.
     */
    default Set<String> deferredParams() {
        return Set.of();
    }

    /**
     * Names the step binds when it evaluates its deferred parameters (for example
     * {@code response}). The validator treats them as known inside those parameters only.
     */
    default Set<String> deferredBindings() {
        return Set.of();
    }

    /**
     * Runs the step.
     * <p>
     * {@code params} arrives already resolved: {@code ${...}} interpolation has run, and the
     * two engine-level keys, {@code as} and {@code wait}, are stripped before this is called —
     * neither is ever present in {@code params}.
     * <p>
     * Must never throw: a failure is a returned {@link StepOutcome}, not an exception. The
     * engine treats any thrown exception as a bug in the step, not a normal failure path.
     *
     * @param params            already-interpolated parameters, with {@code as} and
     *                          {@code wait} stripped (see the interface javadoc)
     * @param captureRequested  true when the scenario declared an {@code as:} name for this
     *                          step, so the returned {@link StepOutcome#value()} will actually
     *                          be bound into scenario scope. A step whose work only exists to
     *                          produce a capture value (for example a follow-up read after an
     *                          assertion passes) should check this flag and skip that work when
     *                          it is false — see {@code LdapVerifyStep} for the canonical
     *                          example. A step that always produces its value cheaply may
     *                          ignore the flag entirely, as {@code LdapCreateStep} does.
     * @return what happened; must not be null, and must not throw. Returning a {@code null}
     * {@link StepOutcome#value()} while {@code captureRequested} is true fails the scenario —
     * the engine treats it as a bug in the step rather than binding the literal text
     * {@code "null"} into scope. A step whose value can be legitimately absent must return a
     * non-null sentinel instead of null in that case.
     */
    StepOutcome execute(Map<String, Object> params, boolean captureRequested);
}
