package com.idm.test.engine;

import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.result.CleanupStatus;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.DeferredExpression;
import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.i18n.KeyedArgumentException;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScenarioEngine")
class ScenarioEngineTest {

    private final List<String> events = new ArrayList<>();

    /** Records that it ran. */
    private StepType recordingAction(String name) {
        return new StepType() {
            public String name() { return name; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                events.add("run:" + name + ":" + params.get("dn"));
                return StepOutcome.success("ok");
            }
        };
    }

    private StepType failingAction(String name) {
        return new StepType() {
            public String name() { return name; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                events.add("run:" + name);
                return StepOutcome.failure(name + " went wrong");
            }
        };
    }

    private static Scenario withCleanup(List<StepSpec> steps, StepSpec... cleanup) {
        return new Scenario("Test", null,
                Map.of("user", new FixtureSpec("random-user", Map.of())),
                steps, List.of(cleanup));
    }

    /** Fails the first {@code failures} calls, then succeeds. */
    private StepType eventuallyPassing(String name, int failures) {
        AtomicInteger calls = new AtomicInteger();
        return new StepType() {
            public String name() { return name; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return calls.getAndIncrement() < failures
                        ? StepOutcome.failure("not yet")
                        : StepOutcome.success("arrived");
            }
        };
    }

    /** Fails with ordinary failures {@code ordinary} times, then with a final failure. */
    private StepType finallyFailing(String name, int ordinary, AtomicInteger calls) {
        return new StepType() {
            public String name() { return name; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return calls.getAndIncrement() < ordinary
                        ? StepOutcome.failure("not yet")
                        : StepOutcome.finalFailure("cannot ever pass");
            }
        };
    }

    private ScenarioEngine engineWith(StepType... types) {
        Map<String, StepType> registry = new java.util.LinkedHashMap<>();
        for (StepType type : types) {
            registry.put(type.name(), type);
        }
        return new ScenarioEngine(
                registry,
                new FixtureFactory(new TestDataGenerator()),
                new TestExecutor(),
                Duration.ofSeconds(2),
                Duration.ofMillis(50),
                new ExpressionEvaluator());
    }

    private static Scenario scenario(StepSpec... steps) {
        return new Scenario("Test", null,
                Map.of("user", new FixtureSpec("random-user", Map.of())),
                List.of(steps));
    }

    @Test
    @DisplayName("Should run steps in order and interpolate their parameters")
    void testRunsInOrderWithInterpolation() {
        ScenarioEngine engine = engineWith(recordingAction("a"), recordingAction("b"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", Map.of("dn", "first-${user.userId}")),
                new StepSpec("b", Map.of("dn", "second"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).hasSize(2);
        assertThat(events.get(0)).startsWith("run:a:first-");
        assertThat(events.get(1)).isEqualTo("run:b:second");
        assertThat(result.steps().get(0).resolvedParams().get("dn").toString())
                .doesNotContain("${");
    }

    @Test
    @DisplayName("Should stop at the first failure and skip the rest")
    void testStopsAtFirstFailure() {
        ScenarioEngine engine = engineWith(failingAction("a"), recordingAction("b"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", Map.of("dn", "x")),
                new StepSpec("b", Map.of("dn", "y"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(events).containsExactly("run:a");
        assertThat(result.failureSummary()).contains("a went wrong").contains("t.yaml");
    }

    @Test
    @DisplayName("Should retry a failing assertion until it passes")
    void testRetriesAssertion() {
        ScenarioEngine engine = engineWith(eventuallyPassing("check", 2));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait", Boolean.TRUE))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(result.steps().get(0).attempts()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Should give up on an assertion that never passes and report what it saw")
    void testAssertionTimeout() {
        ScenarioEngine engine = engineWith(eventuallyPassing("check", Integer.MAX_VALUE));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait",
                        Map.of("timeout", "2s", "poll", "1s")))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).attempts()).isGreaterThan(0);
        assertThat(result.steps().get(0).waitedMillis()).isNotNull();
        assertThat(result.failureSummary()).contains("not yet");
    }

    @Test
    @DisplayName("A final failure under wait stops after one attempt and says it was not retried")
    void testFinalFailureStopsWait() {
        AtomicInteger calls = new AtomicInteger();
        ScenarioEngine engine = engineWith(finallyFailing("check", 0, calls));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait",
                        Map.of("timeout", "10s", "poll", "1s")))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.steps().get(0).attempts()).isEqualTo(1);
        assertThat(result.steps().get(0).waitedMillis()).isLessThan(5_000L);
        assertThat(result.steps().get(0).message())
                .isEqualTo("cannot ever pass (not retried: waiting cannot change this)");
    }

    @Test
    @DisplayName("A final failure after ordinary failures stops at that attempt")
    void testFinalFailureAfterOrdinaryFailures() {
        AtomicInteger calls = new AtomicInteger();
        ScenarioEngine engine = engineWith(finallyFailing("check", 2, calls));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait", Boolean.TRUE))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(result.steps().get(0).attempts()).isEqualTo(3);
        assertThat(result.steps().get(0).message()).endsWith("(not retried: waiting cannot change this)");
    }

