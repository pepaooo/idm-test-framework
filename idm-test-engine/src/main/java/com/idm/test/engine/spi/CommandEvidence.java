package com.idm.test.engine.spi;

import com.idm.test.engine.result.Secrets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * One registered command as an {@code exec.*} step ran it: its name, the command line it started, its
 * exit code, how long it took, whether it was stopped at its timeout, and what it wrote.
 * <p>
 * The constructor masks an argument that follows a secret-looking option ({@code -password x}) and the
 * value of one written as {@code --token=x} or {@code /ApiKey:x}; the factory also masks the arguments a
 * secret-named parameter filled. Known secret values ({@code secrets.env}) are masked by the runner, as
 * for all evidence. Output is cut at {@link Evidence#MAX_BODY_CHARS} characters per stream. Applying the
 * constructor again to its own output changes nothing.
 *
 * @param command        the command's registered name
 * @param argv           the executable and its arguments, masked; empty when it never started
 * @param exitCode       its exit code, {@code -1} when it timed out, or null when it never started
 * @param durationMillis from start to end, or null when it never started
 * @param timedOut       whether it was stopped at its timeout, or null when it never started
 * @param stdout         what it wrote to stdout, cut; null when it never started
 * @param stderr         what it wrote to stderr, cut; null when it never started
 * @param stdoutBytes    every byte it wrote to stdout, or null
 * @param stderrBytes    every byte it wrote to stderr, or null
 */
public record CommandEvidence(String command, List<String> argv, Integer exitCode, Long durationMillis,
                              Boolean timedOut, String stdout, String stderr, Long stdoutBytes, Long stderrBytes)
        implements Evidence {

    public CommandEvidence {
        argv = masked(argv);
        stdout = EvidenceMasking.truncate(stdout, MAX_BODY_CHARS);
        stderr = EvidenceMasking.truncate(stderr, MAX_BODY_CHARS);
    }

    /**
     * A command that ran (or was stopped at its timeout).
     *
     * @param secretPositions positions in {@code argv} filled by a secret-named parameter
     */
    public static CommandEvidence ran(String command, List<String> argv, Set<Integer> secretPositions, int exitCode,
                                      long durationMillis, boolean timedOut, String stdout, String stderr,
                                      long stdoutBytes, long stderrBytes) {
        List<String> shown = new ArrayList<>(argv);
        for (int position : secretPositions) {
            if (position >= 0 && position < shown.size()) {
                shown.set(position, Secrets.MASK);
            }
        }
        return new CommandEvidence(command, shown, exitCode, durationMillis, timedOut, stdout, stderr,
                stdoutBytes, stderrBytes);
    }

    /** A command that could not be started (unknown, not approved, a bad parameter). */
    public static CommandEvidence notStarted(String command) {
        return new CommandEvidence(command, List.of(), null, null, null, null, null, null, null);
    }

    private static List<String> masked(List<String> argv) {
        if (argv == null) {
            return List.of();
        }
        List<String> masked = new ArrayList<>(argv.size());
        boolean maskNext = false;
        for (String arg : argv) {
            String value = arg == null ? "" : arg;
            if (maskNext) {
                masked.add(Secrets.MASK);
                maskNext = false;
                continue;
            }
            int separator = separatorOf(value);
            if (separator > 0 && Secrets.isSecret(optionName(value.substring(0, separator)))) {
                masked.add(value.substring(0, separator + 1) + Secrets.MASK);
                continue;
            }
            if (isOption(value) && Secrets.isSecret(optionName(value))) {
                maskNext = true;
            }
            masked.add(EvidenceMasking.truncate(value, MAX_VALUE_CHARS));
        }
        return Collections.unmodifiableList(masked);
    }

    /** {@code -x}, {@code --x} or {@code /x}: an option, as most tools on any platform spell one. */
    private static boolean isOption(String arg) {
        return arg.length() > 1 && (arg.charAt(0) == '-' || arg.charAt(0) == '/');
    }

    /** Where an option's inline value starts ({@code --token=x}, {@code /ApiKey:x}), or -1. */
    private static int separatorOf(String arg) {
        if (!isOption(arg)) {
            return -1;
        }
        int equals = arg.indexOf('=');
        int colon = arg.indexOf(':');
        if (equals < 0) {
            return colon;
        }
        return colon < 0 ? equals : Math.min(equals, colon);
    }

    private static String optionName(String option) {
        int start = 0;
        while (start < option.length() && (option.charAt(start) == '-' || option.charAt(start) == '/')) {
            start++;
        }
        return option.substring(start);
    }
}
