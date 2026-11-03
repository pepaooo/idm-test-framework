package com.idm.test.connector.exec.client;

import com.idm.test.connector.exec.client.CommandProblem.Kind;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs real processes: the {@link TestProgram} with the current JVM, so every operating system runs
 * the same tests.
 */
class CommandConnectorTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    @TempDir
    Path workspace;

    private static String testClasses() {
        try {
            return Path.of(TestProgram.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The test program with {@code instructions} as its arguments. */
    private static List<String> program(String... instructions) {
        List<String> args = new ArrayList<>(List.of("-cp", testClasses(), TestProgram.class.getName()));
        args.addAll(List.of(instructions));
        return args;
    }

    private static CommandDefinition definition(List<String> args, Map<String, String> params) {
        return new CommandDefinition(JAVA, args, params, null, null, null, null, null, null);
    }

    private CommandConnector connector(Map<String, CommandDefinition> definitions) {
        return connector(definitions, false);
    }

    private CommandConnector connector(Map<String, CommandDefinition> definitions, boolean windows) {
        Map<String, RegisteredCommand> commands = new LinkedHashMap<>();
        definitions.forEach((name, definition) -> commands.put(name, RegisteredCommand.of(definition, definition)));
        return new CommandConnector(commands, workspace, windows);
    }

    private CommandResult ran(OperationResult<CommandResult> result) {
        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        return result.getDataOrThrow();
    }

    private static CommandProblem problem(OperationResult<?> result) {
        assertThat(result.isSuccess()).as("expected a failure").isFalse();
        return CommandProblem.of(result).orElseThrow();
    }

    private static List<String> lines(String text) {
        return text.lines().toList();
    }

    @Nested
    @DisplayName("Arguments")
    class Arguments {

        @ParameterizedTest
        @ValueSource(strings = {"two words", "it's \"quoted\"", "a;rm -rf x", "x && echo hacked", "a|b", "$(id)",
                "`id`", "%PATH%", "^caret^", "-flag", "--delete=all", "back\\slash\\", "trailing\\", "ñandú 日本",
                "", " "})
        @DisplayName("Should pass each parameter value as one literal argument, never through a shell")
        void testValueIsOneLiteralArgument(String value) {
            CommandConnector connector = connector(Map.of("echo",
                    definition(program("args", "fixed", "{value}", "end"), Map.of("value", "(?s).*"))));

            CommandResult result = ran(connector.run("echo", Map.of("value", value)));

            assertThat(lines(result.stdout())).containsExactly("<fixed>", "<" + value + ">", "<end>");
            assertThat(result.exitCode()).isZero();
            assertThat(result.argv()).endsWith("fixed", value, "end");
        }

        @Test
        @DisplayName("Should keep a placeholder inside a longer argument literal")
        void testPlaceholderOnlyWhenWhole() {
            CommandConnector connector = connector(Map.of("echo",
                    definition(program("args", "--name={value}", "{value}x", "{value}"), Map.of("value", "[a-z]+"))));

            CommandResult result = ran(connector.run("echo", Map.of("value", "abc")));

            assertThat(lines(result.stdout())).containsExactly("<--name={value}>", "<{value}x>", "<abc>");
        }

        @Test
        @DisplayName("Should refuse a value that does not match its pattern in full")
        void testPatternMustMatchInFull() {
            CommandConnector connector = connector(Map.of("echo",
                    definition(program("args", "{driver}"), Map.of("driver", "[a-z]+"))));

            CommandProblem partial = problem(connector.run("echo", Map.of("driver", "abc --delete")));
            CommandProblem empty = problem(connector.run("echo", Map.of("driver", "")));

            assertThat(partial.kind()).isEqualTo(Kind.PARAM_REJECTED);
            assertThat(partial.errorType()).isEqualTo(ErrorType.CONFIGURATION_ERROR);
            assertThat(partial.values()).containsExactly("echo", "driver", "[a-z]+");
            assertThat(partial.english()).doesNotContain("--delete");
            assertThat(empty.kind()).isEqualTo(Kind.PARAM_REJECTED);
        }

        @Test
        @DisplayName("Should refuse unknown and missing parameters")
        void testUnknownAndMissing() {
            CommandConnector connector = connector(Map.of("echo",
                    definition(program("args", "{a}", "{b}"), Map.of("a", ".*", "b", ".*"))));

            assertThat(problem(connector.run("echo", Map.of("a", "1", "b", "2", "c", "3"))).kind())
                    .isEqualTo(Kind.UNKNOWN_PARAM);
            assertThat(problem(connector.run("echo", Map.of("a", "1"))).kind()).isEqualTo(Kind.MISSING_PARAM);
            assertThat(problem(connector.run("echo", null)).kind()).isEqualTo(Kind.MISSING_PARAM);
        }

        @Test
        @DisplayName("Should refuse a definition whose placeholders and parameters do not match, or whose pattern is broken")
        void testBadDefinitions() {
            CommandConnector connector = connector(Map.of(
                    "undeclared", definition(program("args", "{x}"), Map.of()),
                    "unused", definition(program("args"), Map.of("x", ".*")),
                    "broken", definition(program("args", "{x}"), Map.of("x", "[unclosed")),
                    "blank", new CommandDefinition(" ", List.of(), Map.of(), null, null, null, null, null, null)));

            for (String name : List.of("undeclared", "unused", "broken", "blank")) {
                Map<String, String> params = name.equals("undeclared") || name.equals("blank") ? Map.of() : Map.of("x", "1");
                assertThat(problem(connector.run(name, params)).kind()).as(name).isEqualTo(Kind.BAD_DEFINITION);
            }
        }

        @Test
        @DisplayName("Should never let a parameter reach the executable")
        void testExecutableIsNeverAParameter() {
            CommandConnector connector = connector(Map.of("sneaky",
                    new CommandDefinition("{x}", List.of(), Map.of("x", ".*"), null, null, null, null, null, null)));

            assertThat(problem(connector.run("sneaky", Map.of("x", JAVA))).kind()).isEqualTo(Kind.BAD_DEFINITION);
        }

        @Test
        @DisplayName("Should refuse a batch file on Windows, where cmd.exe would parse the arguments again")
        void testBatchFilesRefusedOnWindows() {
            Map<String, CommandDefinition> batch = Map.of(
                    "cmd", new CommandDefinition("C:\\tools\\restart.CMD", List.of(), Map.of(), null, null, null, null, null, null),
                    "bat", new CommandDefinition("restart.bat", List.of(), Map.of(), null, null, null, null, null, null));

            CommandConnector windows = connector(batch, true);
            assertThat(problem(windows.run("cmd", Map.of())).kind()).isEqualTo(Kind.BAD_DEFINITION);
            assertThat(problem(windows.run("bat", Map.of())).english()).contains("batch file");

            // Elsewhere such a name is just a file name; this one does not exist.
            assertThat(problem(connector(batch, false).run("bat", Map.of())).kind()).isEqualTo(Kind.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Running")
    class Running {

        @Test
        @DisplayName("Should keep stdout and stderr apart and report the exit code")
        void testStreamsAndExitCode() {
            CommandConnector connector = connector(Map.of("mixed",
                    definition(program("say", "to out", "shout", "to err", "exit", "3"), Map.of())));

            CommandResult result = ran(connector.run("mixed", Map.of()));

            assertThat(result.exitCode()).isEqualTo(3);
            assertThat(result.stdout()).isEqualTo("to out" + System.lineSeparator());
            assertThat(result.stderr()).contains("to err").doesNotContain("to out");
            assertThat(result.timedOut()).isFalse();
            assertThat(result.durationMillis()).isNotNegative();
        }

        @Test
        @DisplayName("Should keep at most max-output bytes of each stream, count them all, and never block on a flood")
        void testOutputCap() {
            CommandConnector connector = connector(Map.of("flood", new CommandDefinition(JAVA,
                    program("out", "3000000", "err", "500"), Map.of(), null, null, null, 1000L, null, null)));

            CommandResult result = ran(connector.run("flood", Map.of()));

            assertThat(result.stdout()).hasSize(1000);
            assertThat(result.stdoutBytes()).isEqualTo(3_000_000);
            // stderr may also hold the JVM's own notices (JAVA_TOOL_OPTIONS), so count rather than compare.
            assertThat(result.stderrBytes()).isGreaterThanOrEqualTo(500);
            assertThat(result.stderr().length()).isLessThanOrEqualTo(1000);
            assertThat(result.exitCode()).isZero();
        }

        @Test
        @DisplayName("Should decode output in the definition's encoding")
        void testEncoding() {
            CommandConnector connector = connector(Map.of(
                    "latin1", new CommandDefinition(JAVA, program("bytes", "ISO-8859-1", "Peña"), Map.of(),
                            null, null, null, null, null, "ISO-8859-1"),
                    "utf8", new CommandDefinition(JAVA, program("bytes", "UTF-8", "Peña 日本"), Map.of(),
                            null, null, null, null, null, "UTF-8")));

            assertThat(ran(connector.run("latin1", Map.of())).stdout()).isEqualTo("Peña");
            assertThat(ran(connector.run("utf8", Map.of())).stdout()).isEqualTo("Peña 日本");
        }

        @Test
        @DisplayName("Should add env to the inherited environment")
        void testEnvironment() {
            CommandConnector connector = connector(Map.of("env", new CommandDefinition(JAVA,
                    program("env", "IDMTEST_PROBE", "env", "PATH"), Map.of(), Map.of("IDMTEST_PROBE", "probe-value"),
                    null, null, null, null, null)));

            List<String> lines = lines(ran(connector.run("env", Map.of())).stdout());

            assertThat(lines.get(0)).isEqualTo("probe-value");
            assertThat(lines.get(1)).isNotEqualTo("<unset>");
        }

        @Test
        @DisplayName("Should start in the workspace by default, or in working-dir, which must exist")
        void testWorkingDirectory() throws Exception {
            Path elsewhere = Files.createDirectory(workspace.resolve("elsewhere"));
            CommandConnector connector = connector(Map.of(
                    "here", definition(program("pwd"), Map.of()),
                    "there", new CommandDefinition(JAVA, program("pwd"), Map.of(), null, elsewhere.toString(),
                            null, null, null, null),
                    "nowhere", new CommandDefinition(JAVA, program("pwd"), Map.of(), null,
                            workspace.resolve("missing").toString(), null, null, null, null)));

            assertThat(Path.of(ran(connector.run("here", Map.of())).stdout().strip()).toRealPath())
                    .isEqualTo(workspace.toRealPath());
            assertThat(Path.of(ran(connector.run("there", Map.of())).stdout().strip()).toRealPath())
                    .isEqualTo(elsewhere.toRealPath());
            assertThat(problem(connector.run("nowhere", Map.of())).kind()).isEqualTo(Kind.NO_WORKING_DIR);
        }

        @Test
        @DisplayName("Should close stdin, so a command that reads it gets end of input instead of hanging")
        void testStdinClosed() {
            CommandConnector connector = connector(Map.of("read", new CommandDefinition(JAVA,
                    List.of("-cp", testClasses(), StdinReader.class.getName()), Map.of(), null, null,
                    Duration.ofSeconds(20), null, null, null)));

            CommandResult result = ran(connector.run("read", Map.of()));

            assertThat(result.timedOut()).isFalse();
            assertThat(result.stdout().strip()).isEqualTo("read -1");
        }

        @Test
        @DisplayName("Should report an executable that cannot be found as a setup problem")
        void testMissingExecutable() {
            CommandConnector connector = connector(Map.of("ghost", new CommandDefinition(
                    "idmtest-no-such-program-7Qx", List.of(), Map.of(), null, null, null, null, null, null)));

            CommandProblem problem = problem(connector.run("ghost", Map.of()));

            assertThat(problem.kind()).isEqualTo(Kind.NOT_FOUND);
            assertThat(problem.errorType()).isEqualTo(ErrorType.CONFIGURATION_ERROR);
        }

        @Test
        @DisplayName("Should report an unknown command with the ones that exist")
        void testUnknownCommand() {
            CommandProblem problem = problem(connector(Map.of("a", definition(program(), Map.of()),
                    "b", definition(program(), Map.of()))).run("c", Map.of()));

            assertThat(problem.kind()).isEqualTo(Kind.NOT_CONFIGURED);
            assertThat(problem.english()).contains("[a, b]");
        }
    }

    @Nested
    @DisplayName("Timeouts")
    class Timeouts {

        @Test
        @DisplayName("Should stop a command at its timeout and say so, with the output so far")
        void testTimeout() {
            CommandConnector connector = connector(Map.of("slow", new CommandDefinition(JAVA,
                    program("say", "started", "sleep", "60000"), Map.of(), null, null, Duration.ofSeconds(3),
                    null, null, null)));

            long start = System.nanoTime();
            CommandResult result = ran(connector.run("slow", Map.of()));
            long seconds = Duration.ofNanos(System.nanoTime() - start).toSeconds();

            assertThat(result.timedOut()).isTrue();
            assertThat(result.stdout()).contains("started");
            assertThat(seconds).isLessThan(20);
        }

        @Test
        @DisplayName("Should kill the command's children on timeout too")
        void testTimeoutKillsChildren() throws Exception {
            Path pidFile = workspace.resolve("child.pid");
            CommandConnector connector = connector(Map.of("parent", new CommandDefinition(JAVA,
                    program("child", "60000", pidFile.toString(), "sleep", "60000"), Map.of(), null, null,
                    Duration.ofSeconds(5), null, null, null)));

            CommandResult result = ran(connector.run("parent", Map.of()));

            assertThat(result.timedOut()).isTrue();
            long childPid = Long.parseLong(Files.readString(pidFile).strip());
            Optional<ProcessHandle> child = ProcessHandle.of(childPid);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (child.isPresent() && child.get().isAlive() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(child.map(ProcessHandle::isAlive).orElse(false)).as("child still alive").isFalse();
        }
    }

    @Nested
    @DisplayName("Definition hash")
    class Hash {

        @Test
        @DisplayName("Should hash the definition as written: secret values do not count, everything else does")
        void testHash() {
            CommandDefinition written = new CommandDefinition("dxcmd", List.of("-password", "${IDM_DXCMD_PASSWORD}",
                    "-getstate", "{driver}"), Map.of("driver", "[a-z]+"), Map.of("A", "1"), null, null, null, null, null);
            CommandDefinition resolvedOne = new CommandDefinition("dxcmd", List.of("-password", "secret-1",
                    "-getstate", "{driver}"), Map.of("driver", "[a-z]+"), Map.of("A", "1"), null, null, null, null, null);
            CommandDefinition resolvedTwo = new CommandDefinition("dxcmd", List.of("-password", "secret-2",
                    "-getstate", "{driver}"), Map.of("driver", "[a-z]+"), Map.of("A", "1"), null, null, null, null, null);

            assertThat(RegisteredCommand.of(resolvedOne, written).hash())
                    .isEqualTo(RegisteredCommand.of(resolvedTwo, written).hash())
                    .isEqualTo(DefinitionHash.of(written))
                    .hasSize(64);
            assertThat(DefinitionHash.of(written)).isNotEqualTo(DefinitionHash.of(resolvedOne));
        }

        @Test
        @DisplayName("Should change with every field, and not with the order of maps")
        void testHashFields() {
            CommandDefinition base = new CommandDefinition("x", List.of("a", "b"), Map.of("p", "1", "q", "2"),
                    Map.of("E", "1", "F", "2"), "w", Duration.ofSeconds(5), 10L, List.of(0, 1), "UTF-8");
            List<CommandDefinition> variants = List.of(
                    new CommandDefinition("y", base.args(), base.params(), base.env(), base.workingDir(), base.timeout(), base.maxOutput(), base.exitCodes(), base.encoding()),
                    new CommandDefinition("x", List.of("b", "a"), base.params(), base.env(), base.workingDir(), base.timeout(), base.maxOutput(), base.exitCodes(), base.encoding()),
                    new CommandDefinition("x", List.of("ab"), base.params(), base.env(), base.workingDir(), base.timeout(), base.maxOutput(), base.exitCodes(), base.encoding()),
                    new CommandDefinition("x", base.args(), Map.of("p", "1", "q", "3"), base.env(), base.workingDir(), base.timeout(), base.maxOutput(), base.exitCodes(), base.encoding()),
                    new CommandDefinition("x", base.args(), base.params(), Map.of("E", "1"), base.workingDir(), base.timeout(), base.maxOutput(), base.exitCodes(), base.encoding()),
                    new CommandDefinition("x", base.args(), base.params(), base.env(), "v", base.timeout(), base.maxOutput(), base.exitCodes(), base.encoding()),
                    new CommandDefinition("x", base.args(), base.params(), base.env(), base.workingDir(), Duration.ofSeconds(6), base.maxOutput(), base.exitCodes(), base.encoding()),
                    new CommandDefinition("x", base.args(), base.params(), base.env(), base.workingDir(), base.timeout(), 11L, base.exitCodes(), base.encoding()),
                    new CommandDefinition("x", base.args(), base.params(), base.env(), base.workingDir(), base.timeout(), base.maxOutput(), List.of(0), base.encoding()),
                    new CommandDefinition("x", base.args(), base.params(), base.env(), base.workingDir(), base.timeout(), base.maxOutput(), base.exitCodes(), "UTF-16"));

            for (CommandDefinition variant : variants) {
                assertThat(DefinitionHash.of(variant)).as(variant.toString()).isNotEqualTo(DefinitionHash.of(base));
            }
            Map<String, String> reordered = new LinkedHashMap<>();
            reordered.put("q", "2");
            reordered.put("p", "1");
            assertThat(DefinitionHash.of(new CommandDefinition("x", base.args(), reordered, base.env(), base.workingDir(),
                    base.timeout(), base.maxOutput(), base.exitCodes(), base.encoding())))
                    .isEqualTo(DefinitionHash.of(base));
        }
    }
}
