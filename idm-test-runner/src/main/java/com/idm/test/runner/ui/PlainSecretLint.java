package com.idm.test.runner.ui;

import com.idm.test.engine.Interpolator;
import com.idm.test.engine.result.Secrets;
import com.idm.test.engine.spi.Message;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags a password written in plain text in a scenario or a {@code config/*.yml} file: a literal
 * value under a secret-looking key (the engine's {@link Secrets} rule, as the reports use, less the keys
 * that only say how a secret is fetched: see {@link #isSecretKey}), at any
 * depth, including the items of a list under such a key. A {@code ${…}} reference, a
 * {@code js:{ … }} expression, an empty value and a boolean are not flagged. Reads the YAML as a
 * token stream, so a file broken further down is still checked up to the error; never throws.
 * <p>
 * The warning's text is fixed and never contains the value. It depends on the file, because the
 * advice differs: a settings file can take {@code ${IDM_TEST_…}} from {@code secrets.env}
 * ({@code runner.ui.lint.plainSecret}), while a scenario's {@code ${…}} reaches only its fixtures
 * and bindings ({@code runner.ui.lint.plainSecretScenario}). The {@code key} is
 * {@value #KEY} for both.
 */
public final class PlainSecretLint {

    public static final String KEY = "ui.lint.plainSecret";
    private static final Message SETTINGS_MESSAGE = Message.of("runner.ui.lint.plainSecret");
    private static final Message SCENARIO_MESSAGE = Message.of("runner.ui.lint.plainSecretScenario");
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    /** {@code key: value} on one line, possibly a list item, for {@link #literals} when the parser gives up. */
    private static final Pattern LINE = Pattern.compile("^\\s*(?:-\\s+)?[\"']?([^\"'#:{}\\[\\],]+?)[\"']?\\s*:\\s+(.+)$");

    /**
     * Keys the secret rule matches that hold no secret: how a token is requested and used (Spring
     * Security's OAuth2 client settings and the like).
     */
    private static final Set<String> NOT_SECRET_KEYS = Set.of("authorization-grant-type", "grant-type",
            "grant_type", "client-authentication-method", "token-type", "token_type", "use-token",
            "token-name", "token-header", "authorization-header", "credentials-type");
    /** Endings of keys that name an address (a token endpoint, an authorization URL), not a secret. */
    private static final List<String> NOT_SECRET_SUFFIXES = List.of("uri", "url");

    private PlainSecretLint() {
    }

    /** Which advice the warning gives. */
    public enum FileKind {
        /** A {@code scenarios/} file. */
        SCENARIO,
        /** A {@code config/*.yml} file. */
        SETTINGS
    }

    /** One warning per plain-text secret, in file order, with the advice for {@code kind}. */
    public static List<Problem> check(String yaml, FileKind kind) {
        Message message = kind == FileKind.SCENARIO ? SCENARIO_MESSAGE : SETTINGS_MESSAGE;
        List<Problem> problems = new ArrayList<>();
        scan(yaml, (value, location) -> {
            if (isLiteral(value)) {
                problems.add(new Problem(Problem.Severity.WARNING, Math.max(1, location.getLineNr()),
                        Math.max(1, location.getColumnNr()), KEY, message));
            }
        });
        return problems;
    }

    /**
     * Every value under a secret-looking key that is not a pure {@code ${…}} reference — literals and
     * expressions (and an expression's body) — so text about the file can be masked. Also reads lines
     * the YAML parser could not, line by line, so a value on a broken line is still found.
     */
    public static Set<String> literals(String yaml) {
        Set<String> values = new LinkedHashSet<>();
        scan(yaml, (value, location) -> addValue(values, value));
        if (yaml != null) {
            for (String line : yaml.split("\\R")) {
                Matcher matcher = LINE.matcher(line);
                if (matcher.matches() && isSecretKey(matcher.group(1).strip())) {
                    addValue(values, unquote(withoutComment(matcher.group(2).strip())));
                }
            }
        }
        return values;
    }

    /**
     * Whether a key holds a secret: the engine's {@link Secrets} rule, except for settings that only
     * name how a secret is fetched, which the rule's words ({@code token}, {@code authorization},
     * {@code credential}) would otherwise catch — the lab panel's own OAuth settings among them. A key
     * is judged by its last dot-separated part, so a flat Spring property is judged as its nested form.
     */
    static boolean isSecretKey(String name) {
        if (!Secrets.isSecret(name)) {
            return false;
        }
        String leaf = name.substring(name.lastIndexOf('.') + 1).strip().toLowerCase(Locale.ROOT);
        return !(NOT_SECRET_KEYS.contains(leaf) || NOT_SECRET_SUFFIXES.stream().anyMatch(leaf::endsWith));
    }

    private static void addValue(Set<String> values, String value) {
        String text = value.strip();
        if (text.isEmpty() || isReference(text)) {
            return;
        }
        values.add(text);
        String body = expressionBody(text);
        if (body != null && !body.isBlank()) {
            values.add(body.strip());
        }
    }

    private static boolean isLiteral(String value) {
        String text = value.strip();
        return !text.isEmpty() && !text.contains("${") && !text.contains("js:{");
    }

    private static boolean isReference(String text) {
        return text.startsWith("${") && text.endsWith("}") && text.indexOf('}') == text.length() - 1;
    }

    private static String expressionBody(String text) {
        try {
            return Interpolator.expressionBody(text);
        } catch (IllegalArgumentException malformed) {
            return text.startsWith("js:{") ? text.substring(4).replaceFirst("}\\s*$", "") : null;
        }
    }

    private static String withoutComment(String value) {
        int hash = value.indexOf(" #");
        return hash >= 0 ? value.substring(0, hash).strip() : value;
    }

    private static String unquote(String value) {
        String text = value;
        if (text.startsWith("\"") || text.startsWith("'")) {
            text = text.substring(1);
        }
        if (text.endsWith("\"") || text.endsWith("'")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    @FunctionalInterface
    private interface SecretValue {
        void found(String value, TokenStreamLocation location);
    }

    /** Calls {@code sink} for each scalar (string or number) under a secret-looking key. */
    private static void scan(String yaml, SecretValue sink) {
        if (yaml == null || yaml.isBlank()) {
            return;
        }
        // Per open container: whether its direct scalar items are secret (a list under a secret key).
        Deque<Boolean> secretItems = new ArrayDeque<>();
        Deque<Boolean> isArray = new ArrayDeque<>();
        boolean pendingSecret = false;
        try (JsonParser parser = YAML.createParser(yaml)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                boolean inArray = !isArray.isEmpty() && isArray.peek();
                switch (token) {
                    case PROPERTY_NAME -> pendingSecret = isSecretKey(parser.currentName());
                    case START_OBJECT -> {
                        isArray.push(false);
                        secretItems.push(false);
                        pendingSecret = false;
                    }
                    case START_ARRAY -> {
                        boolean secret = inArray ? secretItems.peek() : pendingSecret;
                        isArray.push(true);
                        secretItems.push(secret);
                        pendingSecret = false;
                    }
                    case END_OBJECT, END_ARRAY -> {
                        isArray.poll();
                        secretItems.poll();
                    }
                    case VALUE_STRING, VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                        boolean secret = inArray ? secretItems.peek() : pendingSecret;
                        if (secret) {
                            sink.found(parser.getString(), parser.currentTokenLocation());
                        }
                        pendingSecret = false;
                    }
                    default -> pendingSecret = false;
                }
            }
        } catch (RuntimeException broken) {
            // what was read before the error has been checked; the validator reports the error itself
        }
    }
}
