package com.idm.test.engine.steps;

import com.idm.test.connector.exec.client.CommandResult;
import com.idm.test.engine.spi.Check;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code exec.verify}'s {@code expect}: {@code exitCode} (a number or a list), and {@code stdout} and
 * {@code stderr}, each with {@code equals}, {@code contains} or {@code matches} and optionally
 * {@code ignoreCase}, as in {@code ldap.verify}: {@code matches} must match the whole output, and
 * {@code ignoreCase} applies to {@code equals} and {@code contains}. Output is compared with
 * {@code \r\n} read as {@code \n} and trailing line breaks removed, so one check works on every
 * operating system.
 */
final class OutputExpectations {

    private static final String EXIT_CODE = "exitCode";
    private static final String STDOUT = "stdout";
    private static final String STDERR = "stderr";
    private static final Set<String> KEYS = Set.of(EXIT_CODE, STDOUT, STDERR);
    private static final String EQUALS = "equals";
    private static final String CONTAINS = "contains";
    private static final String MATCHES = "matches";
    private static final String IGNORE_CASE = "ignoreCase";
    private static final Set<String> MATCHERS = Set.of(EQUALS, CONTAINS, MATCHES, IGNORE_CASE);
    /** Characters of output quoted in a message; the evidence holds the rest. */
    private static final int QUOTED = 200;

    private OutputExpectations() {
    }

    /** The checks made and the first difference, if any. */
    record Comparison(List<Check> checks, Message mismatch) {
    }

    /**
     * An {@code expect} that asserts nothing, has unknown keys or an unusable condition fails before the
     * command runs: it would pass having compared nothing, or never pass for a reason waiting cannot
     * change.
     *
     * @return the final failure, or null when {@code raw} is usable
     */
    static StepOutcome rejectMalformed(Object raw, String command) {
        if (!(raw instanceof Map<?, ?> expect)) {
            return StepOutcome.finalFailure(Message.of("step.exec.verify.expectNotMap", command, new TreeSet<>(KEYS)));
        }
        if (expect.isEmpty()) {
            return StepOutcome.finalFailure(Message.of("step.exec.verify.expectEmpty", command, new TreeSet<>(KEYS)));
        }
        Set<String> unknown = new TreeSet<>();
        expect.keySet().forEach(key -> unknown.add(String.valueOf(key)));
        unknown.removeAll(KEYS);
        if (!unknown.isEmpty()) {
            return StepOutcome.finalFailure(Message.of("step.exec.verify.unknownKeys", command, unknown,
                    new TreeSet<>(KEYS)));
        }
        if (expect.containsKey(EXIT_CODE) && exitCodes(expect.get(EXIT_CODE)) == null) {
            return StepOutcome.finalFailure(Message.of("step.exec.verify.badExitCode", command));
        }
        for (String stream : List.of(STDOUT, STDERR)) {
            if (!expect.containsKey(stream)) {
                continue;
            }
            StepOutcome rejection = rejectStream(command, stream, expect.get(stream));
            if (rejection != null) {
                return rejection;
            }
        }
        return null;
    }

    private static StepOutcome rejectStream(String command, String stream, Object raw) {
        if (!(raw instanceof Map<?, ?> matcher) || matcher.isEmpty()) {
            return StepOutcome.finalFailure(Message.of("step.exec.verify.streamShape", command, stream));
        }
        Set<String> unknown = new TreeSet<>();
        matcher.keySet().forEach(key -> unknown.add(String.valueOf(key)));
        unknown.removeAll(MATCHERS);
        if (!unknown.isEmpty()) {
            return StepOutcome.finalFailure(Message.of("step.exec.verify.unknownMatcher", command, stream, unknown,
                    new TreeSet<>(MATCHERS)));
        }
        for (Map.Entry<?, ?> entry : matcher.entrySet()) {
            if (entry.getValue() == null) {
                return StepOutcome.finalFailure(Message.of("step.exec.verify.nullCondition", command, stream,
                        entry.getKey()));
            }
        }
        if (!matcher.containsKey(EQUALS) && !matcher.containsKey(CONTAINS) && !matcher.containsKey(MATCHES)) {
            return StepOutcome.finalFailure(Message.of("step.exec.verify.streamShape", command, stream));
        }
        if (matcher.containsKey(IGNORE_CASE)) {
            if (!(matcher.get(IGNORE_CASE) instanceof Boolean)) {
                return StepOutcome.finalFailure(Message.of("step.exec.verify.streamShape", command, stream));
            }
            if (!matcher.containsKey(EQUALS) && !matcher.containsKey(CONTAINS)) {
                return StepOutcome.finalFailure(Message.of("step.exec.verify.ignoreCaseAlone", command, stream));
            }
        }
        if (matcher.containsKey(MATCHES)) {
            try {
                Pattern.compile(String.valueOf(matcher.get(MATCHES)));
            } catch (PatternSyntaxException e) {
                return StepOutcome.finalFailure(Message.of("step.exec.verify.badPattern", command, stream,
                        e.getDescription()));
            }
        }
        return null;
    }

    /** @return the expected exit codes, or null when {@code raw} is not a whole number or a list of them */
    private static List<Integer> exitCodes(Object raw) {
        List<?> values = raw instanceof List<?> list ? list : List.of(raw);
        if (values.isEmpty()) {
            return null;
        }
        List<Integer> codes = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
                return null;
            }
            codes.add(number.intValue());
        }
        return codes;
    }

    /** Compares a result with an {@code expect} {@link #rejectMalformed} accepted. */
    static Comparison compare(Map<?, ?> expect, CommandResult result, String command) {
        List<Check> checks = new ArrayList<>();
        Message mismatch = null;
        if (expect.containsKey(EXIT_CODE)) {
            List<Integer> codes = exitCodes(expect.get(EXIT_CODE));
            boolean matched = codes.contains(result.exitCode());
            Object expected = codes.size() == 1 ? codes.get(0) : codes;
            checks.add(Check.compared("exit code", expected, result.exitCode(), matched));
            if (!matched) {
                mismatch = Message.of("step.exec.verify.mismatch", command, "exit code", expected, result.exitCode());
            }
        }
        for (String stream : List.of(STDOUT, STDERR)) {
            if (!expect.containsKey(stream)) {
                continue;
            }
            Map<?, ?> matcher = (Map<?, ?>) expect.get(stream);
            String output = normalised(STDOUT.equals(stream) ? result.stdout() : result.stderr());
            boolean ignoreCase = Boolean.TRUE.equals(matcher.get(IGNORE_CASE));
            for (String condition : List.of(EQUALS, CONTAINS, MATCHES)) {
                if (!matcher.containsKey(condition)) {
                    continue;
                }
                String expected = String.valueOf(matcher.get(condition));
                boolean matched = switch (condition) {
                    case EQUALS -> ignoreCase ? output.equalsIgnoreCase(normalised(expected))
                            : output.equals(normalised(expected));
                    case CONTAINS -> ignoreCase
                            ? output.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT))
                            : output.contains(expected);
                    default -> Pattern.compile(expected).matcher(output).matches();
                };
                String name = stream + " " + condition;
                checks.add(Check.compared(name, expected, quoted(output), matched));
                if (!matched && mismatch == null) {
                    mismatch = Message.of("step.exec.verify.mismatch", command, name, expected, quoted(output));
                }
            }
        }
        return new Comparison(checks, mismatch);
    }

    static String normalised(String output) {
        if (output == null) {
            return "";
        }
        String text = output.replace("\r\n", "\n");
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String quoted(String output) {
        return output.length() <= QUOTED ? output : output.substring(0, QUOTED) + "…";
    }
}
