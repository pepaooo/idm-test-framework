package com.idm.test.engine;

import com.idm.test.core.engine.TestExecutor;
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

    private final Map<String, StepType> registry;
    private final FixtureFactory fixtures;
    private final TestExecutor executor;
    private final Duration defaultTimeout;
    private final Duration defaultPoll;

    public ScenarioEngine(
            Map<String, StepType> registry,
            FixtureFactory fixtures,
            TestExecutor executor,
            Duration defaultTimeout,
            Duration defaultPoll) {
        this.registry = registry;
        this.fixtures = fixtures;
        this.executor = executor;
        this.defaultTimeout = defaultTimeout;
        this.defaultPoll = defaultPoll;
    }

    public ScenarioResult run(Scenario scenario, String sourceName) {
        long started = System.currentTimeMillis();
        Map<String, Object> scope = new LinkedHashMap<>(fixtures.build(scenario.fixtures()));

        List<StepResult> results = new ArrayList<>();
        Deque<Runnable> undos = new ArrayDeque<>();
        boolean success = true;

        for (int i = 0; i < scenario.steps().size() && success; i++) {
            StepResult result = runStep(scenario.steps().get(i), i, scope, undos);
            results.add(result);
            success = result.success();
        }

        unwind(undos);

        return new ScenarioResult(scenario.name(), sourceName, success,
                System.currentTimeMillis() - started, results);
    }

    @SuppressWarnings("unchecked")
    private StepResult runStep(
            StepSpec step, int index, Map<String, Object> scope, Deque<Runnable> undos) {

        long started = System.currentTimeMillis();
        StepType type = registry.get(step.type());

        Map<String, Object> resolved = (Map<String, Object>) Interpolator.resolve(
                step.params(), scope);

        WaitSpec wait = WaitSpec.parse(resolved.remove("wait"), defaultTimeout, defaultPoll);

        if (wait == null) {
            StepOutcome outcome = type.execute(resolved);
            register(undos, outcome);
            return new StepResult(index, step.type(), resolved, outcome.success(),
                    System.currentTimeMillis() - started, outcome.message(), null, null);
        }

        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<StepOutcome> last = new AtomicReference<>();

        boolean passed = executor.waitUntil(() -> {
            attempts.incrementAndGet();
            StepOutcome outcome = type.execute(resolved);
            last.set(outcome);
            return outcome.success();
        }, wait.timeout(), wait.poll());

        StepOutcome outcome = last.get();
        register(undos, outcome);

        long elapsed = System.currentTimeMillis() - started;
        String message;
        if (outcome == null) {
            // waitUntil never evaluated the condition, which happens when the timeout is
            // already spent. Report that rather than failing with a NullPointerException.
            message = "wait timed out before the assertion ran once";
        } else if (passed) {
            message = outcome.message();
        } else {
            message = outcome.message() + " (last observed value after retrying)";
        }

        return new StepResult(index, step.type(), resolved, passed, elapsed, message,
                attempts.get(), elapsed);
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
