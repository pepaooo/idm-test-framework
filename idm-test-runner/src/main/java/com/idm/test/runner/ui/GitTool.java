package com.idm.test.runner.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The installed {@code git}, run as a program with an argument list (no shell) and a time limit.
 * Whether it is installed is asked once, on first use. Git runs without the {@code GIT_*}
 * variables of the workbench's own environment ({@code GIT_DIR}, {@code GIT_WORK_TREE}, …), so
 * {@code git init} always acts on the new folder and not on a repository the workbench was started
 * from.
 */
public final class GitTool {

    private static final long VERSION_SECONDS = 5;
    private static final long INIT_SECONDS = 30;

    private final String program;
    private volatile Boolean available;

    /** The {@code git} on the {@code PATH}. */
    public GitTool() {
        this("git");
    }

    /** {@code program} in place of {@code git} (tests: a program that does not exist). */
    GitTool(String program) {
        this.program = program;
    }

    /** True when {@code git --version} runs and succeeds. */
    public boolean available() {
        Boolean known = available;
        if (known == null) {
            known = run(List.of(program, "--version"), null, VERSION_SECONDS);
            available = known;
        }
        return known;
    }

    /** Runs {@code git init} in {@code folder}; false when git is missing, fails or takes too long. */
    public boolean init(Path folder) {
        return run(List.of(program, "init", "--quiet"), folder, INIT_SECONDS);
    }

    /** Removes every {@code GIT_*} variable (any case, for Windows) from a child's environment. */
    static void withoutGitVariables(Map<String, String> environment) {
        environment.keySet().removeIf(name -> name.toUpperCase(java.util.Locale.ROOT).startsWith("GIT_"));
    }

    private static boolean run(List<String> command, Path folder, long seconds) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            withoutGitVariables(builder.environment());
            if (folder != null) {
                builder.directory(folder.toFile());
            }
            process = builder.start();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | RuntimeException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
