package com.idm.test.engine;

import com.idm.test.engine.expr.ExpressionEvaluator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${path}} references against the scenario's variables.
 * <p>
 * Property paths only — no arithmetic, no conditionals, no function calls. That limit is
 * deliberate: it is what lets a form-based UI represent every value. When a scenario needs
 * computation it uses the {@code js:{ }} escape hatch (ITF-7) instead, evaluated by an
 * {@link ExpressionEvaluator} — a value must be entirely an expression, never embedded in a
 * larger string.
 */
public final class Interpolator {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
    private static final String EXPRESSION_PREFIX = "js:{";

    /**
     * A value that is exactly one reference and nothing else. Such a value resolves to the
     * referenced object itself rather than its text, so a map stays a map and a number stays
     * a number. Anything else — a reference inside a larger string, or two references — is
     * text by construction and goes through {@link #resolveString}.
     * <p>
     * Same regex body as {@link #REFERENCE}; the difference is purely in how each is used —
     * {@link #REFERENCE} is scanned with {@code find()} to locate references anywhere in a
     * string, while this one is checked with {@code matches()} to confirm the whole string is
     * a single reference and nothing more.
     */
    private static final Pattern WHOLE_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private Interpolator() {
    }

    /**
     * Replaces every reference in {@code value}, walking maps and lists.
     *
     * @throws IllegalArgumentException naming the reference that could not be resolved, or
     *                                  describing a malformed {@code js:{ }} expression
     */
    public static Object resolve(Object value, Map<String, Object> scope,
                                 ExpressionEvaluator evaluator) {
        if (value instanceof String text) {
            String expression = expressionBody(text);
            if (expression != null) {
                // Deliberately NOT interpolated first. The evaluator already receives the
                // variables, so ${...} inside an expression would be redundant — and actively
                // harmful, because ES6 template literals use the same ${...} syntax and our
                // interpolator would mangle them before JavaScript ever saw them.
                return evaluator.evaluate(expression, scope);
            }
            Matcher whole = WHOLE_REFERENCE.matcher(text);
            if (whole.matches()) {
                // Deliberately no trimming: " ${x} " is a string with a reference in it, not
                // a whole-value reference, and treating it as one would silently discard the
                // spaces the author wrote.
                return lookup(whole.group(1), scope, text);
            }
            return resolveString(text, scope);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(k.toString(), resolve(v, scope, evaluator)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            list.forEach(element -> resolved.add(resolve(element, scope, evaluator)));
            return resolved;
        }
        return value;
    }

    /**
     * Returns the body of the {@code js:{ ... }} expression in {@code text}, or {@code null}
     * when {@code text} is not an expression at all.
     * <p>
     * Used both to evaluate ({@link #resolve}) and, before anything runs, to check a scenario
     * file's expressions are structurally well-formed at load — see {@code ScenarioValidator}.
     * Structural only: this does not check that the body is syntactically valid JavaScript.
     * {@link com.idm.test.engine.expr.ExpressionEvaluator#validate(String)} does that.
     *
     * @throws IllegalArgumentException when {@code text} looks like an expression but is
     *                                  malformed: embedded in a larger string, unterminated, or
     *                                  empty
     */
    public static String expressionBody(String text) {
        if (text.contains(EXPRESSION_PREFIX) && !text.trim().startsWith(EXPRESSION_PREFIX)) {
            throw new IllegalArgumentException(
                    "an expression must be the whole value, but found js:{ inside '" + text + "'");
        }
        return expressionIn(text);
    }

    /**
     * A region a brace can appear in without being structural, or the normal code region where
     * it is.
     */
    private enum Region {
        /** Ordinary JS code (and the body of a {@code ${ ... }} template substitution). */
        CODE,
        SINGLE_QUOTED,
        DOUBLE_QUOTED,
        /** A backtick string. A brace here is inert unless it starts a {@code ${ ... }}. */
        TEMPLATE,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    /**
     * Extracts the body of a {@code js:{ ... }} value.
     * <p>
     * Brace matching rather than a regex, because expressions legitimately contain braces —
     * {@code js:{ JSON.stringify({a: 1}) }} is valid, and a pattern terminating at the first
     * closing brace would silently truncate it. The matcher is brace-depth counting, not a full
     * JS lexer, but it does track the regions where a brace is not structural: single- and
     * double-quoted strings (backslash escapes honoured), backtick template literals — including
     * a {@code ${ ... }} substitution's own nested braces and strings — and both comment forms.
     * <p>
     * <b>Known limitation:</b> a regular-expression literal containing a character that is
     * structural elsewhere — a brace, a quote, or a comment opener, such as
     * {@code js:{ /}/.test('}') }} or {@code js:{ /'/.test(x) }} — still confuses the counter:
     * telling a regex literal from a division apart requires real JavaScript lexing, which this
     * deliberately is not. Such an expression is rejected (usually as "not closed"), which is
     * confusing but safe: it never silently truncates what the author wrote.
     * <p>
     * <b>Also note:</b> {@code js:{ {a: 1} }} is parsed by JavaScript as a labelled block
     * containing the expression statement {@code 1}, not an object literal — so it evaluates to
     * {@code 1}, not an object. That is JavaScript's own grammar rule, not a limitation of this
     * matcher. An author who wants an object literal should write {@code js:{ ({a: 1}) }} or
     * build the value with {@code JSON.stringify(...)}.
     *
     * @return the expression body, or {@code null} when this value is not an expression
     * @throws IllegalArgumentException when the value only looks like one
     */
    private static String expressionIn(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith(EXPRESSION_PREFIX)) {
            return null;
        }

        int length = trimmed.length();
        // One entry per currently-open region, innermost last. The outermost entry is the CODE
        // region opened by the '{' in "js:{"; the expression is fully matched when it pops.
        List<Region> regions = new ArrayList<>();
        regions.add(Region.CODE);

        int i = EXPRESSION_PREFIX.length();
        while (i < length && !regions.isEmpty()) {
            char c = trimmed.charAt(i);
            Region region = regions.get(regions.size() - 1);

            switch (region) {
                case CODE -> {
                    if (c == '{') {
                        regions.add(Region.CODE);
                        i++;
                    } else if (c == '}') {
                        regions.remove(regions.size() - 1);
                        i++;
                        if (regions.isEmpty()) {
                            if (i != length) {
                                throw new IllegalArgumentException(
                                        "an expression must be the whole value, but '" + text
                                                + "' has text after the closing brace");
                            }
                            String body = trimmed.substring(EXPRESSION_PREFIX.length(), i - 1);
                            if (body.isBlank()) {
                                throw new IllegalArgumentException(
                                        "the expression in '" + text + "' is empty");
                            }
                            return body;
                        }
                    } else if (c == '\'') {
                        regions.add(Region.SINGLE_QUOTED);
                        i++;
                    } else if (c == '"') {
                        regions.add(Region.DOUBLE_QUOTED);
                        i++;
                    } else if (c == '`') {
                        regions.add(Region.TEMPLATE);
                        i++;
                    } else if (c == '/' && startsWith(trimmed, i, "//")) {
                        regions.add(Region.LINE_COMMENT);
                        i += 2;
                    } else if (c == '/' && startsWith(trimmed, i, "/*")) {
                        regions.add(Region.BLOCK_COMMENT);
                        i += 2;
                    } else {
                        i++;
                    }
                }
                case SINGLE_QUOTED, DOUBLE_QUOTED -> {
                    char quote = region == Region.SINGLE_QUOTED ? '\'' : '"';
                    if (c == '\\' && i + 1 < length) {
                        i += 2;
                    } else if (c == quote) {
                        regions.remove(regions.size() - 1);
                        i++;
                    } else {
                        i++;
                    }
                }
                case TEMPLATE -> {
                    if (c == '\\' && i + 1 < length) {
                        i += 2;
                    } else if (c == '`') {
                        regions.remove(regions.size() - 1);
                        i++;
                    } else if (c == '$' && startsWith(trimmed, i, "${")) {
                        // The substitution's own '}' is handled by the CODE case above, which
                        // pops back to this TEMPLATE region — not the outer expression's.
                        regions.add(Region.CODE);
                        i += 2;
                    } else {
                        i++;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        regions.remove(regions.size() - 1);
                    }
                    i++;
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && startsWith(trimmed, i, "*/")) {
                        regions.remove(regions.size() - 1);
                        i += 2;
                    } else {
                        i++;
                    }
                }
            }
        }

        Region unclosed = regions.get(regions.size() - 1);
        String missing = switch (unclosed) {
            case SINGLE_QUOTED -> "a closing \"'\" is missing";
            case DOUBLE_QUOTED -> "a closing '\"' is missing";
            case TEMPLATE -> "a closing '`' is missing";
            case BLOCK_COMMENT -> "a closing '*/' is missing";
            case CODE, LINE_COMMENT -> "a '}' is missing";
        };
        throw new IllegalArgumentException(
                "the expression in '" + text + "' is not closed — " + missing);
    }

