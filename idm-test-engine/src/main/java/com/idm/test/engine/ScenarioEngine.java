package com.idm.test.engine;

import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.model.WaitSpec;
import com.idm.test.engine.result.CleanupResult;
import com.idm.test.engine.result.CleanupStatus;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.DeferredExpression;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a scenario: interpolate each step's parameters, execute in order, retry assertions that
 * declare a wait, stop at the first failure, then run the author's cleanup block.
 * <p>
 * Deliberately knows nothing about Spring, JUnit, files or output formats. It takes its
 * dependencies by constructor and returns a result object. That is what lets a CLI reuse it.
 */
public final class ScenarioEngine {

    /** Reserved: handled here rather than by a registered step type. */
    private static final String SET_STEP = "set";

    private static final String DESCRIPTION = "description";

    private static final String NOT_RETRIED = " (not retried: waiting cannot change this)";

    private final Map<String, StepType> registry;
    private final FixtureFactory fixtures;
    private final TestExecutor executor;
    private final Duration defaultTimeout;
    private final Duration defaultPoll;
    private final ExpressionEvaluator evaluator;

    public ScenarioEngine(
            Map<String, StepType> registry,
            FixtureFactory fixtures,
            TestExecutor executor,
            Duration defaultTimeout,
            Duration defaultPoll,
            ExpressionEvaluator evaluator) {
        this.registry = registry;
        this.fixtures = fixtures;
        this.executor = executor;
        this.defaultTimeout = defaultTimeout;
        this.defaultPoll = defaultPoll;
        this.evaluator = evaluator;
    }

    public ScenarioResult run(Scenario scenario, String sourceName) {
        long started = System.currentTimeMillis();
        Map<String, Object> scope = new LinkedHashMap<>();

        List<StepResult> results = new ArrayList<>();
        List<CleanupResult> cleanupResults = List.of();
        boolean success = true;
        String failureDetail = null;

        try {
            // ScenarioValidator does validate fixture generator names on the path that calls
            // it before run() — see ScenarioValidator.validate(). This guard is defence for
            // callers that reach run() without validating first (a future CLI, say), so a
            // bad generator name still surfaces here rather than throwing. It must stay
            // inside this protected region so run() still returns a ScenarioResult — with
            // zero steps, since none can run without fixtures — instead of throwing.
            try {
                scope.putAll(fixtures.build(scenario.fixtures()));
            } catch (RuntimeException e) {
                success = false;
                failureDetail = "fixture setup failed: " + e.getMessage();
            }

            for (int i = 0; i < scenario.steps().size() && success; i++) {
                StepSpec step = scenario.steps().get(i);
                long stepStarted = System.currentTimeMillis();
                StepResult result;
                try {
                    result = runStep(step, i, scope);
                } catch (RuntimeException e) {
                    result = new StepResult(i, step.type(), Map.of(), false,
                            System.currentTimeMillis() - stepStarted,
                            "step '" + step.type() + "' threw " + e.getClass().getSimpleName()
                                    + ": " + e.getMessage(),
                            null, null, describe(step, scope));
                }
                results.add(result);
                success = result.success();
            }
        } finally {
            // The author's cleanup always runs, whether or not the steps passed.
            cleanupResults = runCleanup(scenario.cleanup(), scope);
        }

        boolean cleanupFailed = cleanupResults.stream()
                .anyMatch(entry -> entry.status() == CleanupStatus.FAILED);

        return new ScenarioResult(scenario.name(), sourceName, success && !cleanupFailed,
                System.currentTimeMillis() - started, results, cleanupResults, failureDetail,
                scenario.description());
    }

