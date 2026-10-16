package com.idm.test.runner;

import com.idm.test.engine.spi.Message;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Asks Git whether a file is tracked. */
public final class GitTracking {

    private GitTracking() {
    }

    /** The warning shown when {@code secrets.env} is tracked by Git. */
    public static Message trackedWarning() {
        return Message.of("runner.secrets.tracked");
    }

    /**
     * True when {@code file} is tracked by the Git repository containing {@code workspace}. False when
     * {@code git} is not installed, the folder is no repository or the file is not tracked; never throws.
     */
    public static boolean isTracked(Path workspace, Path file) {
        Process process = null;
        try {
            Path absolute = workspace.toAbsolutePath().normalize();
            Path relative = absolute.relativize(file.toAbsolutePath().normalize());
            process = new ProcessBuilder("git", "-C", absolute.toString(), "ls-files", "--error-unmatch",
                    relative.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
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
