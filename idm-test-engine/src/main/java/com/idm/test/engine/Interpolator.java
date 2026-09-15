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
    private static final ExpressionEvaluator DEFAULT_EVALUATOR = new ExpressionEvaluator();

    private Interpolator() {
    }

    /**
     * Replaces every reference in {@code value}, walking maps and lists, using a default
     * expression evaluator.
     */
    public static Object resolve(Object value, Map<String, Object> scope) {
        return resolve(value, scope, DEFAULT_EVALUATOR);
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
            String expression = expressionIn(text);
            if (expression != null) {
                // Deliberately NOT interpolated first. The evaluator already receives the
                // variables, so ${...} inside an expression would be redundant — and actively
                // harmful, because ES6 template literals use the same ${...} syntax and our
                // interpolator would mangle them before JavaScript ever saw them.
                return evaluator.evaluate(expression, scope);
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
     * Extracts the body of a {@code js:{ ... }} value.
     * <p>
     * Brace matching rather than a regex, because expressions legitimately contain braces —
     * {@code js:{ JSON.stringify({a: 1}) }} is valid, and a pattern terminating at the first
     * closing brace would silently truncate it.
     *
     * @return the expression body, or {@code null} when this value is not an expression
     * @throws IllegalArgumentException when the value only looks like one
     */
    private static String expressionIn(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith(EXPRESSION_PREFIX)) {
            return null;
        }

        int depth = 0;
        for (int i = EXPRESSION_PREFIX.length() - 1; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    if (i != trimmed.length() - 1) {
                        throw new IllegalArgumentException(
                                "an expression must be the whole value, but '" + text
                                        + "' has text after the closing brace");
                    }
                    return trimmed.substring(EXPRESSION_PREFIX.length(), i);
                }
            }
        }

        throw new IllegalArgumentException(
                "the expression in '" + text + "' is not closed — a '}' is missing");
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

    private static Object resolveString(String text, Map<String, Object> scope) {
        if (text.contains(EXPRESSION_PREFIX) && !text.trim().startsWith(EXPRESSION_PREFIX)) {
            throw new IllegalArgumentException(
                    "an expression must be the whole value, but found js:{ inside '" + text + "'");
        }

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
