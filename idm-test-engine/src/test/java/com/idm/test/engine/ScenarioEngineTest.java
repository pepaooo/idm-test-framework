package com.idm.test.engine;

import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
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

    /** Records that it ran, and registers an undo that records too. */
    private StepType recordingAction(String name) {
        return new StepType() {
            public String name() { return name; }
            public StepOutcome execute(Map<String, Object> params) {
                events.add("run:" + name + ":" + params.get("dn"));
                return StepOutcome.success("ok", () -> events.add("undo:" + name));
            }
        };
    }

    private StepType failingAction(String name) {
        return new StepType() {
            public String name() { return name; }
            public StepOutcome execute(Map<String, Object> params) {
                events.add("run:" + name);
                return StepOutcome.failure(name + " went wrong");
            }
        };
    }

    /** Fails the first {@code failures} calls, then succeeds. */
    private StepType eventuallyPassing(String name, int failures) {
        AtomicInteger calls = new AtomicInteger();
        return new StepType() {
            public String name() { return name; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params) {
                return calls.getAndIncrement() < failures
                        ? StepOutcome.failure("not yet")
                        : StepOutcome.success("arrived");
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
    @DisplayName("Should unwind cleanup in reverse order, including after a failure")
    void testCleanupRunsInReverse() {
        ScenarioEngine engine = engineWith(
                recordingAction("a"), recordingAction("b"), failingAction("c"));

        engine.run(scenario(
                new StepSpec("a", Map.of("dn", "x")),
                new StepSpec("b", Map.of("dn", "y")),
                new StepSpec("c", Map.of("dn", "z"))), "t.yaml");

        assertThat(events).containsExactly(
                "run:a:x", "run:b:y", "run:c", "undo:b", "undo:a");
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
    @DisplayName("Should keep the original failure when a cleanup action also fails")
    void testCleanupFailureDoesNotMaskOriginal() {
        StepType breaksOnUndo = new StepType() {
            public String name() { return "a"; }
            public StepOutcome execute(Map<String, Object> params) {
                return StepOutcome.success("ok", () -> {
                    throw new IllegalStateException("undo exploded");
                });
            }
        };
        ScenarioEngine engine = engineWith(breaksOnUndo, failingAction("b"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", Map.of("dn", "x")),
                new StepSpec("b", Map.of("dn", "y"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.failureSummary()).contains("b went wrong");
    }

    @Test
    @DisplayName("Should fail the step and still run cleanup when a param reference can't resolve")
    void testUnresolvableReferenceFailsStepAndStillCleansUp() {
        ScenarioEngine engine = engineWith(recordingAction("a"), recordingAction("b"));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("a", Map.of("dn", "x")),
                new StepSpec("b", Map.of("dn", "${user.bogus}"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(1).success()).isFalse();
        assertThat(result.steps().get(1).message()).contains("b").contains("bogus");
        // "a" ran and must still be cleaned up even though "b" blew up with an exception.
        assertThat(events).containsExactly("run:a:x", "undo:a");
    }

    @Test
    @DisplayName("Should fail the step (not abort the run) when execute() throws")
    void testThrowingStepFailsButDoesNotAbort() {
        StepType throwing = new StepType() {
            public String name() { return "boom"; }
            public StepOutcome execute(Map<String, Object> params) {
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
        assertThat(events).containsExactly("run:a:x", "undo:a");
    }

    @Test
    @DisplayName("Should report the exception message when a retried assertion throws every attempt")
    void testRetriedAssertionThrowingEveryAttemptReportsMessage() {
        StepType alwaysThrows = new StepType() {
            public String name() { return "check"; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params) {
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
            public StepOutcome execute(Map<String, Object> params) {
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
            public StepOutcome execute(Map<String, Object> params) {
                return StepOutcome.success("ok", null, value);
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
            public StepOutcome execute(Map<String, Object> params) {
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
            public StepOutcome execute(Map<String, Object> params) {
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
            public StepOutcome execute(Map<String, Object> params) {
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
            public StepOutcome execute(Map<String, Object> params) {
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
            public StepOutcome execute(Map<String, Object> params) {
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
}
