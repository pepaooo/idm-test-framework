package com.idm.test.connector.ldap.client;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compares one attribute's expected value against what the directory holds.
 * <p>
 * The actual value is treated as a set: an absent attribute is the empty set, a scalar is one
 * value, a list is its values. LDAP keeps no value order, so a plain expected list is the exact
 * set in any order; {@code contains} and {@code excludes} check part of it. Two values that both
 * parse as DNs compare as DNs, because the directory may spell the same DN with another case or
 * spacing. A matcher map may also give equals, count, matches and ignoreCase; every key given must hold.
 */
public final class LdapValueMatcher {

    public static final String EQUALS = "equals";
    public static final String CONTAINS = "contains";
    public static final String EXCLUDES = "excludes";
    public static final String COUNT = "count";
    public static final String MATCHES = "matches";
    public static final String IGNORE_CASE = "ignoreCase";

    private static final List<String> KEYS = List.of(EQUALS, CONTAINS, EXCLUDES, COUNT, MATCHES, IGNORE_CASE);
    /** The keys whose values are compared as values, and so are affected by ignoreCase. */
    private static final List<String> VALUE_KEYS = List.of(EQUALS, CONTAINS, EXCLUDES);
    private static final String KEY_HINT = "use equals, contains, excludes, count, matches or ignoreCase";

    private LdapValueMatcher() {
    }

    /**
     * @return {@code null} when {@code actual} satisfies {@code expected}, else the failure message
     */
    public static String match(String attribute, Object expected, Object actual) {
        boolean secret = SecretNames.isSecret(attribute);
        String problem = shapeProblem(expected, secret);
        if (problem != null) {
            // A malformed matcher would otherwise compare nothing and pass.
            return "Attribute '" + attribute + "' " + problem;
        }
        List<Object> actualValues = values(actual);

        if (expected instanceof Map<?, ?> matcher) {
            boolean ignoreCase = Boolean.TRUE.equals(matcher.get(IGNORE_CASE));
            if (matcher.containsKey(EQUALS)) {
                String mismatch = exactSet(attribute, values(matcher.get(EQUALS)), actualValues, ignoreCase, secret);
                if (mismatch != null) {
                    return mismatch;
                }
            }
            if (matcher.containsKey(CONTAINS)) {
                List<Object> wanted = distinct(values(matcher.get(CONTAINS)), ignoreCase);
                List<Object> missing = absentFrom(wanted, actualValues, ignoreCase);
                if (!missing.isEmpty()) {
                    if (secret) {
                        return "Attribute '" + attribute + "': does not contain all the expected values";
                    }
                    return "Attribute '" + attribute + "': expected to contain " + wanted
                            + "; missing " + missing + "; actual=" + actual;
                }
            }
            if (matcher.containsKey(EXCLUDES)) {
                List<Object> unwanted = distinct(values(matcher.get(EXCLUDES)), ignoreCase);
                List<Object> found = presentIn(unwanted, actualValues, ignoreCase);
                if (!found.isEmpty()) {
                    if (secret) {
                        return "Attribute '" + attribute + "': contains values that were expected to be absent";
                    }
                    return "Attribute '" + attribute + "': expected not to contain " + unwanted
                            + "; found " + found;
                }
            }
            if (matcher.containsKey(COUNT)) {
                long wanted = ((Number) matcher.get(COUNT)).longValue();
                if (actualValues.size() != wanted) {
                    return "Attribute '" + attribute + "': expected " + wanted + (wanted == 1 ? " value" : " values")
                            + ", found " + actualValues.size()
                            + (actualValues.isEmpty() || secret ? "" : ": " + actualValues);
                }
            }
            if (matcher.containsKey(MATCHES)) {
                for (Object pattern : values(matcher.get(MATCHES))) {
                    Pattern compiled = Pattern.compile((String) pattern);
                    if (actualValues.stream().noneMatch(value -> compiled.matcher(String.valueOf(value)).matches())) {
                        return secret
                                ? "Attribute '" + attribute + "': no value matches the pattern"
                                : "Attribute '" + attribute + "': no value matches " + pattern + "; actual=" + actual;
                    }
                }
            }
            return null;
        }

        if (expected instanceof Collection<?> list) {
            return exactSet(attribute, new ArrayList<>(list), actualValues, false, secret);
        }

        boolean matches = expected == null
                ? actualValues.isEmpty()
                : actualValues.size() == 1 && sameValue(expected, actualValues.get(0));
        if (matches) {
            return null;
        }
        return secret ? "Attribute '" + attribute + "': does not have the expected values"
                : "Attribute '" + attribute + "': expected=" + expected + ", actual=" + actual;
    }

