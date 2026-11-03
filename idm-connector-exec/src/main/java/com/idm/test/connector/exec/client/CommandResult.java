package com.idm.test.connector.exec.client;

import java.util.List;

/**
 * What a command did.
 *
 * @param exitCode       its exit code; {@code -1} when it timed out
 * @param stdout         the first {@code max-output} bytes of stdout, decoded
 * @param stderr         the first {@code max-output} bytes of stderr, decoded
 * @param stdoutBytes    every byte it wrote to stdout, kept or not
 * @param stderrBytes    every byte it wrote to stderr, kept or not
 * @param durationMillis from start to end
 * @param timedOut       whether it was stopped at its timeout (with its descendants)
 * @param argv           the executable and arguments it was started with, secrets included: mask before
 *                       showing
 */
public record CommandResult(int exitCode, String stdout, String stderr, long stdoutBytes, long stderrBytes,
                            long durationMillis, boolean timedOut, List<String> argv) {

    public CommandResult {
        argv = List.copyOf(argv);
    }
}
