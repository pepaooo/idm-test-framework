package com.idm.test.engine;

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
 * computation it belongs in the Java tier, not in a larger expression language here.
 * <p>
 * The {@code js:{ }} escape hatch is ITF-7 and is not handled by this class.
 */
public final class Interpolator {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private Interpolator() {
    }

    /**
     * Replaces every reference in {@code value}, walking maps and lists.
     *
     * @throws IllegalArgumentException naming the reference that could not be resolved
     */
    public static Object resolve(Object value, Map<String, Object> scope) {
        if (value instanceof String text) {
            return resolveString(text, scope);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(k.toString(), resolve(v, scope)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            list.forEach(element -> resolved.add(resolve(element, scope)));
            return resolved;
        }
        return value;
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
