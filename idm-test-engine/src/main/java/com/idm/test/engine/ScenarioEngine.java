package com.idm.test.engine;

import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.model.WaitSpec;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a scenario: interpolate each step's parameters, execute in order, retry assertions
 * that declare a wait, stop at the first failure, then unwind cleanup in reverse.
 * <p>
 * Deliberately knows nothing about Spring, JUnit, files or output formats. It takes its
 * dependencies by constructor and returns a result object. That is what lets a CLI reuse it.
 */
public final class ScenarioEngine {

    private static final Logger log = LoggerFactory.getLogger(ScenarioEngine.class);

    /** Reserved: handled here rather than by a registered step type. */
    private static final String SET_STEP = "set";

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
        Deque<Runnable> undos = new ArrayDeque<>();
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
                    result = runStep(step, i, scope, undos);
                } catch (RuntimeException e) {
                    result = new StepResult(i, step.type(), Map.of(), false,
                            System.currentTimeMillis() - stepStarted,
                            "step '" + step.type() + "' threw " + e.getClass().getSimpleName()
                                    + ": " + e.getMessage(),
                            null, null);
                }
                results.add(result);
                success = result.success();
            }
        } finally {
            // Whatever earlier steps created must be torn down even when a step throws
            // instead of returning a failed outcome, so this always runs. On the fixture
            // failure path there is nothing to undo yet, but the structure doesn't special
            // case that — unwind() is a no-op on an empty deque.
            unwind(undos);
        }

        return new ScenarioResult(scenario.name(), sourceName, success,
                System.currentTimeMillis() - started, results, failureDetail);
    }

    @SuppressWarnings("unchecked")
    private StepResult runStep(
            StepSpec step, int index, Map<String, Object> scope, Deque<Runnable> undos) {

        long started = System.currentTimeMillis();

        if (SET_STEP.equals(step.type())) {
            return runSet(step, index, scope, started);
        }

        StepType type = registry.get(step.type());

        if (type == null) {
            return new StepResult(index, step.type(), Map.of(), false,
                    System.currentTimeMillis() - started,
                    "unknown step type '" + step.type() + "'. Available: "
                            + new TreeSet<>(registry.keySet()),
                    null, null);
        }

        Map<String, Object> resolved = (Map<String, Object>) Interpolator.resolve(
                step.params(), scope, evaluator);

        WaitSpec wait = WaitSpec.parse(resolved.remove("wait"), defaultTimeout, defaultPoll);
        Object asName = resolved.remove("as");

        if (wait != null && !type.retryable()) {
            return new StepResult(index, step.type(), resolved, false,
                    System.currentTimeMillis() - started,
                    "'wait' is only valid on assertions, and step '" + step.type()
                            + "' is not retryable",
                    null, null);
        }

        if (wait == null) {
            StepOutcome outcome = type.execute(resolved);
            register(undos, outcome);
            bind(scope, asName, outcome);
            return new StepResult(index, step.type(), resolved, outcome.success(),
                    System.currentTimeMillis() - started, outcome.message(), null, null);
        }

        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<StepOutcome> last = new AtomicReference<>();

        long waitStarted = System.currentTimeMillis();
        boolean passed = executor.waitUntil(() -> {
            attempts.incrementAndGet();
            StepOutcome outcome;
            try {
                outcome = type.execute(resolved);
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
            // Register as each attempt happens, not just the final one, so an undo
            // returned by an earlier attempt (that later attempts don't repeat) isn't lost.
            register(undos, outcome);
            return outcome.success();
        }, wait.timeout(), wait.poll());
        long waitedMillis = System.currentTimeMillis() - waitStarted;

        StepOutcome outcome = last.get();
        bind(scope, asName, outcome);

        long elapsed = System.currentTimeMillis() - started;
        String message;
        if (outcome == null) {
            // The supplier above always sets `last` before returning, so this only happens
            // when waitUntil's loop condition never even entered — the timeout was already
            // spent. A null outcome here really does mean the condition never ran.
            message = "wait timed out before the assertion ran once";
        } else if (passed) {
            message = outcome.message();
        } else {
            message = outcome.message() + " (last observed value after retrying)";
        }

        return new StepResult(index, step.type(), resolved, passed, elapsed, message,
                attempts.get(), waitedMillis);
    }

    /**
     * Binds each entry into the scenario's variables, in declaration order so a later entry may
     * read one bound earlier in the same step.
     */
    private StepResult runSet(StepSpec step, int index, Map<String, Object> scope, long started) {
        Map<String, Object> bound = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : step.params().entrySet()) {
            Object value = Interpolator.resolve(entry.getValue(), scope, evaluator);
            scope.put(entry.getKey(), value);
            bound.put(entry.getKey(), value);
        }

        return new StepResult(index, SET_STEP, bound, true,
                System.currentTimeMillis() - started, "bound " + bound.keySet(), null, null);
    }

    /**
     * Binds a successful step's value under its {@code as:} name, so later steps can read it.
     */
    private static void bind(Map<String, Object> scope, Object asName, StepOutcome outcome) {
        if (asName != null && outcome != null && outcome.success()) {
            scope.put(String.valueOf(asName), outcome.value());
        }
    }

    private static void register(Deque<Runnable> undos, StepOutcome outcome) {
        if (outcome != null && outcome.undo() != null) {
            undos.push(outcome.undo());
        }
    }

    /**
     * Runs every undo in reverse order. A cleanup failure is logged but never replaces the
     * scenario's own result: what broke matters more than what could not be tidied.
     */
    private static void unwind(Deque<Runnable> undos) {
        while (!undos.isEmpty()) {
            try {
                undos.pop().run();
            } catch (Exception e) {
                log.warn("Cleanup action failed, continuing: {}", e.getMessage());
            }
        }
    }
}
