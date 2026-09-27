package com.idm.test.engine;

import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.result.CleanupStatus;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
}
