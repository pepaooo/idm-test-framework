package com.idm.test.connector.exec.client;

import com.idm.test.connector.exec.client.CommandProblem.Kind;
import com.idm.test.core.model.OperationResult;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Runs commands registered by name ({@code idm.test.commands.<name>}), for {@code exec.run} and
 * {@code exec.verify}.
 * <p>
 * No shell is ever involved: the executable and its arguments go to the operating system as a list. A
 * scenario's parameter fills only an argument that is exactly {@code {name}}, after its value has
 * matched the declared pattern in full, and is then exactly one argument; it never reaches the
 * executable, the environment or the working directory. On Windows a batch file ({@code .cmd},
 * {@code .bat}) is refused, because {@code cmd.exe} would parse its arguments again.
 * <p>
 * Every run has a closed stdin, stdout and stderr read on their own threads and capped, and a timeout
 * after which the process and its descendants are killed. A command that runs and exits, with any exit
 * code, or that times out, is a successful {@link OperationResult} whose {@link CommandResult} says so:
 * the steps decide what that means. A command that cannot run is a failure carrying a
 * {@link CommandProblem}.
 * <p>
 * This connector does not decide whether a command may run: the caller checks the approval of
 * {@link RegisteredCommand#hash()} first (ADR 0008).
 */
public final class CommandConnector {

    /** How long to wait for the output readers once the process has ended or been killed. */
    private static final Duration DRAIN = Duration.ofSeconds(5);

    private final Map<String, RegisteredCommand> commands;
    private final Path defaultWorkingDir;
    private final boolean windows;

    /**
     * @param commands          the registered commands, by name
     * @param defaultWorkingDir where a command without {@code working-dir} starts: the workspace folder
     */
    public CommandConnector(Map<String, RegisteredCommand> commands, Path defaultWorkingDir) {
        this(commands, defaultWorkingDir,
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    CommandConnector(Map<String, RegisteredCommand> commands, Path defaultWorkingDir, boolean windows) {
        this.commands = Collections.unmodifiableMap(new LinkedHashMap<>(commands == null ? Map.of() : commands));
        this.defaultWorkingDir = defaultWorkingDir;
        this.windows = windows;
    }

    /** @return the registered command {@code name}, if there is one */
    public Optional<RegisteredCommand> command(String name) {
        return Optional.ofNullable(commands.get(name));
    }

    /** @return the registered command names, sorted */
    public Set<String> commandNames() {
        return Collections.unmodifiableSet(new TreeSet<>(commands.keySet()));
    }

    /**
     * Runs a registered command.
     *
     * @param name   the command's name
     * @param params the scenario's parameters, by name; {@code null} for none
     */
    public OperationResult<CommandResult> run(String name, Map<String, String> params) {
        RegisteredCommand command = commands.get(name);
        if (command == null) {
            return CommandProblem.of(Kind.NOT_CONFIGURED, name, commandNames()).toResult();
        }
        CommandDefinition definition = command.definition();
        Optional<CommandProblem> bad = checkDefinition(name, definition);
        if (bad.isPresent()) {
            return bad.get().toResult();
        }
        List<String> argv = new ArrayList<>();
        argv.add(definition.executable());
        Map<String, String> given = params == null ? Map.of() : params;
        for (String key : given.keySet()) {
            if (!definition.params().containsKey(key)) {
                return CommandProblem.of(Kind.UNKNOWN_PARAM, name, key, new TreeSet<>(definition.params().keySet()))
                        .toResult();
            }
        }
        for (String arg : definition.args()) {
            if (!CommandDefinition.isPlaceholder(arg)) {
                argv.add(arg);
                continue;
            }
            String parameter = CommandDefinition.placeholderName(arg);
            String value = given.get(parameter);
            if (value == null) {
                return CommandProblem.of(Kind.MISSING_PARAM, name, parameter).toResult();
            }
            String pattern = definition.params().get(parameter);
            if (!Pattern.compile(pattern).matcher(value).matches()) {
                return CommandProblem.of(Kind.PARAM_REJECTED, name, parameter, pattern).toResult();
            }
            argv.add(value);
        }

        Path workingDir;
        try {
            workingDir = definition.workingDir() == null ? defaultWorkingDir : Path.of(definition.workingDir());
        } catch (InvalidPathException e) {
            return CommandProblem.of(Kind.NO_WORKING_DIR, name).toResult();
        }
        if (workingDir == null || !Files.isDirectory(workingDir)) {
            return CommandProblem.of(Kind.NO_WORKING_DIR, name).toResult();
        }
        Charset charset = charset(definition);
        return start(name, definition, argv, workingDir, charset);
    }

    private Optional<CommandProblem> checkDefinition(String name, CommandDefinition definition) {
        String executable = definition.executable();
        if (executable == null || executable.isBlank()) {
            return Optional.of(CommandProblem.of(Kind.BAD_DEFINITION, name, "no executable"));
        }
        if (executable.contains("{") || executable.contains("}")) {
            return Optional.of(CommandProblem.of(Kind.BAD_DEFINITION, name,
                    "the executable cannot be a parameter"));
        }
        String lower = executable.toLowerCase(Locale.ROOT);
        if (windows && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
            return Optional.of(CommandProblem.of(Kind.BAD_DEFINITION, name,
                    "a batch file (.cmd, .bat) runs through cmd.exe, which would parse its arguments again; "
                            + "register a program instead"));
        }
        Set<String> used = new HashSet<>();
        for (String arg : definition.args()) {
            if (CommandDefinition.isPlaceholder(arg)) {
                String parameter = CommandDefinition.placeholderName(arg);
                if (!definition.params().containsKey(parameter)) {
                    return Optional.of(CommandProblem.of(Kind.BAD_DEFINITION, name,
                            "the argument {" + parameter + "} has no declared parameter"));
                }
                used.add(parameter);
            }
        }
        for (Map.Entry<String, String> param : definition.params().entrySet()) {
            if (!used.contains(param.getKey())) {
                return Optional.of(CommandProblem.of(Kind.BAD_DEFINITION, name,
                        "the parameter " + param.getKey() + " is declared but no argument is {" + param.getKey() + "}"));
            }
            if (param.getValue() == null) {
                return Optional.of(CommandProblem.of(Kind.BAD_DEFINITION, name,
                        "the parameter " + param.getKey() + " has no pattern"));
            }
            try {
                Pattern.compile(param.getValue());
            } catch (PatternSyntaxException e) {
                return Optional.of(CommandProblem.of(Kind.BAD_DEFINITION, name,
                        "the pattern of " + param.getKey() + " is not valid: " + e.getDescription()));
            }
        }
        if (definition.timeout().isNegative() || definition.timeout().isZero()) {
            return Optional.of(CommandProblem.of(Kind.BAD_DEFINITION, name, "the timeout must be positive"));
        }
        if (definition.maxOutput() <= 0 || definition.maxOutput() > Integer.MAX_VALUE - 8) {
            return Optional.of(CommandProblem.of(Kind.BAD_DEFINITION, name, "max-output is out of range"));
        }
        if (definition.encoding() != null) {
            try {
                Charset.forName(definition.encoding());
            } catch (IllegalArgumentException e) {
                return Optional.of(CommandProblem.of(Kind.BAD_DEFINITION, name,
                        "unknown encoding " + definition.encoding()));
            }
        }
        return Optional.empty();
    }

    private static Charset charset(CommandDefinition definition) {
        if (definition.encoding() != null) {
            return Charset.forName(definition.encoding());
        }
        String nativeEncoding = System.getProperty("native.encoding");
        try {
            return nativeEncoding == null ? Charset.defaultCharset() : Charset.forName(nativeEncoding);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private OperationResult<CommandResult> start(String name, CommandDefinition definition, List<String> argv,
                                                 Path workingDir, Charset charset) {
        ProcessBuilder builder = new ProcessBuilder(argv).directory(workingDir.toFile());
        builder.environment().putAll(definition.env());
        long started = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            // "Cannot run program "x": error=2, No such file or directory" (Unix),
            // "CreateProcess error=2, The system cannot find the file specified" (Windows)
            if (message.contains("error=2,") || message.contains("error=2 ") || message.endsWith("error=2")
                    || message.contains("No such file") || message.contains("cannot find the file")) {
                return CommandProblem.of(Kind.NOT_FOUND, name, definition.executable()).toResult();
            }
            return CommandProblem.of(Kind.START_FAILED, name, reason(e)).toResult();
        } catch (RuntimeException e) {
            return CommandProblem.of(Kind.START_FAILED, name, reason(e)).toResult();
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // the process may already have exited; stdin is closed either way
        }
        OutputBuffer out = new OutputBuffer(process.getInputStream(), definition.maxOutput());
        OutputBuffer err = new OutputBuffer(process.getErrorStream(), definition.maxOutput());
        Thread outReader = reader(out, name, "out");
        Thread errReader = reader(err, name, "err");

        boolean timedOut;
        try {
            timedOut = !process.waitFor(definition.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timedOut = true;
        }
        if (timedOut) {
            killTree(process);
        }
        join(outReader);
        join(errReader);
        if (outReader.isAlive() || errReader.isAlive()) {
            // A descendant that outlived the command still holds its output open: stop it too.
            killTree(process);
        }
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        int exitCode = timedOut ? -1 : process.exitValue();
        return OperationResult.success(new CommandResult(exitCode, new String(out.kept(), charset),
                new String(err.kept(), charset), out.total(), err.total(), millis, timedOut, argv),
                "Command '" + name + "' " + (timedOut ? "timed out" : "exited with " + exitCode));
    }

    private static Thread reader(OutputBuffer buffer, String name, String stream) {
        Thread thread = new Thread(buffer, "idmtest-exec-" + name + "-" + stream);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(DRAIN.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Kills the process's descendants first, so none is left behind reparented, then the process. */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(DRAIN.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
