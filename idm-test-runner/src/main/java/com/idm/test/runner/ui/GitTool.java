package com.idm.test.runner.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The installed {@code git}, run as a program with an argument list (no shell) and a time limit.
 * Whether it is installed is asked once, on first use. Git runs without the {@code GIT_*}
 * variables of the workbench's own environment ({@code GIT_DIR}, {@code GIT_WORK_TREE}, …), so
 * {@code git init} always acts on the new folder and not on a repository the workbench was started
 * from, and {@link #state} reads the workspace's own repository.
 */
public final class GitTool {

    private static final long VERSION_SECONDS = 5;
    private static final long INIT_SECONDS = 30;
    private static final long STATE_SECONDS = 10;

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

    /** A file's state in the workspace's git repository. */
    public enum FileState {
        /** Committed, and the same on disk and in the index as in the last commit. */
        CLEAN,
        /** Tracked, with changes since the last commit (on disk or staged), or added and never committed. */
        CHANGED,
        /** Not tracked: new, or ignored. */
        UNTRACKED,
        /** Not known: the workspace has no {@code .git}, git is not installed, or it failed. */
        NONE
    }

    /**
     * The git state of {@code relativePath} (workspace-relative, with {@code /}) in {@code workspace}'s
     * own repository; {@link FileState#NONE} when the workspace has no {@code .git}. Only reads: git
     * runs with {@code --no-optional-locks}, so it does not refresh or write the index, with
     * {@code core.fsmonitor=false}, so a configured fsmonitor hook or daemon is never started, and with
     * {@code --literal-pathspecs}, so {@code * ? [} in a name are not patterns.
     */
    public FileState state(Path workspace, String relativePath) {
        if (!Files.exists(workspace.resolve(".git"), LinkOption.NOFOLLOW_LINKS) || !available()) {
            return FileState.NONE;
        }
        Result tracked = exec(List.of(program, "-c", "core.fsmonitor=false", "--no-optional-locks",
                "--literal-pathspecs", "ls-files", "--error-unmatch", "--", relativePath),
                workspace, STATE_SECONDS);
        if (tracked == null) {
            return FileState.NONE;
        }
        if (tracked.exitCode() == 1) {
            return FileState.UNTRACKED; // "did not match any file(s) known to git"
        }
        if (tracked.exitCode() != 0) {
            return FileState.NONE;
        }
        Result status = exec(List.of(program, "-c", "core.fsmonitor=false", "--no-optional-locks",
                "--literal-pathspecs", "status", "--porcelain=v1", "-z", "--untracked-files=no", "--", relativePath),
                workspace, STATE_SECONDS);
        if (status == null || status.exitCode() != 0) {
            return FileState.NONE;
        }
        return status.output().length == 0 ? FileState.CLEAN : FileState.CHANGED;
    }

    /** Removes every {@code GIT_*} variable (any case, for Windows) from a child's environment. */
    static void withoutGitVariables(Map<String, String> environment) {
        environment.keySet().removeIf(name -> name.toUpperCase(java.util.Locale.ROOT).startsWith("GIT_"));
    }

    private static boolean run(List<String> command, Path folder, long seconds) {
        Result result = exec(command, folder, seconds);
        return result != null && result.exitCode() == 0;
    }

    /** How a git command ended: its exit code and what it wrote to its standard output. */
    private record Result(int exitCode, byte[] output) {
    }

    /**
     * Runs {@code command} in {@code folder} (null: the current one) without the {@code GIT_*}
     * variables, its standard error discarded; null when it cannot start, takes longer than
     * {@code seconds} (it is then killed) or its output cannot be read.
     */
    private static Result exec(List<String> command, Path folder, long seconds) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            withoutGitVariables(builder.environment());
            if (folder != null) {
                builder.directory(folder.toFile());
            }
            process = builder.start();
            process.getOutputStream().close();
            InputStream stdout = process.getInputStream();
            // read on another thread, so a git that hangs with its output open still meets the time limit
            CompletableFuture<byte[]> output = new CompletableFuture<>();
            Thread.ofVirtual().name("idmtest-git-output").start(() -> {
                try (stdout) {
                    output.complete(stdout.readAllBytes());
                } catch (IOException e) {
                    output.completeExceptionally(e);
                }
            });
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                return null;
            }
            return new Result(process.exitValue(), output.get(seconds, TimeUnit.SECONDS));
        } catch (IOException | RuntimeException | ExecutionException | TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