    private static boolean startsWith(String text, int index, String prefix) {
        return text.regionMatches(index, prefix, 0, prefix.length());
    }

    /**
     * @return the root name of every reference found, for example "user" in "${user.email}"
     */
    public static Set<String> references(Object value) {
        Set<String> roots = new LinkedHashSet<>();
        collectReferences(value, roots);
        return roots;
    }

    private static void collectReferences(Object value, Set<String> roots) {
        if (value instanceof String text) {
            if (isWholeValueExpression(text)) {
                // An expression is JavaScript, not a scenario reference — a template literal's
                // own ${...} substitutions must not be mistaken for ${path} references. A
                // malformed expression is reported by resolve(), not here: references() is a
                // query and must not throw.
                return;
            }
            Matcher matcher = REFERENCE.matcher(text);
            while (matcher.find()) {
                roots.add(matcher.group(1).split("\\.", 2)[0]);
            }
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectReferences(v, roots));
        } else if (value instanceof List<?> list) {
            list.forEach(element -> collectReferences(element, roots));
        }
    }

    private static boolean isWholeValueExpression(String text) {
        try {
            return expressionIn(text) != null;
        } catch (IllegalArgumentException malformed) {
            return true;
        }
    }

    private static Object resolveString(String text, Map<String, Object> scope) {
        // The embedded-js:{ } check already ran in expressionBody(), called by resolve()
        // before this is ever reached, so text.contains(EXPRESSION_PREFIX) cannot be true here.
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String path = matcher.group(1);
            Object value = lookup(path, scope, matcher.group());
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private static Object lookup(String path, Map<String, Object> scope, String reference) {
        Object current = scope;

        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                throw new IllegalArgumentException(
                        "cannot resolve " + reference + " — no value for '" + segment + "'");
            }
            current = map.get(segment);
        }

        return current;
    }
}