    private static String exactSet(String attribute, List<Object> expected, List<Object> actualValues,
                                   boolean ignoreCase, boolean secret) {
        List<Object> wanted = distinct(expected, ignoreCase);
        List<Object> missing = absentFrom(wanted, actualValues, ignoreCase);
        List<Object> unexpected = absentFrom(actualValues, wanted, ignoreCase);
        if (missing.isEmpty() && unexpected.isEmpty()) {
            return null;
        }
        if (secret) {
            return "Attribute '" + attribute + "': does not have the expected values";
        }
        StringBuilder message = new StringBuilder("Attribute '" + attribute + "': expected exactly "
                + wanted + " in any order");
        if (!missing.isEmpty()) {
            message.append("; missing ").append(missing);
        }
        if (!unexpected.isEmpty()) {
            message.append("; unexpected ").append(unexpected);
        }
        return message.toString();
    }

    /**
     * @return {@code null} when {@code expected} is well-formed, else what is wrong with it, as a
     *         fragment to follow the attribute's name
     */
    public static String shapeProblem(Object expected) {
        return shapeProblem(expected, false);
    }

    /**
     * As {@link #shapeProblem(Object)}, but for a secret-named attribute the fragment never quotes
     * a value or a pattern.
     */
    public static String shapeProblem(String attribute, Object expected) {
        return shapeProblem(expected, SecretNames.isSecret(attribute));
    }

    private static String shapeProblem(Object expected, boolean secret) {
        if (expected instanceof Map<?, ?> matcher) {
            if (matcher.isEmpty()) {
                return "is an empty map; " + KEY_HINT;
            }
            Set<String> unknown = new TreeSet<>();
            for (Object key : matcher.keySet()) {
                if (!KEYS.contains(String.valueOf(key))) {
                    unknown.add(String.valueOf(key));
                }
            }
            if (!unknown.isEmpty()) {
                return "has unknown matcher keys " + unknown + "; " + KEY_HINT;
            }
            for (String key : VALUE_KEYS) {
                if (!matcher.containsKey(key)) {
                    continue;
                }
                Object value = matcher.get(key);
                if (value == null || value instanceof Collection<?> c && c.isEmpty()) {
                    return "has an empty '" + key + "'";
                }
                if (value instanceof Map<?, ?> || value instanceof Collection<?> c && !plain(c)) {
                    return "has a null, map or list value in '" + key + "'; give plain values";
                }
            }
            if (matcher.containsKey(COUNT) && !wholeNonNegative(matcher.get(COUNT))) {
                return "has a 'count' that is not a non-negative whole number"
                        + (secret ? "" : ": " + matcher.get(COUNT));
            }
            if (matcher.containsKey(MATCHES)) {
                String patternProblem = patternProblem(matcher.get(MATCHES), secret);
                if (patternProblem != null) {
                    return patternProblem;
                }
            }
            boolean ignoreCase = false;
            if (matcher.containsKey(IGNORE_CASE)) {
                if (!(matcher.get(IGNORE_CASE) instanceof Boolean flag)) {
                    return "has an 'ignoreCase' that is not true or false";
                }
                if (VALUE_KEYS.stream().noneMatch(matcher::containsKey)) {
                    return "has an 'ignoreCase' with no equals, contains or excludes to apply to";
                }
                ignoreCase = flag;
            }
            if (matcher.containsKey(CONTAINS) && matcher.containsKey(EXCLUDES)) {
                List<Object> overlap = presentIn(distinct(values(matcher.get(CONTAINS)), ignoreCase),
                        values(matcher.get(EXCLUDES)), ignoreCase);
                if (!overlap.isEmpty()) {
                    return "lists " + (secret ? "the same value" : overlap.toString())
                            + " in both 'contains' and 'excludes'";
                }
            }
            return null;
        }
        if (expected instanceof Collection<?> list) {
            if (list.isEmpty()) {
                return "is an empty list, which a misspelled attribute name would also satisfy; "
                        + "to assert values are absent use { excludes: [...] }";
            }
            if (!plain(list)) {
                return "has a null, map or list value inside its list; give plain values";
            }
        }
        return null;
    }

