package com.idm.test.engine.steps;

import com.idm.test.connector.exec.client.CommandConnector;
import com.idm.test.connector.exec.client.CommandDefinition;
import com.idm.test.connector.exec.client.RegisteredCommand;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.Check;
import com.idm.test.engine.spi.CommandApproval;
import com.idm.test.engine.spi.CommandEvidence;
import com.idm.test.engine.spi.FailureKind;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives exec.run and exec.verify against a real {@link CommandConnector} running {@link ExecTestProgram}. */
@DisplayName("Exec step types")
class ExecStepsTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    @TempDir
    Path dir;

    private Path status;
    private Path marker;
    private ExecRunStep run;
    private ExecVerifyStep verify;

    private static List<String> program(String... instructions) {
        try {
            String classes = Path.of(ExecTestProgram.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
            List<String> args = new ArrayList<>(List.of("-cp", classes, ExecTestProgram.class.getName()));
            args.addAll(List.of(instructions));
            return args;
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CommandDefinition command(List<String> args, Map<String, String> params, Duration timeout,
                                             List<Integer> exitCodes) {
        return new CommandDefinition(JAVA, args, params, null, null, timeout, null, exitCodes, "UTF-8");
    }

    @BeforeEach
    void setUp() throws Exception {
        status = Files.writeString(dir.resolve("status.txt"), "starting");
        marker = dir.resolve("marker.txt");
        Map<String, CommandDefinition> definitions = new LinkedHashMap<>();
        definitions.put("status", command(program("file", status.toString()), Map.of(), null, null));
        definitions.put("echo", command(program("args", "{value}"), Map.of("value", "[A-Za-z0-9 ]*"), null, null));
        definitions.put("fail", command(program("say", "boom", "shout", "bad thing", "exit", "3"), Map.of(), null, null));
        definitions.put("tolerant", command(program("exit", "1"), Map.of(), null, List.of(0, 1)));
        definitions.put("slow", command(program("sleep", "60000"), Map.of(), Duration.ofSeconds(2), null));
        definitions.put("set-pw", command(program("args", "--for", "{user}", "{password}"),
                Map.of("user", "[a-z]+", "password", ".+"), null, null));
        definitions.put("touch-it", command(program("touch", marker.toString()), Map.of(), null, null));
        Map<String, RegisteredCommand> registered = new LinkedHashMap<>();
        definitions.forEach((name, definition) -> registered.put(name, RegisteredCommand.of(definition, definition)));
        CommandConnector connector = new CommandConnector(registered, dir);
        // Everything is approved here except touch-it, whose approval is for another definition.
        CommandApproval approval = (name, hash) -> !name.equals("touch-it");
        run = new ExecRunStep(connector, approval);
        verify = new ExecVerifyStep(connector, approval);
    }

    /** A mutable, ordered parameter map, as the engine hands a step. */
    private static Map<String, Object> params(Object... keysAndValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            params.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return params;
    }

    private static void assertSetupFinal(StepOutcome outcome) {
        assertThat(outcome.success()).as(outcome.message()).isFalse();
        assertThat(outcome.isFinal()).as(outcome.message()).isTrue();
        assertThat(outcome.kind()).isEqualTo(FailureKind.SETUP);
    }

    @Nested
    @DisplayName("exec.run")
    class Run {

        @Test
        @DisplayName("Should run a command and capture its exit code, outputs and duration")
        void testRunAndCapture() {
            StepOutcome outcome = run.execute(params("command", "echo", "params", params("value", "two words")), true);

            assertThat(outcome.success()).as(outcome.message()).isTrue();
            assertThat(outcome.message()).startsWith("ran command 'echo': exit 0");
            @SuppressWarnings("unchecked")
            Map<String, Object> captured = (Map<String, Object>) outcome.value();
            assertThat(captured).containsEntry("exitCode", 0).containsKeys("stdout", "stderr", "durationMillis");
            assertThat(((String) captured.get("stdout")).strip()).isEqualTo("<two words>");
            assertThat(outcome.evidence()).isInstanceOfSatisfying(CommandEvidence.class, evidence -> {
                assertThat(evidence.command()).isEqualTo("echo");
                assertThat(evidence.argv()).endsWith("two words");
                assertThat(evidence.exitCode()).isZero();
            });
        }

        @Test
        @DisplayName("Should accept numbers and booleans as parameter values, as their text")
        void testScalarParams() {
            StepOutcome outcome = run.execute(params("command", "echo", "params", params("value", 42)), true);

            assertThat(outcome.success()).as(outcome.message()).isTrue();
        }

        @Test
        @DisplayName("Should report an exit code outside exit-codes as a finding, with the output as evidence")
        void testExitCodeFinding() {
            StepOutcome outcome = run.execute(params("command", "fail"), false);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.isFinal()).isFalse();
            assertThat(outcome.kind()).isEqualTo(FailureKind.FINDING);
            assertThat(outcome.message()).isEqualTo("exec.run: command 'fail' exited with 3; it should exit with one of [0]");
            assertThat(outcome.evidence()).isInstanceOfSatisfying(CommandEvidence.class, evidence ->
                    assertThat(evidence.stdout()).contains("boom"));
            assertThat(run.execute(params("command", "tolerant"), false).success()).isTrue();
        }

        @Test
        @DisplayName("Should report a timeout as a setup problem, not final")
        void testTimeout() {
            StepOutcome outcome = run.execute(params("command", "slow"), false);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.isFinal()).isFalse();
            assertThat(outcome.kind()).isEqualTo(FailureKind.SETUP);
            assertThat(outcome.message()).contains("'slow'").contains("timed out");
        }

        @Test
        @DisplayName("Should never start a command whose definition is not approved on this machine")
        void testNotApproved() {
            StepOutcome outcome = run.execute(params("command", "touch-it"), false);

            assertSetupFinal(outcome);
            assertThat(outcome.message()).contains("not approved").contains("idmtest commands approve");
            assertThat(marker).doesNotExist();
            assertThat(outcome.evidence()).isEqualTo(CommandEvidence.notStarted("touch-it"));
        }

        @Test
        @DisplayName("Should refuse an unknown command, a bad parameter and malformed params as final setup problems")
        void testFinalFailures() {
            assertSetupFinal(run.execute(params("command", "nope"), false));
            assertThat(run.execute(params("command", "nope"), false).message())
                    .contains("Command 'nope' is not configured");
            assertSetupFinal(run.execute(params("command", "echo", "params", params("value", "a;b")), false));
            assertSetupFinal(run.execute(params("command", "echo", "params", "a"), false));
            assertSetupFinal(run.execute(params("command", "echo", "params", params("value", List.of("a"))), false));
            assertSetupFinal(run.execute(params("command", "echo", "params", params("other", "a")), false));
        }

        @Test
        @DisplayName("Should mask a secret-named parameter in the evidence")
        void testSecretParameterMasked() {
            StepOutcome outcome = run.execute(params("command", "set-pw",
                    "params", params("user", "jdoe", "password", "fake-new-pw")), false);

            assertThat(outcome.success()).as(outcome.message()).isTrue();
            CommandEvidence evidence = (CommandEvidence) outcome.evidence();
            assertThat(evidence.argv()).endsWith("--for", "jdoe", "***");
            // The program echoes its arguments: the value is masked in its output as well.
            assertThat(evidence.stdout()).contains("<***>").contains("<jdoe>");
            assertThat(evidence.toString()).doesNotContain("fake-new-pw");
        }

        @Test
        @DisplayName("Should word a problem in Spanish")
        void testSpanish() {
            StepOutcome outcome = run.execute(params("command", "touch-it"), false);

            assertThat(Messages.render(outcome.detail(), Locale.forLanguageTag("es"))).contains("no está aprobado");
        }
    }

    @Nested
    @DisplayName("exec.verify")
    class Verify {

        private StepOutcome check(String command, Map<String, Object> expect) {
            return verify.execute(params("command", command, "expect", expect), true);
        }

        @Test
        @DisplayName("Should compare exit code and output, normalising line breaks, and name each check")
        void testPasses() throws Exception {
            Files.writeString(status, "Running\r\nok\r\n");

            // stderr is checked with contains: a JVM started with JAVA_TOOL_OPTIONS prints a notice there.
            StepOutcome outcome = check("status", params(
                    "exitCode", 0,
                    "stdout", params("equals", "Running\nok"),
                    "stderr", params("contains", "")));
            StepOutcome contains = check("status", params("stdout", params("contains", "running", "ignoreCase", true)));
            StepOutcome matches = check("status", params("stdout", params("matches", "(?s)Run.*ok"),
                    "exitCode", List.of(0, 2)));

            assertThat(outcome.success()).as(outcome.message()).isTrue();
            assertThat(outcome.checks()).extracting(Check::name).containsExactly("exit code", "stdout equals", "stderr contains");
            assertThat(contains.success()).as(contains.message()).isTrue();
            assertThat(matches.success()).as(matches.message()).isTrue();
            assertThat(outcome.message()).isEqualTo("verified command 'status': 3 check(s)");
        }

        @Test
        @DisplayName("Should report every difference as a retryable finding, naming the first")
        void testMismatch() {
            StepOutcome outcome = check("fail", params("exitCode", 0, "stdout", params("contains", "fine")));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.isFinal()).isFalse();
            assertThat(outcome.kind()).isEqualTo(FailureKind.FINDING);
            assertThat(outcome.message()).isEqualTo("exec.verify on command 'fail': exit code expected 0, found 3");
            assertThat(outcome.checks()).extracting(Check::matched).containsExactly(false, false);
            assertThat(outcome.evidence()).isInstanceOf(CommandEvidence.class);
        }

        @Test
        @DisplayName("Should require matches to match the whole output, as ldap.verify does")
        void testMatchesWhole() throws Exception {
            Files.writeString(status, "Running");

            assertThat(check("status", params("stdout", params("matches", "Run"))).success()).isFalse();
            assertThat(check("status", params("stdout", params("matches", "Run.*"))).success()).isTrue();
        }

        @Test
        @DisplayName("Should refuse an expect that would verify nothing, before running anything")
        void testVacuous() {
            List<Object> bad = new ArrayList<>(List.of("yes", Map.of(), params("exit", 0), params("exitCode", "zero"),
                    params("exitCode", List.of()), params("stdout", "running"), params("stdout", Map.of()),
                    params("stdout", params("ignoreCase", true)), params("stdout", params("matches", "[unclosed")),
                    params("stdout", params("matches", "x", "ignoreCase", true)),
                    params("stdout", params("startsWith", "x"))));
            Map<String, Object> nullValue = new HashMap<>();
            nullValue.put("contains", null);
            bad.add(params("stdout", nullValue));

            for (Object expect : bad) {
                StepOutcome outcome = verify.execute(params("command", "touch-it", "expect", expect), false);
                assertSetupFinal(outcome);
                assertThat(outcome.message()).as(String.valueOf(expect)).doesNotContain("not approved");
            }
            assertThat(marker).doesNotExist();
        }

        @Test
        @DisplayName("Should refuse an unapproved command finally, and keep a timeout retryable")
        void testFinalAndTimeout() {
            assertSetupFinal(check("touch-it", params("exitCode", 0)));
            StepOutcome slow = check("slow", params("exitCode", 0));
            assertThat(slow.isFinal()).isFalse();
            assertThat(slow.kind()).isEqualTo(FailureKind.SETUP);
            assertThat(verify.retryable()).isTrue();
            assertThat(run.retryable()).isFalse();
        }
    }

    @Test
    @DisplayName("Should wait through the engine until the command reports what the scenario expects")
    void testWaitsThroughEngine() throws Exception {
        Map<String, StepType> registry = new LinkedHashMap<>();
        registry.put(run.name(), run);
        registry.put(verify.name(), verify);
        ScenarioEngine engine = new ScenarioEngine(registry, new FixtureFactory(new TestDataGenerator()),
                new TestExecutor(), Duration.ofSeconds(20), Duration.ofMillis(200), new ExpressionEvaluator());
        Thread driver = new Thread(() -> {
            try {
                Thread.sleep(1500);
                Files.writeString(status, "running");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        driver.start();

        ScenarioResult result = engine.run(new Scenario("Driver state", null, Map.of(), List.of(
                new StepSpec("exec.verify", params("command", "status",
                        "expect", params("stdout", params("equals", "running")), "wait", true)))), "t.yaml");
        driver.join();

        assertThat(result.success()).as(String.valueOf(result.failureSummary())).isTrue();
        assertThat(result.steps().get(0).attempts()).isGreaterThan(1);
    }
}
