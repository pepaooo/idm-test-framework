package com.idm.test.engine;

import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.i18n.KeyedArgumentException;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.model.WaitSpec;
import com.idm.test.engine.result.CleanupResult;
import com.idm.test.engine.result.CleanupStatus;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.DeferredExpression;
import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.spi.FailureKind;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.RunListener;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import com.idm.test.engine.spi.WaitObservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Runs a scenario: interpolate each step's parameters, execute in order, retry assertions that
 * declare a wait, stop at the first failure, then run the author's cleanup block.
 * <p>
 * A run can be watched ({@link RunListener}) and stopped: a stop is honoured before each step,
 * before and between the tries under {@code wait:} (within a fraction of a second, however long the
 * poll) and before the cleanup, and skips the cleanup entirely, so the
 * result lists those entries with the values they would have used.
 * <p>
 * Deliberately knows nothing about Spring, JUnit, files or output formats. It takes its
 * dependencies by constructor and returns a result object. That is what lets a CLI reuse it.
 */
public final class ScenarioEngine {

    /** Reserved: handled here rather than by a registered step type. */
    private static final String SET_STEP = "set";

    private static final String DESCRIPTION = "description";

    private static final Logger LOG = LoggerFactory.getLogger(ScenarioEngine.class);

    /** Why a step, a cleanup entry or a scenario ended when the run was stopped. */
    private static final Message STOPPED = Message.of("engine.run.stopped");

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
        return run(scenario, sourceName, RunListener.NONE, () -> false);
    }

    /**
     * Runs {@code scenario}, telling {@code listener} how it goes, until it ends or
     * {@code stopRequested} answers true. A stopped scenario fails as a setup problem; its cleanup
     * does not run and comes back in {@link ScenarioResult#skippedCleanup()} instead.
     */
    public ScenarioResult run(Scenario scenario, String sourceName, RunListener runListener,
                              BooleanSupplier stopRequested) {
        // Every call goes through the guard: a listener that throws must not change the run.
        RunListener listener = guarded(runListener);
        long started = System.currentTimeMillis();
        Map<String, Object> scope = new LinkedHashMap<>();

        List<StepResult> results = new ArrayList<>();
        List<CleanupResult> cleanupResults = List.of();
        List<CleanupResult> skippedCleanup = List.of();
        boolean success = true;
        Message failureDetail = null;
        AtomicBoolean stopped = new AtomicBoolean();

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
                failureDetail = Message.of("engine.fixture.failed", thrown(e));
            }

            for (int i = 0; i < scenario.steps().size() && success; i++) {
                StepSpec step = scenario.steps().get(i);
                long stepStarted = System.currentTimeMillis();
                StepResult result;
                if (stopRequested.getAsBoolean()) {
                    // Recorded on the step that would have run next, so the stop shows where it came.
                    stopped.set(true);
                    result = failed(i, step.type(), Map.of(), stepStarted, STOPPED, describe(step, scope));
                } else {
                    listener.stepStarted(i, step.type(), describe(step, scope));
                    try {
                        result = runStep(step, i, scope, listener, stopRequested, stopped);
                    } catch (RuntimeException e) {
                        result = failed(i, step.type(), Map.of(), stepStarted,
                                Message.of("engine.step.threw", step.type(), KeyedArgumentException.typeName(e),
                                        thrown(e)),
                                describe(step, scope));
                    }
                }
                listener.stepFinished(result);
                results.add(result);
                success = result.success();
            }
        } finally {
            // The author's cleanup always runs, whether or not the steps passed — unless the run
            // was stopped: then nothing more runs, and the entries are reported as they would have run.
            if (stopped.get() || stopRequested.getAsBoolean()) {
                stopped.set(true);
                skippedCleanup = skippedByStop(scenario.cleanup(), scope);
            } else {
                cleanupResults = runCleanup(scenario.cleanup(), scope, listener);
            }
        }

        if (stopped.get() && success) {
            // Every step that ran passed, but the stop came before the cleanup: the scenario did
            // not finish, and something may be left in the lab.
            success = false;
            if (failureDetail == null) {
                failureDetail = STOPPED;
            }
        }

        boolean cleanupFailed = cleanupResults.stream()
                .anyMatch(entry -> entry.status() == CleanupStatus.FAILED);

        return new ScenarioResult(scenario.name(), sourceName, success && !cleanupFailed,
                System.currentTimeMillis() - started, results, cleanupResults,
                failureDetail == null ? null : Messages.english(failureDetail), scenario.description(),
                key(failureDetail), args(failureDetail), stopped.get(), skippedCleanup);
    }

    @SuppressWarnings("unchecked")
    private StepResult runStep(StepSpec step, int index, Map<String, Object> scope, RunListener listener,
                               BooleanSupplier stopRequested, AtomicBoolean stopped) {

        long started = System.currentTimeMillis();

        String description;
        try {
            description = resolvedDescription(step, scope);
        } catch (RuntimeException e) {
            return failed(index, step.type(), Map.of(), started,
                    Message.of("engine.step.description", thrown(e)), writtenDescription(step));
        }

        if (SET_STEP.equals(step.type())) {
            return runSet(step, index, scope, started, description);
        }

        StepType type = registry.get(step.type());

        if (type == null) {
            return failed(index, step.type(), Map.of(), started,
                    Message.of("engine.step.unknownType", step.type(), new TreeSet<>(registry.keySet())), description);
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
            return failed(index, step.type(), reportable(resolved), started,
                    Message.of("engine.step.waitNotRetryable", step.type()), description);
        }

        boolean captureRequested = asName != null;

        if (wait == null) {
            StepOutcome outcome = type.execute(resolved, captureRequested);
            outcome = bind(scope, asName, outcome, step.type());
            return new StepResult(index, step.type(), reportable(resolved), outcome.success(),
                    System.currentTimeMillis() - started, outcome.message(), null, null, description,
                    outcome.evidence(), key(outcome.detail()), args(outcome.detail()), kindOf(outcome), null,
                    outcome.checks());
        }

        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<StepOutcome> last = new AtomicReference<>();
        List<WaitObservation> history = new ArrayList<>();

        long waitStarted = System.currentTimeMillis();
        // True when the assertion passed or a final failure ended the wait early; either way
        // waitUntil's loop condition was satisfied. False on timeout or stop (see `stopped`).
        // Success itself always comes from `outcome`.
        boolean ended = executor.waitUntil(() -> {
            int attempt = attempts.incrementAndGet();
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
                // An unexpected exception is a setup problem, as it is without wait.
                outcome = setupFailure(
                        Message.of("engine.wait.threw", KeyedArgumentException.typeName(e), thrown(e)), null);
            }
            StepOutcome previous = last.getAndSet(outcome);
            long elapsed = System.currentTimeMillis() - waitStarted;
            boolean changed = previous == null || !Objects.equals(previous.message(), outcome.message());
            if (changed && history.size() < StepResult.MAX_WAIT_HISTORY) {
                history.add(new WaitObservation(attempt, elapsed, outcome.message()));
            }
            listener.attempt(index, attempt, elapsed, outcome.message());
            // A final failure ends the wait: polling it again cannot change the answer.
            return outcome.success() || outcome.isFinal();
        }, wait.timeout(), wait.poll(), () -> {
            // Asked before each try and every few hundred milliseconds while sleeping between
            // tries, so a stop does not wait out a long poll interval.
            if (stopRequested.getAsBoolean()) {
                stopped.set(true);
            }
            return stopped.get();
        });
        long waitedMillis = System.currentTimeMillis() - waitStarted;
        List<WaitObservation> waitHistory = history.isEmpty() ? null : history;

        if (stopped.get()) {
            StepOutcome lastTry = last.get();
            return new StepResult(index, step.type(), reportable(resolved), false,
                    System.currentTimeMillis() - started, Messages.english(STOPPED), attempts.get(), waitedMillis,
                    description, lastTry == null ? null : lastTry.evidence(), key(STOPPED), args(STOPPED),
                    FailureKind.SETUP.name(), waitHistory, lastTry == null ? null : lastTry.checks());
        }

        StepOutcome outcome = bind(scope, asName, last.get(), step.type());
        boolean success = outcome != null && outcome.success();

        long elapsed = System.currentTimeMillis() - started;
        String message;
        Message detail = null;
        if (outcome == null) {
            // The supplier above always sets `last` before returning, so this only happens
            // when waitUntil's loop condition never even entered — the timeout was already
            // spent. A null outcome here really does mean the condition never ran.
            detail = Message.of("engine.wait.timedOut");
            message = Messages.english(detail);
        } else if (outcome.isFinal()) {
            detail = Message.of("engine.wait.notRetried", keyed(outcome));
            message = Messages.english(detail);
        } else if (ended) {
            // Covers both the ordinary passing case and the one bind() just downgraded: the
            // assertion itself passed on this attempt, so its own message (success or, on a
            // missing capture, bind()'s failure message) is what belongs here — never the
            // "last observed value after retrying" suffix, which only makes sense when the
            // assertion itself never passed.
            message = outcome.message();
            detail = outcome.detail();
        } else {
            detail = Message.of("engine.wait.lastObserved", keyed(outcome));
            message = Messages.english(detail);
        }

        // The kind stays the last outcome's through the suffixes above; no outcome at all means
        // the step never got an answer, a setup problem.
        String kind = success ? null : outcome == null ? FailureKind.SETUP.name() : kindOf(outcome);
        return new StepResult(index, step.type(), reportable(resolved), success, elapsed, message,
                attempts.get(), waitedMillis, description, outcome == null ? null : outcome.evidence(),
                key(detail), args(detail), kind, waitHistory, outcome == null ? null : outcome.checks());
    }

    /**
     * {@code listener} with every call guarded: what it throws is logged (its type only, never its
     * message, which could carry a secret) and dropped.
     */
    private static RunListener guarded(RunListener listener) {
        if (listener == null || listener == RunListener.NONE) {
            return RunListener.NONE;
        }
        return new RunListener() {
            @Override
            public void stepStarted(int index, String type, String description) {
                safely(listener, () -> listener.stepStarted(index, type, description));
            }

            @Override
            public void attempt(int index, int attempt, long elapsedMillis, String message) {
                safely(listener, () -> listener.attempt(index, attempt, elapsedMillis, message));
            }

            @Override
            public void stepFinished(StepResult result) {
                safely(listener, () -> listener.stepFinished(result));
            }

            @Override
            public void cleanupFinished(CleanupResult result) {
                safely(listener, () -> listener.cleanupFinished(result));
            }
        };
    }

    private static void safely(RunListener listener, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            LOG.warn("Run listener {} threw {}; ignored", listener.getClass().getName(), e.getClass().getName());
        }
    }

    /**
     * A failed step's result, its message by key: a setup problem, since the engine itself found
     * the step could not run as written (or it threw).
     */
    private static StepResult failed(int index, String type, Map<String, Object> params, long started,
                                     Message detail, String description) {
        return new StepResult(index, type, params, false, System.currentTimeMillis() - started,
                Messages.english(detail), null, null, description, null, key(detail), args(detail),
                FailureKind.SETUP.name());
    }

    /** A failure the engine records as a setup problem, with the evidence it has, if any. */
    private static StepOutcome setupFailure(Message detail, Evidence evidence) {
        return new StepOutcome(false, Messages.english(detail), null, false, evidence, detail, FailureKind.SETUP);
    }

    /** The outcome's failure kind by name, or null when it passed. */
    private static String kindOf(StepOutcome outcome) {
        return outcome.kind() == null ? null : outcome.kind().name();
    }

    /**
     * What an exception says, as a message argument: its message by key when it has one, so it
     * reads in the run's language, otherwise its text (which may be null, shown as "null").
     */
    private static Object thrown(Throwable e) {
        if (e instanceof KeyedArgumentException keyed) {
            return keyed.detail();
        }
        if (e instanceof ExpressionEvaluator.ExpressionException expression && expression.detail() != null) {
            return expression.detail();
        }
        if (e instanceof ValidationException validation && validation.detail() != null) {
            return validation.detail();
        }
        return e.getMessage();
    }

    /** The outcome's message by key, or its plain text when the step did not use a key. */
    private static Object keyed(StepOutcome outcome) {
        return outcome.detail() != null ? outcome.detail() : outcome.message();
    }

    private static String key(Message detail) {
        return detail == null ? null : detail.key();
    }

    private static List<Object> args(Message detail) {
        return detail == null ? null : detail.args();
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
            throw new KeyedArgumentException(Message.of("engine.defer.notExpression", text));
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

        Message message = Message.of("engine.set.bound", bound.keySet());
        return new StepResult(index, SET_STEP, bound, true, System.currentTimeMillis() - started,
                Messages.english(message), null, null, description, null, key(message), args(message));
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
            // The step's own defect, not the lab's: a setup problem.
            return setupFailure(Message.of("engine.capture.noValue", stepType, asName), outcome.evidence())
                    .withChecks(outcome.checks());
        }
        scope.put(String.valueOf(asName), outcome.value());
        return outcome;
    }

    /**
     * Runs every cleanup entry in order, whatever happened before. An entry that references a
     * name never bound is SKIPPED: what it would tear down never happened. Never throws.
     */
    private List<CleanupResult> runCleanup(List<StepSpec> cleanup, Map<String, Object> scope,
                                           RunListener listener) {
        List<CleanupResult> results = new ArrayList<>();
        for (int i = 0; i < cleanup.size(); i++) {
            StepSpec entry = cleanup.get(i);
            long started = System.currentTimeMillis();
            CleanupResult result;
            try {
                result = runCleanupEntry(entry, i, scope, started);
            } catch (RuntimeException e) {
                result = cleanupResult(i, entry.type(), CleanupStatus.FAILED, started,
                        Message.of("engine.cleanup.threw", entry.type(), KeyedArgumentException.typeName(e), thrown(e)),
                        writtenDescription(entry));
            }
            listener.cleanupFinished(result);
            results.add(result);
        }
        return results;
    }

    /**
     * The cleanup entries a stop skipped, SKIPPED, each with the values it would have used: every
     * parameter resolved with what the scenario had bound when it stopped, or as written when it
     * cannot be. Runs nothing and never throws.
     */
    private List<CleanupResult> skippedByStop(List<StepSpec> cleanup, Map<String, Object> scope) {
        List<CleanupResult> skipped = new ArrayList<>();
        for (int i = 0; i < cleanup.size(); i++) {
            StepSpec entry = cleanup.get(i);
            Map<String, Object> params = new LinkedHashMap<>();
            withoutDescription(entry.params()).forEach((name, value) -> params.put(name, resolvedOrRaw(value, scope)));
            skipped.add(new CleanupResult(i, entry.type(), params, CleanupStatus.SKIPPED, 0,
                    Messages.english(STOPPED), describe(entry, scope), key(STOPPED), args(STOPPED), null));
        }
        return skipped;
    }

    /** {@code value} resolved with {@code scope} when every reference is bound and resolving succeeds, else as written. */
    private Object resolvedOrRaw(Object value, Map<String, Object> scope) {
        if (!scope.keySet().containsAll(Interpolator.references(value))) {
            return value;
        }
        try {
            return Interpolator.resolve(value, scope, evaluator);
        } catch (RuntimeException e) {
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private CleanupResult runCleanupEntry(
            StepSpec entry, int index, Map<String, Object> scope, long started) {

        Set<String> unbound = new TreeSet<>(Interpolator.references(withoutDescription(entry.params())));
        unbound.removeAll(scope.keySet());
        if (!unbound.isEmpty()) {
            return cleanupResult(index, entry.type(), CleanupStatus.SKIPPED, started,
                    Message.of("engine.cleanup.skipped", unbound), writtenDescription(entry));
        }

        String description = describe(entry, scope);

        StepType type = registry.get(entry.type());
        if (type == null) {
            return cleanupResult(index, entry.type(), CleanupStatus.FAILED, started,
                    Message.of("engine.step.unknownType", entry.type(), new TreeSet<>(registry.keySet())), description);
        }

        Map<String, Object> resolved =
                (Map<String, Object>) Interpolator.resolve(withoutDescription(entry.params()), scope, evaluator);
        StepOutcome outcome = type.execute(resolved, false);

        return new CleanupResult(index, entry.type(), resolved,
                outcome.success() ? CleanupStatus.SUCCEEDED : CleanupStatus.FAILED,
                System.currentTimeMillis() - started, outcome.message(), description,
                key(outcome.detail()), args(outcome.detail()), cleanupKind(outcome.success()));
    }

    /** A cleanup entry's result with no parameters recorded, its message by key. */
    private static CleanupResult cleanupResult(int index, String type, CleanupStatus status, long started,
                                               Message detail, String description) {
        return new CleanupResult(index, type, Map.of(), status, System.currentTimeMillis() - started,
                Messages.english(detail), description, key(detail), args(detail),
                cleanupKind(status != CleanupStatus.FAILED));
    }

    /**
     * Every failed cleanup entry is a finding, whatever made it fail: something the scenario
     * created may be left in the lab.
     */
    private static String cleanupKind(boolean succeededOrSkipped) {
        return succeededOrSkipped ? null : FailureKind.FINDING.name();
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