    private static String patternProblem(Object raw, boolean secret) {
        List<Object> patterns = values(raw);
        if (patterns.isEmpty()) {
            return "has an empty 'matches'";
        }
        for (Object pattern : patterns) {
            if (!(pattern instanceof String text)) {
                return "has a 'matches' that is not a regular expression or a list of them";
            }
            try {
                Pattern.compile(text);
            } catch (PatternSyntaxException e) {
                return "has an invalid 'matches' pattern" + (secret ? "" : " '" + text + "'")
                        + ": " + e.getDescription();
            }
        }
        return null;
    }

    /** A YAML integer that is zero or more; decimals, text and booleans are not counts. */
    private static boolean wholeNonNegative(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue() >= 0;
        }
        return value instanceof BigInteger big && big.signum() >= 0 && big.bitLength() < 64;
    }

    /** Equal as DNs when both parse as DNs, otherwise equal as text. */
    static boolean sameValue(Object expected, Object actual) {
        return sameValue(expected, actual, false);
    }

    /** Equal as DNs when both parse as DNs, otherwise equal as text, ignoring case when asked. */
    static boolean sameValue(Object expected, Object actual, boolean ignoreCase) {
        LdapName expectedDn = dn(expected);
        LdapName actualDn = dn(actual);
        if (expectedDn != null && actualDn != null) {
            return expectedDn.equals(actualDn);
        }
        if (expected.equals(actual)) {
            return true;
        }
        String expectedText = String.valueOf(expected);
        String actualText = String.valueOf(actual);
        return ignoreCase ? expectedText.equalsIgnoreCase(actualText) : expectedText.equals(actualText);
    }

    private static LdapName dn(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            LdapName name = new LdapName(text);
            return name.size() > 0 ? name : null;
        } catch (InvalidNameException | IllegalArgumentException e) {
            return null;
        }
    }

    private static List<Object> values(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of(value);
    }

    private static boolean plain(Collection<?> values) {
        return values.stream().allMatch(v -> v != null && !(v instanceof Map<?, ?>) && !(v instanceof Collection<?>));
    }

    private static List<Object> distinct(List<Object> values, boolean ignoreCase) {
        List<Object> result = new ArrayList<>();
        for (Object value : values) {
            if (result.stream().noneMatch(kept -> sameValue(kept, value, ignoreCase))) {
                result.add(value);
            }
        }
        return result;
    }

    /** The values of {@code source} that have no equal in {@code target}. */
    private static List<Object> absentFrom(List<Object> source, List<Object> target, boolean ignoreCase) {
        return distinct(source, ignoreCase).stream()
                .filter(value -> target.stream().noneMatch(other -> sameValue(value, other, ignoreCase)))
                .toList();
    }

    /** The values of {@code source} that have an equal in {@code target}. */
    private static List<Object> presentIn(List<Object> source, List<Object> target, boolean ignoreCase) {
        return distinct(source, ignoreCase).stream()
                .filter(value -> target.stream().anyMatch(other -> sameValue(value, other, ignoreCase)))
                .toList();
    }
}