    @Test
    @DisplayName("An ordinary failure under wait keeps polling and keeps the retry suffix")
    void testOrdinaryFailureStillPolls() {
        ScenarioEngine engine = engineWith(eventuallyPassing("check", Integer.MAX_VALUE));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait",
                        Map.of("timeout", "2s", "poll", "1s")))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).attempts()).isGreaterThan(1);
        assertThat(result.steps().get(0).message()).endsWith("(last observed value after retrying)");
    }

    @Test
    @DisplayName("A final failure without wait is an ordinary failed step")
    void testFinalFailureWithoutWait() {
        AtomicInteger calls = new AtomicInteger();
        ScenarioEngine engine = engineWith(finallyFailing("check", 0, calls));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).message()).isEqualTo("cannot ever pass");
        assertThat(result.steps().get(0).attempts()).isNull();
    }

    @Test
    @DisplayName("Only finalFailure is final")
    void testOnlyFinalFailureIsFinal() {
        assertThat(StepOutcome.success("ok").isFinal()).isFalse();
        assertThat(StepOutcome.success("ok", 1).isFinal()).isFalse();
        assertThat(StepOutcome.failure("no").isFinal()).isFalse();
        assertThat(new StepOutcome(false, "no", null).isFinal()).isFalse();
        StepOutcome terminal = StepOutcome.finalFailure("never");
        assertThat(terminal.success()).isFalse();
        assertThat(terminal.value()).isNull();
        assertThat(terminal.isFinal()).isTrue();
    }

    @Test
    @DisplayName("Should fail the step when a param reference can't resolve")
    void testUnresolvableReferenceFailsStep() {
        ScenarioEngine engine = engineWith(recordingAction("a"), recordingAction("b"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", Map.of("dn", "x")),
                new StepSpec("b", Map.of("dn", "${user.bogus}"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(1).success()).isFalse();
        assertThat(result.steps().get(1).message()).contains("b").contains("bogus");
        assertThat(events).containsExactly("run:a:x");
    }

    @Test
    @DisplayName("Should fail the step (not abort the run) when execute() throws")
    void testThrowingStepFailsButDoesNotAbort() {
        StepType throwing = new StepType() {
            public String name() { return "boom"; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                throw new IllegalStateException("kaboom");
            }
        };
        ScenarioEngine engine = engineWith(recordingAction("a"), throwing);

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", Map.of("dn", "x")),
                new StepSpec("boom", Map.of("dn", "y"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(1).success()).isFalse();
        assertThat(result.steps().get(1).message()).contains("boom").contains("kaboom");
        assertThat(events).containsExactly("run:a:x");
    }

    @Test
    @DisplayName("Should report the exception message when a retried assertion throws every attempt")
    void testRetriedAssertionThrowingEveryAttemptReportsMessage() {
        StepType alwaysThrows = new StepType() {
            public String name() { return "check"; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                throw new IllegalStateException("connector unavailable");
            }
        };
        ScenarioEngine engine = engineWith(alwaysThrows);

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait",
                        Map.of("timeout", "1s", "poll", "1s")))),
                "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).attempts()).isGreaterThan(0);
        assertThat(result.failureSummary())
                .contains("connector unavailable")
                .doesNotContain("before the assertion ran once");
    }

    @Test
    @DisplayName("Should fail with a naming message for an unknown step type")
    void testUnknownStepTypeFails() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("does.not.exist", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).message())
                .contains("does.not.exist")
                .contains("[a]");
    }

    @Test
    @DisplayName("Should fail when 'wait' is declared on a step type that isn't retryable")
    void testWaitOnNonRetryableStepFails() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", Map.of("dn", "x", "wait", Boolean.TRUE))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).message()).contains("wait").contains("a");
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("Should fail with a ScenarioResult, not a thrown exception, when a fixture "
            + "names an unknown generator")
    void testUnknownFixtureGeneratorFailsWithoutThrowing() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        Scenario scenario = new Scenario("Test", null,
                Map.of("user", new FixtureSpec("randmo-user", Map.of())),
                List.of(new StepSpec("a", Map.of("dn", "x"))));

        ScenarioResult result = engine.run(scenario, "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).isEmpty();
        assertThat(result.failureSummary()).isNotNull().contains("randmo-user");
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("Should report the exception's class name, not the literal \"null\", when a "
            + "retried assertion throws with no message")
    void testRetriedAssertionThrowingWithNullMessageReportsClassName() {
        StepType throwsWithNullMessage = new StepType() {
            public String name() { return "check"; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                throw new IllegalStateException();
            }
        };
        ScenarioEngine engine = engineWith(throwsWithNullMessage);

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait",
                        Map.of("timeout", "1s", "poll", "1s")))),
                "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).attempts()).isGreaterThan(0);
        assertThat(result.failureSummary()).contains("IllegalStateException");
    }

    /** Returns a fixed value so `as:` has something to bind. */
    private StepType valueProducing(String name, Object value) {
        return new StepType() {
            public String name() { return name; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return StepOutcome.success("ok", value);
            }
        };
    }

    @Test
    @DisplayName("Should bind a step's value under its as: name and let later steps read it")
    void testAsCaptureIsVisibleToLaterSteps() {
        List<String> seen = new ArrayList<>();
        StepType reader = new StepType() {
            public String name() { return "reader"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                seen.add(String.valueOf(params.get("dn")));
                return StepOutcome.success("ok");
            }
        };
        ScenarioEngine engine = engineWith(valueProducing("maker", "cn=made,ou=users"), reader);

        ScenarioResult result = engine.run(scenario(
                new StepSpec("maker", Map.of("dn", "x", "as", "created")),
                new StepSpec("reader", Map.of("dn", "${created}"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(seen).containsExactly("cn=made,ou=users");
    }

    @Test
    @DisplayName("Should not pass the as: key through to the step")
    void testAsIsStrippedBeforeTheStepRuns() {
        List<Map<String, Object>> received = new ArrayList<>();
        StepType recorder = new StepType() {
            public String name() { return "recorder"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                received.add(params);
                return StepOutcome.success("ok");
            }
        };
        engineWith(recorder).run(scenario(
                new StepSpec("recorder", Map.of("dn", "x", "as", "bound"))), "t.yaml");

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).doesNotContainKey("as");
    }

    /** An assertion whose 'check' is deferred and sees 'attempt' as a binding. */
    private StepType deferredProbe(List<Object> seen) {
        AtomicInteger attempt = new AtomicInteger();
        return new StepType() {
            public String name() { return "probe.verify"; }
            public boolean retryable() { return true; }
            public Set<String> requiredParams() { return Set.of("check"); }
            public Set<String> deferredParams() { return Set.of("check"); }
            public Set<String> deferredBindings() { return Set.of("attempt"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                seen.add(params.get("check"));
                DeferredExpression check = (DeferredExpression) params.get("check");
                Object passed = check.evaluate(Map.of("attempt", attempt.incrementAndGet()));
                return Boolean.TRUE.equals(passed) ? StepOutcome.success("ok") : StepOutcome.failure("not yet");
            }
        };
    }

    @Test
    @DisplayName("A deferred parameter reaches the step unevaluated and is evaluated each attempt with scope and bindings")
    void testDeferredParameter() {
        List<Object> seen = new ArrayList<>();
        ScenarioEngine engine = engineWith(deferredProbe(seen));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("set", Map.of("target", 3)),
                new StepSpec("probe.verify", Map.of("check", "js:{ attempt >= target }", "wait", Boolean.TRUE))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(result.steps().get(1).attempts()).isEqualTo(3);
        assertThat(seen).allSatisfy(value -> assertThat(value).isInstanceOf(DeferredExpression.class));
        assertThat(((DeferredExpression) seen.get(0)).source()).isEqualTo("js:{ attempt >= target }");
        assertThat(result.steps().get(1).resolvedParams().get("check")).isEqualTo("js:{ attempt >= target }");
    }

    @Test
    @DisplayName("A deferred list arrives as a list of expressions and is reported as its sources")
    void testDeferredList() {
        List<Object> seen = new ArrayList<>();
        StepType listProbe = new StepType() {
            public String name() { return "probe.verify"; }
            public boolean retryable() { return true; }
            public Set<String> requiredParams() { return Set.of("check"); }
            public Set<String> deferredParams() { return Set.of("check"); }
            public Set<String> deferredBindings() { return Set.of("attempt"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                seen.add(params.get("check"));
                return StepOutcome.success("ok");
            }
        };
        ScenarioEngine engine = engineWith(listProbe);

        ScenarioResult result = engine.run(scenario(new StepSpec("probe.verify",
                Map.of("check", List.of("js:{ true }", "js:{ attempt > 0 }")))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat((List<?>) seen.get(0)).hasSize(2).allSatisfy(v -> assertThat(v).isInstanceOf(DeferredExpression.class));
        assertThat(result.steps().get(0).resolvedParams().get("check"))
                .isEqualTo(List.of("js:{ true }", "js:{ attempt > 0 }"));
    }

    @Test
    @DisplayName("A binding wins over a scope name of the same name")
    void testBindingWinsOverScope() {
        List<Object> seen = new ArrayList<>();
        ScenarioEngine engine = engineWith(deferredProbe(seen));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("set", Map.of("attempt", 0)),
                new StepSpec("probe.verify", Map.of("check", "js:{ attempt === 1 }"))), "t.yaml");

        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("Should bind a captured map so its fields are reachable by path")
    void testCapturedMapFieldsAreReachable() {
        List<String> seen = new ArrayList<>();
        StepType reader = new StepType() {
            public String name() { return "reader"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                seen.add(String.valueOf(params.get("dn")));
                return StepOutcome.success("ok");
            }
        };
        ScenarioEngine engine = engineWith(
                valueProducing("maker", Map.of("id", "42")), reader);

        engine.run(scenario(
                new StepSpec("maker", Map.of("dn", "x", "as", "created")),
                new StepSpec("reader", Map.of("dn", "${created.id}"))), "t.yaml");

        assertThat(seen).containsExactly("42");
    }

    @Test
    @DisplayName("Should bind set: entries and make them readable by later steps")
    void testSetBindsVariables() {
        List<String> seen = new ArrayList<>();
        StepType reader = new StepType() {
            public String name() { return "reader"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                seen.add(String.valueOf(params.get("dn")));
                return StepOutcome.success("ok");
            }
        };
        ScenarioEngine engine = engineWith(reader);

        ScenarioResult result = engine.run(scenario(
                new StepSpec("set", Map.of("greeting", "js:{ 'hello ' + user.firstName }")),
                new StepSpec("reader", Map.of("dn", "${greeting}"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).startsWith("hello ");
    }

    @Test
    @DisplayName("Should let a set: entry read one bound earlier in the same step")
    void testSetEntriesBindInOrder() {
        List<String> seen = new ArrayList<>();
        StepType reader = new StepType() {
            public String name() { return "reader"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                seen.add(String.valueOf(params.get("dn")));
                return StepOutcome.success("ok");
            }
        };
        Map<String, Object> ordered = new java.util.LinkedHashMap<>();
        ordered.put("first", "ada");
        ordered.put("second", "js:{ first.toUpperCase() }");

        engineWith(reader).run(scenario(
                new StepSpec("set", ordered),
                new StepSpec("reader", Map.of("dn", "${second}"))), "t.yaml");

        assertThat(seen).containsExactly("ADA");
    }

    @Test
    @DisplayName("Should record a set: step in the results like any other step")
    void testSetAppearsInResults() {
        ScenarioResult result = engineWith().run(scenario(
                new StepSpec("set", Map.of("a", "1"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).type()).isEqualTo("set");
    }

    @Test
    @DisplayName("Should fail the scenario when a set: expression cannot be evaluated")
    void testSetExpressionFailureIsReported() {
        ScenarioResult result = engineWith().run(scenario(
                new StepSpec("set", Map.of("bad", "js:{ absent.field }"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("absent");
    }

    @Test
    @DisplayName("Should fail the scenario, naming the step and the as: name, when a step "
            + "declares as: but its outcome carries no value")
    void testNullCaptureFailsTheScenario() {
        // The default shape every step type gets for free: StepOutcome.success(msg) carries a
        // null value(). Binding that under as: would let a later ${created...} reference
        // resolve to the literal text "null" instead of failing loudly.
        StepType noValue = new StepType() {
            public String name() { return "silent"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return StepOutcome.success("ok");
            }
        };
        ScenarioEngine engine = engineWith(noValue);

        ScenarioResult result = engine.run(scenario(
                new StepSpec("silent", Map.of("dn", "x", "as", "created"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).message()).contains("silent").contains("created");
    }

    @Test
    @DisplayName("Should fail a retried assertion, not bind \"null\", when it finally passes "
            + "with as: set but no value")
    void testNullCaptureFailsARetriedAssertionToo() {
        StepType noValueAssertion = new StepType() {
            public String name() { return "check"; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return StepOutcome.success("arrived");
            }
        };
        ScenarioEngine engine = engineWith(noValueAssertion);

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "as", "created", "wait", Boolean.TRUE))),
                "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).message()).contains("check").contains("created");
    }

    @Test
    @DisplayName("Should bind the value from the attempt that finally passed, when a retried "
            + "step fails twice then succeeds with as: set")
    void testRetriedStepBindsValueFromThePassingAttempt() {
        AtomicInteger calls = new AtomicInteger();
        StepType eventuallyPassingWithValue = new StepType() {
            public String name() { return "check"; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                int attempt = calls.getAndIncrement();
                return attempt < 2
                        ? StepOutcome.failure("not yet")
                        : StepOutcome.success("arrived", "value-from-attempt-" + attempt);
            }
        };
        List<String> seen = new ArrayList<>();
        StepType reader = new StepType() {
            public String name() { return "reader"; }
            public java.util.Set<String> requiredParams() { return java.util.Set.of("dn"); }
            public java.util.Set<String> acceptedParams() { return java.util.Set.of("dn"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                seen.add(String.valueOf(params.get("dn")));
                return StepOutcome.success("ok");
            }
        };
        ScenarioEngine engine = engineWith(eventuallyPassingWithValue, reader);

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of(
                        "dn", "x", "as", "captured", "wait", Boolean.TRUE)),
                new StepSpec("reader", Map.of("dn", "${captured}"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(seen).containsExactly("value-from-attempt-2");
    }

    @Test
    @DisplayName("Cleanup runs after the steps, in declaration order")
    void testCleanupRunsAfterStepsInOrder() {
        ScenarioEngine engine = engineWith(recordingAction("a"), recordingAction("b"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1"))),
                new StepSpec("a", Map.of("dn", "c1")),
                new StepSpec("b", Map.of("dn", "c2"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(events).containsExactly("run:a:s1", "run:a:c1", "run:b:c2");
        assertThat(result.cleanup()).extracting(c -> c.status())
                .containsExactly(CleanupStatus.SUCCEEDED, CleanupStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("Cleanup still runs when a step failed")
    void testCleanupRunsAfterAFailedStep() {
        ScenarioEngine engine = engineWith(recordingAction("a"), failingAction("f"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1")), new StepSpec("f", Map.of())),
                new StepSpec("a", Map.of("dn", "c1"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(events).containsExactly("run:a:s1", "run:f", "run:a:c1");
    }

    @Test
    @DisplayName("Every cleanup entry is attempted even after one fails, and the failure fails the scenario")
    void testAllCleanupEntriesAttempted() {
        ScenarioEngine engine = engineWith(recordingAction("a"), failingAction("f"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1"))),
                new StepSpec("f", Map.of()),
                new StepSpec("a", Map.of("dn", "c2"))), "t.yaml");

        assertThat(events).containsExactly("run:a:s1", "run:f", "run:a:c2");
        assertThat(result.cleanup()).extracting(c -> c.status())
                .containsExactly(CleanupStatus.FAILED, CleanupStatus.SUCCEEDED);
        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("cleanup entry 1 (f)").contains("f went wrong");
    }

    @Test
    @DisplayName("A cleanup entry whose capture was never bound is SKIPPED, and a skip never fails a scenario")
    void testUnboundCaptureSkips() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1"))),
                new StepSpec("a", Map.of("dn", "${neverBound}"))), "t.yaml");

        assertThat(events).containsExactly("run:a:s1");
        assertThat(result.cleanup()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(CleanupStatus.SKIPPED);
            assertThat(c.message()).contains("neverBound");
        });
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("A cleanup entry can use a capture a step bound")
    void testCleanupSeesCaptures() {
        ScenarioEngine engine = engineWith(valueProducing("v", "cn=made"), recordingAction("a"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("v", Map.of("dn", "x", "as", "created"))),
                new StepSpec("a", Map.of("dn", "${created}"))), "t.yaml");

        assertThat(events).containsExactly("run:a:cn=made");
        assertThat(result.cleanup()).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo(CleanupStatus.SUCCEEDED));
    }

    @Test
    @DisplayName("A bad field on a bound name is FAILED, not SKIPPED, so a typo cannot hide as a skip")
    void testBadFieldOnBoundNameFails() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1"))),
                new StepSpec("a", Map.of("dn", "${user.bogus}"))), "t.yaml");

        assertThat(result.cleanup()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(CleanupStatus.FAILED);
            assertThat(c.message()).contains("bogus");
        });
        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("A step failure leads the summary; cleanup failures follow it")
    void testStepFailureLeadsSummary() {
        ScenarioEngine engine = engineWith(failingAction("f"), failingAction("g"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("f", Map.of())),
                new StepSpec("g", Map.of())), "t.yaml");

        String summary = result.failureSummary();
        assertThat(summary).contains("f went wrong").contains("g went wrong");
        assertThat(summary.indexOf("f went wrong")).isLessThan(summary.indexOf("g went wrong"));
    }

    @Test
    @DisplayName("A capture that never ran because an earlier step failed leaves cleanup SKIPPED")
    void testCleanupSkipsAfterEarlierStepFailure() {
        ScenarioEngine engine = engineWith(failingAction("f"), valueProducing("v", "cn=made"), recordingAction("a"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("f", Map.of()),
                        new StepSpec("v", Map.of("dn", "x", "as", "created"))),
                new StepSpec("a", Map.of("dn", "${created}"))), "t.yaml");

        assertThat(result.cleanup()).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo(CleanupStatus.SKIPPED));
        assertThat(events).contains("run:f");
        assertThat(events).doesNotContain("run:a:cn=made");
        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("f went wrong");
        assertThat(result.failureSummary()).doesNotContain("cleanup entry");
    }

    @Test
    @DisplayName("A fixture that fails to build still runs cleanup, which SKIPS references to it")
    void testFixtureFailureStillRunsCleanupAsSkipped() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        Scenario badFixture = new Scenario("Test", null,
                Map.of("user", new FixtureSpec("no-such-generator", Map.of())),
                List.of(new StepSpec("a", Map.of("dn", "s1"))),
                List.of(new StepSpec("a", Map.of("dn", "${user.userId}"))));

        ScenarioResult result = engine.run(badFixture, "t.yaml");

        assertThat(result.cleanup()).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo(CleanupStatus.SKIPPED));
        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("fixture setup failed");
    }

    @Test
    @DisplayName("A scenario with no cleanup block reports an empty cleanup list")
    void testNoCleanupMeansEmptyList() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(scenario(new StepSpec("a", Map.of("dn", "s1"))), "t.yaml");

        assertThat(result.cleanup()).isEmpty();
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("A js: typo in a cleanup parameter is FAILED, not a silent success")
    void testJsTypoInCleanupFails() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "s1"))),
                new StepSpec("a", Map.of("dn", "js:{ user.userid }"))), "t.yaml");

        // Before this fix the cleanup action ran with dn=null and reported SUCCEEDED.
        assertThat(events).containsExactly("run:a:s1");
        assertThat(result.cleanup()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(CleanupStatus.FAILED);
            assertThat(c.message()).contains("userid").contains("undefined");
        });
        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("A js: typo in a step parameter fails that step")
    void testJsTypoInStepFails() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(
                scenario(new StepSpec("a", Map.of("dn", "js:{ user.userid }"))), "t.yaml");

        assertThat(events).isEmpty();
        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("userid").contains("undefined");
    }

    @Test
    @DisplayName("A cleanup js:{} reading a capture that was never bound is SKIPPED")
    void testCleanupExpressionOnUnboundCaptureSkips() {
        ScenarioEngine engine = engineWith(
                failingAction("f"), valueProducing("v", "cn=made"), recordingAction("a"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("f", Map.of()),
                        new StepSpec("v", Map.of("dn", "x", "as", "created"))),
                new StepSpec("a", Map.of("dn", "js:{ created + ',ou=users' }"))), "t.yaml");

        assertThat(events).containsExactly("run:f");
        assertThat(result.cleanup()).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(CleanupStatus.SKIPPED);
            assertThat(c.message()).contains("created");
        });
        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("f went wrong").doesNotContain("cleanup entry");
    }

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    /** Records the parameters it received. */
    private StepType paramsRecorder(String name, List<Map<String, Object>> seen) {
        return new StepType() {
            public String name() { return name; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                seen.add(Map.copyOf(params));
                return StepOutcome.success("ok");
            }
        };
    }

    @Test
    @DisplayName("A step's description is resolved into its result, and never reaches the step or resolvedParams")
    void testStepDescriptionRecorded() {
        List<Map<String, Object>> seen = new ArrayList<>();
        ScenarioEngine engine = engineWith(paramsRecorder("a", seen));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", params("description", "Create ${user.userId}", "dn", "x")),
                new StepSpec("a", params("dn", "y"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(seen.get(0)).doesNotContainKey("description");
        assertThat(result.steps().get(0).description()).startsWith("Create ").doesNotContain("${");
        assertThat(result.steps().get(0).resolvedParams()).doesNotContainKey("description");
        assertThat(result.steps().get(1).description()).isNull();
    }

    @Test
    @DisplayName("A set: step records its description and binds no 'description' name")
    void testSetDescription() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("set", params("description", "Pick ${user.userId}", "dn", "z")),
                new StepSpec("a", params("dn", "${dn}"))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(result.steps().get(0).description()).startsWith("Pick ").doesNotContain("${");
        assertThat(result.steps().get(0).resolvedParams()).containsOnlyKeys("dn");
    }

    @Test
    @DisplayName("A description that can't be resolved fails the step, naming 'description'")
    void testUnresolvableDescriptionFailsStep() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", params("description", "for ${nobody.here}", "dn", "x"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).message()).startsWith("could not resolve 'description': ");
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("A step that fails on an unresolvable parameter still records its description resolved")
    void testFailedStepRecordsResolvedDescription() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", params("description", "Create ${user.userId}", "dn", "${nobody.here}"))), "t.yaml");

        assertThat(result.success()).isFalse();
        StepResult step = result.steps().get(0);
        assertThat(step.success()).isFalse();
        assertThat(step.description()).startsWith("Create ").doesNotContain("${");
        assertThat(result.failureSummary()).contains(step.description()).doesNotContain("Create ${");
    }

    @Test
    @DisplayName("Cleanup entries record their description: resolved when they run, as written when skipped")
    void testCleanupDescription() {
        ScenarioEngine engine = engineWith(recordingAction("a"), recordingAction("tidy"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", params("dn", "x"))),
                new StepSpec("tidy", params("description", "Remove ${user.userId}", "dn", "x")),
                new StepSpec("tidy", params("description", "Undo ${never}", "dn", "${never}"))), "t.yaml");

        assertThat(result.cleanup().get(0).description()).startsWith("Remove ").doesNotContain("${");
        assertThat(result.cleanup().get(0).resolvedParams()).doesNotContainKey("description");
        assertThat(result.cleanup().get(1).status()).isEqualTo(CleanupStatus.SKIPPED);
        assertThat(result.cleanup().get(1).description()).isEqualTo("Undo ${never}");
    }

    @Test
    @DisplayName("A description that only names an unbound value does not skip a runnable cleanup entry")
    void testCleanupDescriptionDoesNotCauseSkip() {
        ScenarioEngine engine = engineWith(recordingAction("a"), recordingAction("tidy"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", params("dn", "x"))),
                new StepSpec("tidy", params("description", "Undo ${never}", "dn", "x"))), "t.yaml");

        assertThat(result.cleanup().get(0).status()).isEqualTo(CleanupStatus.SUCCEEDED);
        assertThat(result.cleanup().get(0).description()).isEqualTo("Undo ${never}");
    }

    @Test
    @DisplayName("The scenario's own description is recorded")
    void testScenarioDescriptionRecorded() {
        ScenarioEngine engine = engineWith(recordingAction("a"));

        ScenarioResult result = engine.run(new Scenario("Test", "What this checks",
                Map.of(), List.of(new StepSpec("a", params("dn", "x")))), "t.yaml");

        assertThat(result.description()).isEqualTo("What this checks");
    }

    @Test
    @DisplayName("The failure summary quotes a failing step's or cleanup entry's description, and is unchanged without one")
    void testFailureSummaryQuotesDescription() {
        ScenarioEngine engine = engineWith(failingAction("a"), failingAction("tidy"));

        ScenarioResult described = engine.run(withCleanup(
                List.of(new StepSpec("a", params("description", "Grant roles", "dn", "x"))),
                new StepSpec("tidy", params("description", "Remove it", "dn", "x"))), "t.yaml");
        assertThat(described.failureSummary())
                .isEqualTo("t.yaml, step 1 (a: \"Grant roles\"): a went wrong; "
                        + "t.yaml, cleanup entry 1 (tidy: \"Remove it\"): tidy went wrong");

        ScenarioResult plain = engine.run(scenario(new StepSpec("a", params("dn", "x"))), "t.yaml");
        assertThat(plain.failureSummary()).isEqualTo("t.yaml, step 1 (a): a went wrong");
    }

    private static final Evidence FIRST = LdapEvidence.exists(null, "cn=first", false);
    private static final Evidence LAST = LdapEvidence.exists(null, "cn=last", true);

    @Test
    @DisplayName("A step's evidence is recorded in its result, failed or not")
    void testEvidenceRecorded() {
        StepType probe = new StepType() {
            public String name() { return "probe"; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return StepOutcome.failure("no").withEvidence(LAST);
            }
        };

        ScenarioResult result = engineWith(probe).run(scenario(new StepSpec("probe", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.steps().get(0).evidence()).isEqualTo(LAST);
    }

    @Test
    @DisplayName("Under wait:, only the last attempt's evidence is kept")
    void testOnlyLastAttemptEvidence() {
        AtomicInteger calls = new AtomicInteger();
        StepType probe = new StepType() {
            public String name() { return "probe"; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return calls.getAndIncrement() < 2
                        ? StepOutcome.failure("not yet").withEvidence(FIRST)
                        : StepOutcome.success("arrived").withEvidence(LAST);
            }
        };

        ScenarioResult result = engineWith(probe).run(scenario(new StepSpec("probe",
                Map.of("dn", "x", "wait", Map.of("timeout", "5s", "poll", "1s")))), "t.yaml");

        assertThat(result.steps().get(0).attempts()).isEqualTo(3);
        assertThat(result.steps().get(0).evidence()).isEqualTo(LAST);
    }

    @Test
    @DisplayName("A step that throws records no evidence")
    void testThrowingStepHasNoEvidence() {
        StepType probe = new StepType() {
            public String name() { return "probe"; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                throw new IllegalStateException("boom");
            }
        };

        ScenarioResult result = engineWith(probe).run(scenario(new StepSpec("probe", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.steps().get(0).evidence()).isNull();
    }

    @Test
    @DisplayName("A missing as: capture keeps the step's evidence")
    void testMissingCaptureKeepsEvidence() {
        StepType probe = new StepType() {
            public String name() { return "probe"; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return StepOutcome.success("ok").withEvidence(LAST);
            }
        };

        ScenarioResult result = engineWith(probe).run(scenario(
                new StepSpec("probe", Map.of("dn", "x", "as", "v"))), "t.yaml");

        assertThat(result.steps().get(0).success()).isFalse();
        assertThat(result.steps().get(0).evidence()).isEqualTo(LAST);
    }

    @Test
    @DisplayName("A recursive expression in a parameter fails its step; cleanup still runs and a result is recorded")
    void testRecursiveExpressionFailsStep() {
        ScenarioEngine engine = engineWith(recordingAction("a"), recordingAction("undo"));

        ScenarioResult result = engine.run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "js:{ (function f() { return 1 + f(); })() }"))),
                new StepSpec("undo", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).success()).isFalse();
        assertThat(result.steps().get(0).message()).contains("recursed too deeply");
        assertThat(events).contains("run:undo:x");
    }

    /** A stand-in for a converted step: any existing key will do. */
    private static final Message PROBE = Message.of("engine.wait.lastObserved", "probe");

    /** Fails every attempt with {@link #PROBE}, as an ordinary or a final failure. */
    private static StepType keyedFailing(String name, boolean isFinal) {
        return new StepType() {
            public String name() { return name; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return isFinal ? StepOutcome.finalFailure(PROBE) : StepOutcome.failure(PROBE);
            }
        };
    }

    @Test
    @DisplayName("A step's keyed message reaches its result with the key and arguments")
    void testKeyedMessageRecorded() {
        ScenarioResult result = engineWith(keyedFailing("keyed", false)).run(scenario(
                new StepSpec("keyed", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.steps().get(0).message()).isEqualTo("probe (last observed value after retrying)");
        assertThat(result.steps().get(0).messageKey()).isEqualTo("engine.wait.lastObserved");
        assertThat(result.steps().get(0).messageArgs()).containsExactly("probe");
    }

    @Test
    @DisplayName("The wait suffix is keyed and wraps the step's own message, so both translate")
    void testWaitSuffixIsKeyed() {
        ScenarioResult result = engineWith(keyedFailing("keyed", false)).run(scenario(
                new StepSpec("keyed", Map.of("dn", "x", "wait", Map.of("timeout", "1s", "poll", "1s")))), "t.yaml");

        StepResult step = result.steps().get(0);
        assertThat(step.messageKey()).isEqualTo("engine.wait.lastObserved");
        assertThat(step.messageArgs()).containsExactly(PROBE);
        assertThat(step.message())
                .isEqualTo("probe (last observed value after retrying) (last observed value after retrying)");
        assertThat(Messages.render(step.messageDetail(), Locale.of("es")))
                .isEqualTo("probe (último valor observado tras reintentar) (último valor observado tras reintentar)");
    }

    @Test
    @DisplayName("The not-retried suffix is keyed and wraps the step's own message")
    void testNotRetriedSuffixIsKeyed() {
        ScenarioResult result = engineWith(keyedFailing("keyed", true)).run(scenario(
                new StepSpec("keyed", Map.of("dn", "x", "wait", Map.of("timeout", "10s", "poll", "1s")))), "t.yaml");

        StepResult step = result.steps().get(0);
        assertThat(step.messageKey()).isEqualTo("engine.wait.notRetried");
        assertThat(step.messageArgs()).containsExactly(PROBE);
        assertThat(step.message())
                .isEqualTo("probe (last observed value after retrying) (not retried: waiting cannot change this)");
        assertThat(Messages.render(step.messageDetail(), Locale.of("es")))
                .endsWith("(sin reintentos: esperar no puede cambiar este resultado)");
    }

    @Test
    @DisplayName("A plain-text failure under wait still gets a keyed suffix, with the text as its argument")
    void testPlainTextUnderWaitIsKeyed() {
        ScenarioResult result = engineWith(eventuallyPassing("check", Integer.MAX_VALUE)).run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait", Map.of("timeout", "1s", "poll", "1s")))), "t.yaml");

        StepResult step = result.steps().get(0);
        assertThat(step.message()).isEqualTo("not yet (last observed value after retrying)");
        assertThat(step.messageKey()).isEqualTo("engine.wait.lastObserved");
        assertThat(step.messageArgs()).containsExactly("not yet");
        assertThat(Messages.render(step.messageDetail(), Locale.of("es")))
                .isEqualTo("not yet (último valor observado tras reintentar)");
    }

    @Test
    @DisplayName("A passing step under wait keeps its own keyed message, without a suffix")
    void testPassingUnderWaitKeepsKey() {
        StepType passing = new StepType() {
            public String name() { return "keyed"; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return StepOutcome.success(PROBE);
            }
        };
        ScenarioResult result = engineWith(passing).run(scenario(
                new StepSpec("keyed", Map.of("dn", "x", "wait", Boolean.TRUE))), "t.yaml");

        StepResult step = result.steps().get(0);
        assertThat(step.success()).isTrue();
        assertThat(step.message()).isEqualTo("probe (last observed value after retrying)");
        assertThat(step.messageKey()).isEqualTo("engine.wait.lastObserved");
        assertThat(step.messageArgs()).containsExactly("probe");
    }

    @Test
    @DisplayName("A cleanup entry's keyed message reaches its result with the key and arguments")
    void testKeyedCleanupMessageRecorded() {
        ScenarioResult result = engineWith(recordingAction("a"), keyedFailing("keyed", false)).run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "x"))),
                new StepSpec("keyed", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.cleanup().get(0).status()).isEqualTo(CleanupStatus.FAILED);
        assertThat(result.cleanup().get(0).message()).isEqualTo("probe (last observed value after retrying)");
        assertThat(result.cleanup().get(0).messageKey()).isEqualTo("engine.wait.lastObserved");
        assertThat(result.cleanup().get(0).messageArgs()).containsExactly("probe");
    }

    @Test
    @DisplayName("A plain-text step leaves the key and arguments out")
    void testPlainTextHasNoKey() {
        ScenarioResult result = engineWith(failingAction("f")).run(scenario(
                new StepSpec("f", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.steps().get(0).messageKey()).isNull();
        assertThat(result.steps().get(0).messageArgs()).isNull();
        assertThat(result.steps().get(0).messageDetail()).isNull();
    }

    private static final Locale ES = Locale.of("es");

    @Test
    @DisplayName("A step that throws on an unresolvable reference reads in Spanish, English unchanged")
    void testStepThrewRendersInSpanish() {
        ScenarioResult result = engineWith(recordingAction("a")).run(scenario(
                new StepSpec("a", Map.of("dn", "${user.nope}"))), "t.yaml");

        StepResult step = result.steps().get(0);
        assertThat(step.message())
                .isEqualTo("step 'a' threw IllegalArgumentException: cannot resolve ${user.nope} — no value for 'nope'");
        assertThat(Messages.render(step.messageDetail(), ES))
                .isEqualTo("el paso 'a' lanzó IllegalArgumentException: no se puede resolver ${user.nope}"
                        + " — no hay valor para 'nope'");
    }

    @Test
    @DisplayName("Fixture setup, capture and cleanup messages read in Spanish")
    void testEngineMessagesRenderInSpanish() {
        Scenario badFixture = new Scenario("Test", null,
                Map.of("user", new FixtureSpec("randmo-user", Map.of())),
                List.of(new StepSpec("a", Map.of("dn", "x"))));
        ScenarioResult fixture = engineWith(recordingAction("a")).run(badFixture, "t.yaml");
        assertThat(fixture.failureDetail())
                .isEqualTo("fixture setup failed: unknown generator 'randmo-user'. Available: [random-user]");
        assertThat(fixture.failureSummary(ES)).isEqualTo("t.yaml: la preparación de fixtures falló:"
                + " generador desconocido 'randmo-user'. Disponibles: [random-user]");

        StepType silent = new StepType() {
            public String name() { return "silent"; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return StepOutcome.success("ok");
            }
        };
        ScenarioResult capture = engineWith(silent).run(scenario(
                new StepSpec("silent", Map.of("dn", "x", "as", "created"))), "t.yaml");
        assertThat(capture.steps().get(0).message())
                .isEqualTo("step 'silent' declares as: 'created' but produced no value to capture");
        assertThat(Messages.render(capture.steps().get(0).messageDetail(), ES))
                .isEqualTo("el paso 'silent' declara as: 'created' pero no produjo ningún valor que capturar");

        ScenarioResult cleanup = engineWith(failingAction("a"), recordingAction("b")).run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "x"))),
                new StepSpec("b", Map.of("dn", "${never.dn}"))), "t.yaml");
        assertThat(cleanup.cleanup().get(0).message())
                .isEqualTo("skipped: [never] never bound, so what this would clean up never happened");
        assertThat(Messages.render(cleanup.cleanup().get(0).messageDetail(), ES))
                .isEqualTo("omitida: [never] nunca se asignó, así que lo que esto limpiaría nunca ocurrió");
    }

    @Test
    @DisplayName("Unknown step types and a set: step read in Spanish")
    void testUnknownTypeAndSetRenderInSpanish() {
        ScenarioResult set = engineWith(recordingAction("a")).run(scenario(
                new StepSpec("set", Map.of("x", "1")),
                new StepSpec("nope", Map.of())), "t.yaml");

        assertThat(set.steps().get(0).message()).isEqualTo("bound [x]");
        assertThat(Messages.render(set.steps().get(0).messageDetail(), ES)).isEqualTo("se asignó [x]");
        assertThat(set.steps().get(1).message()).isEqualTo("unknown step type 'nope'. Available: [a]");
        assertThat(Messages.render(set.steps().get(1).messageDetail(), ES))
                .isEqualTo("tipo de paso desconocido 'nope'. Disponibles: [a]");
    }

    @Test
    @DisplayName("A keyed IllegalArgumentException is still named IllegalArgumentException at every engine site")
    void testKeyedArgumentExceptionKeepsItsName() {
        StepType throwing = new StepType() {
            public String name() { return "probe"; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                throw new KeyedArgumentException(Message.of("engine.ref.unresolved", "${x}", "x"));
            }
        };
        ScenarioResult waited = engineWith(throwing).run(scenario(
                new StepSpec("probe", Map.of("dn", "x", "wait", Map.of("timeout", "1s", "poll", "1s")))), "t.yaml");
        assertThat(waited.steps().get(0).message()).isEqualTo(
                "threw IllegalArgumentException: cannot resolve ${x} — no value for 'x' (last observed value after retrying)");
        assertThat(Messages.render(waited.steps().get(0).messageDetail(), ES)).isEqualTo(
                "lanzó IllegalArgumentException: no se puede resolver ${x} — no hay valor para 'x'"
                        + " (último valor observado tras reintentar)");

        ScenarioResult cleanup = engineWith(recordingAction("a")).run(withCleanup(
                List.of(new StepSpec("a", Map.of("dn", "x"))),
                new StepSpec("a", Map.of("dn", "${user.nope}"))), "t.yaml");
        assertThat(cleanup.cleanup().get(0).message())
                .isEqualTo("cleanup 'a' threw IllegalArgumentException: cannot resolve ${user.nope} — no value for 'nope'");
        assertThat(Messages.render(cleanup.cleanup().get(0).messageDetail(), ES)).isEqualTo(
                "la limpieza 'a' lanzó IllegalArgumentException: no se puede resolver ${user.nope} — no hay valor para 'nope'");
    }
}