    @SuppressWarnings("unchecked")
    private StepResult runStep(StepSpec step, int index, Map<String, Object> scope) {

        long started = System.currentTimeMillis();

        String description;
        try {
            description = resolvedDescription(step, scope);
        } catch (RuntimeException e) {
            return new StepResult(index, step.type(), Map.of(), false,
                    System.currentTimeMillis() - started,
                    "could not resolve 'description': " + e.getMessage(), null, null, writtenDescription(step));
        }

        if (SET_STEP.equals(step.type())) {
            return runSet(step, index, scope, started, description);
        }

        StepType type = registry.get(step.type());

        if (type == null) {
            return new StepResult(index, step.type(), Map.of(), false,
                    System.currentTimeMillis() - started,
                    "unknown step type '" + step.type() + "'. Available: "
                            + new TreeSet<>(registry.keySet()),
                    null, null, description);
        }

        // Deferred parameters are left for the step to evaluate itself (see
        // StepType.deferredParams); everything else is resolved once, as always.
        Map<String, Object> immediate = withoutDescription(step.params());
        Map<String, Object> deferred = new LinkedHashMap<>();
        for (String name : type.deferredParams()) {
            if (immediate.containsKey(name)) {
                deferred.put(name, immediate.remove(name));
            }
        }
        Map<String, Object> resolved = (Map<String, Object>) Interpolator.resolve(immediate, scope, evaluator);
        deferred.forEach((name, value) -> resolved.put(name, defer(value, scope)));

        WaitSpec wait = WaitSpec.parse(resolved.remove("wait"), defaultTimeout, defaultPoll);
        Object asName = resolved.remove("as");

        if (wait != null && !type.retryable()) {
            return new StepResult(index, step.type(), reportable(resolved), false,
                    System.currentTimeMillis() - started,
                    "'wait' is only valid on assertions, and step '" + step.type()
                            + "' is not retryable",
                    null, null, description);
        }

        boolean captureRequested = asName != null;

        if (wait == null) {
            StepOutcome outcome = type.execute(resolved, captureRequested);
            outcome = bind(scope, asName, outcome, step.type());
            return new StepResult(index, step.type(), reportable(resolved), outcome.success(),
                    System.currentTimeMillis() - started, outcome.message(), null, null, description);
        }

        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<StepOutcome> last = new AtomicReference<>();

        long waitStarted = System.currentTimeMillis();
        // True when the assertion passed or a final failure ended the wait early; either way
        // waitUntil's loop condition was satisfied. Success itself always comes from `outcome`.
        boolean stopped = executor.waitUntil(() -> {
            attempts.incrementAndGet();
            StepOutcome outcome;
            try {
                outcome = type.execute(resolved, captureRequested);
            } catch (RuntimeException e) {
                // Record the failure and keep polling — TestExecutor.waitUntil already
                // swallows exceptions from this supplier, but without this the "last"
                // outcome it reports on would be stale or absent rather than reflecting
                // what actually happened on this attempt. Mirror the outer catch's format
                // so the exception's class name is always present — otherwise a null
                // message surfaces as the bare literal "null".
                outcome = StepOutcome.failure(
                        "threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            last.set(outcome);
            // A final failure ends the wait: polling it again cannot change the answer.
            return outcome.success() || outcome.isFinal();
        }, wait.timeout(), wait.poll());
        long waitedMillis = System.currentTimeMillis() - waitStarted;

        StepOutcome outcome = bind(scope, asName, last.get(), step.type());
        boolean success = outcome != null && outcome.success();

        long elapsed = System.currentTimeMillis() - started;
        String message;
        if (outcome == null) {
            // The supplier above always sets `last` before returning, so this only happens
            // when waitUntil's loop condition never even entered — the timeout was already
            // spent. A null outcome here really does mean the condition never ran.
            message = "wait timed out before the assertion ran once";
        } else if (outcome.isFinal()) {
            message = outcome.message() + NOT_RETRIED;
        } else if (stopped) {
            // Covers both the ordinary passing case and the one bind() just downgraded: the
            // assertion itself passed on this attempt, so its own message (success or, on a
            // missing capture, bind()'s failure message) is what belongs here — never the
            // "last observed value after retrying" suffix, which only makes sense when the
            // assertion itself never passed.
            message = outcome.message();
        } else {
            message = outcome.message() + " (last observed value after retrying)";
        }

        return new StepResult(index, step.type(), reportable(resolved), success, elapsed, message,
                attempts.get(), waitedMillis, description);
    }

    private Object defer(Object value, Map<String, Object> scope) {
        if (value instanceof List<?> list) {
            List<Object> expressions = new ArrayList<>(list.size());
            list.forEach(item -> expressions.add(defer(item, scope)));
            return expressions;
        }
        String text = String.valueOf(value);
        String body = Interpolator.expressionBody(text);
        if (body == null) {
            // The validator rejects this at load; reaching it means validation was skipped.
            throw new IllegalArgumentException("a deferred parameter must be a js:{ … } expression, got '" + text + "'");
        }
        return new ScopedExpression(text, body, scope, evaluator);
    }

    /** Resolved parameters as the results record them: deferred expressions as their source text. */
    private static Map<String, Object> reportable(Map<String, Object> resolved) {
        Map<String, Object> report = new LinkedHashMap<>();
        resolved.forEach((name, value) -> report.put(name, reportableValue(value)));
        return report;
    }

    private static Object reportableValue(Object value) {
        if (value instanceof DeferredExpression expression) {
            return expression.source();
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof DeferredExpression) {
            return list.stream().map(ScenarioEngine::reportableValue).toList();
        }
        return value;
    }

    /**
     * Binds each entry into the scenario's variables, in declaration order so a later entry may
     * read one bound earlier in the same step.
     */
    private StepResult runSet(StepSpec step, int index, Map<String, Object> scope, long started,
                              String description) {
        Map<String, Object> bound = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : withoutDescription(step.params()).entrySet()) {
            Object value = Interpolator.resolve(entry.getValue(), scope, evaluator);
            scope.put(entry.getKey(), value);
            bound.put(entry.getKey(), value);
        }

        return new StepResult(index, SET_STEP, bound, true,
                System.currentTimeMillis() - started, "bound " + bound.keySet(), null, null, description);
    }

    /**
     * Binds a successful step's value under its {@code as:} name, so later steps can read it.
     * <p>
     * A scenario must never report success on a fabricated value. {@link StepOutcome#value()}
     * defaults to {@code null} — that is the shape {@code StepOutcome.success(msg)} produces,
     * and every future step type gets it for free unless it
     * opts into capturing something. Binding a null value anyway would let {@code containsKey}
     * report the name as known while {@code String.valueOf(null)} renders the literal text
     * {@code "null"} into scope, which the interpolator would then hand to a later step as if it
     * were real data. So a null value where a capture was requested is treated as the step's own
     * bug: this downgrades the outcome to a failure instead, naming the step type and the as:
     * name, and the scenario goes red rather than green.
     *
     * @return {@code outcome} unchanged when there is nothing to bind or it was already a
     * failure; a failure outcome when {@code as:} was declared but {@code outcome.value()} was
     * null; otherwise {@code outcome} unchanged, after binding its value into {@code scope}
     */
    private static StepOutcome bind(
            Map<String, Object> scope, Object asName, StepOutcome outcome, String stepType) {
        if (asName == null || outcome == null || !outcome.success()) {
            return outcome;
        }
        if (outcome.value() == null) {
            return StepOutcome.failure("step '" + stepType + "' declares as: '" + asName
                    + "' but produced no value to capture");
        }
        scope.put(String.valueOf(asName), outcome.value());
        return outcome;
    }

    /**
     * Runs every cleanup entry in order, whatever happened before. An entry that references a
     * name never bound is SKIPPED: what it would tear down never happened. Never throws.
     */
    private List<CleanupResult> runCleanup(List<StepSpec> cleanup, Map<String, Object> scope) {
        List<CleanupResult> results = new ArrayList<>();
        for (int i = 0; i < cleanup.size(); i++) {
            StepSpec entry = cleanup.get(i);
            long started = System.currentTimeMillis();
            CleanupResult result;
            try {
                result = runCleanupEntry(entry, i, scope, started);
            } catch (RuntimeException e) {
                result = new CleanupResult(i, entry.type(), Map.of(), CleanupStatus.FAILED,
                        System.currentTimeMillis() - started,
                        "cleanup '" + entry.type() + "' threw " + e.getClass().getSimpleName()
                                + ": " + e.getMessage(),
                        writtenDescription(entry));
            }
            results.add(result);
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private CleanupResult runCleanupEntry(
            StepSpec entry, int index, Map<String, Object> scope, long started) {

        Set<String> unbound = new TreeSet<>(Interpolator.references(withoutDescription(entry.params())));
        unbound.removeAll(scope.keySet());
        if (!unbound.isEmpty()) {
            return new CleanupResult(index, entry.type(), Map.of(), CleanupStatus.SKIPPED,
                    System.currentTimeMillis() - started,
                    "skipped: " + unbound + " never bound, so what this would clean up never happened",
                    writtenDescription(entry));
        }

        String description = describe(entry, scope);

        StepType type = registry.get(entry.type());
        if (type == null) {
            return new CleanupResult(index, entry.type(), Map.of(), CleanupStatus.FAILED,
                    System.currentTimeMillis() - started,
                    "unknown step type '" + entry.type() + "'. Available: " + new TreeSet<>(registry.keySet()),
                    description);
        }

        Map<String, Object> resolved =
                (Map<String, Object>) Interpolator.resolve(withoutDescription(entry.params()), scope, evaluator);
        StepOutcome outcome = type.execute(resolved, false);

        return new CleanupResult(index, entry.type(), resolved,
                outcome.success() ? CleanupStatus.SUCCEEDED : CleanupStatus.FAILED,
                System.currentTimeMillis() - started, outcome.message(), description);
    }

    /** The description as written, or null. */
    private static String writtenDescription(StepSpec step) {
        Object value = step.params().get(DESCRIPTION);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The description resolved when every reference is in scope and resolving succeeds, otherwise as
     * written (null when absent). Never throws: a description must not change what a step or cleanup does.
     */
    private String describe(StepSpec step, Map<String, Object> scope) {
        Object raw = step.params().get(DESCRIPTION);
        if (raw != null && scope.keySet().containsAll(Interpolator.references(raw))) {
            try {
                return resolvedDescription(step, scope);
            } catch (RuntimeException e) {
                // fall through to the text as written
            }
        }
        return writtenDescription(step);
    }

    /** The description resolved with {@code scope}, or null. Throws when a reference can't be resolved. */
    private String resolvedDescription(StepSpec step, Map<String, Object> scope) {
        Object value = step.params().get(DESCRIPTION);
        return value == null ? null : String.valueOf(Interpolator.resolve(value, scope, evaluator));
    }

    private static Map<String, Object> withoutDescription(Map<String, Object> params) {
        Map<String, Object> copy = new LinkedHashMap<>(params);
        copy.remove(DESCRIPTION);
        return copy;
    }

}
