package com.idm.test.runner.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The installed {@code git}, run as a program with an argument list (no shell) and a time limit.
 * Whether it is installed is asked once, on first use.
 */
public final class GitTool {

    private static final long VERSION_SECONDS = 5;
    private static final long INIT_SECONDS = 30;

    private volatile Boolean available;

    /** True when {@code git --version} runs and succeeds. */
    public boolean available() {
        Boolean known = available;
        if (known == null) {
            known = run(List.of("git", "--version"), null, VERSION_SECONDS);
            available = known;
        }
        return known;
    }

    /** Runs {@code git init} in {@code folder}; false when git is missing, fails or takes too long. */
    public boolean init(Path folder) {
        return run(List.of("git", "init", "--quiet"), folder, INIT_SECONDS);
    }

    private static boolean run(List<String> command, Path folder, long seconds) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
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
