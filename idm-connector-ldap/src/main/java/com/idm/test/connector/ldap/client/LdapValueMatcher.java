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

    private LdapValueMatcher() {
    }

    /**
     * @return {@code null} when {@code actual} satisfies {@code expected}, else the failure message
     */
    public static String match(String attribute, Object expected, Object actual) {
        LdapProblem problem = problem(attribute, expected, actual);
        return problem == null ? null : problem.english();
    }

    /**
     * @return {@code null} when {@code actual} satisfies {@code expected}, else what differs
     */
    public static LdapProblem problem(String attribute, Object expected, Object actual) {
        boolean secret = SecretNames.isSecret(attribute);
        LdapProblem problem = shape(expected, secret);
        if (problem != null) {
            // A malformed matcher would otherwise compare nothing and pass.
            return LdapProblem.of(LdapProblem.Kind.MATCH_SHAPE, attribute, problem);
        }
        List<Object> actualValues = values(actual);

        if (expected instanceof Map<?, ?> matcher) {
            boolean ignoreCase = Boolean.TRUE.equals(matcher.get(IGNORE_CASE));
            if (matcher.containsKey(EQUALS)) {
                LdapProblem mismatch = exactSet(attribute, values(matcher.get(EQUALS)), actualValues, ignoreCase, secret);
                if (mismatch != null) {
                    return mismatch;
                }
            }
            if (matcher.containsKey(CONTAINS)) {
                List<Object> wanted = distinct(values(matcher.get(CONTAINS)), ignoreCase);
                List<Object> missing = absentFrom(wanted, actualValues, ignoreCase);
                if (!missing.isEmpty()) {
                    if (secret) {
                        return LdapProblem.of(LdapProblem.Kind.MATCH_CONTAINS_SECRET, attribute);
                    }
                    return LdapProblem.of(LdapProblem.Kind.MATCH_CONTAINS, attribute, wanted, missing, actual);
                }
            }
            if (matcher.containsKey(EXCLUDES)) {
                List<Object> unwanted = distinct(values(matcher.get(EXCLUDES)), ignoreCase);
                List<Object> found = presentIn(unwanted, actualValues, ignoreCase);
                if (!found.isEmpty()) {
                    if (secret) {
                        return LdapProblem.of(LdapProblem.Kind.MATCH_EXCLUDES_SECRET, attribute);
                    }
                    return LdapProblem.of(LdapProblem.Kind.MATCH_EXCLUDES, attribute, unwanted, found);
                }
            }
            if (matcher.containsKey(COUNT)) {
                long wanted = ((Number) matcher.get(COUNT)).longValue();
                if (actualValues.size() != wanted) {
                    return LdapProblem.of(wanted == 1 ? LdapProblem.Kind.MATCH_COUNT_ONE : LdapProblem.Kind.MATCH_COUNT_OTHER,
                            attribute, wanted, actualValues.size(),
                            actualValues.isEmpty() || secret ? "" : ": " + actualValues);
                }
            }
            if (matcher.containsKey(MATCHES)) {
                for (Object pattern : values(matcher.get(MATCHES))) {
                    Pattern compiled = Pattern.compile((String) pattern);
                    if (actualValues.stream().noneMatch(value -> compiled.matcher(String.valueOf(value)).matches())) {
                        return secret
                                ? LdapProblem.of(LdapProblem.Kind.MATCH_PATTERN_SECRET, attribute)
                                : LdapProblem.of(LdapProblem.Kind.MATCH_PATTERN, attribute, pattern, actual);
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
        return secret ? LdapProblem.of(LdapProblem.Kind.MATCH_VALUES_SECRET, attribute)
                : LdapProblem.of(LdapProblem.Kind.MATCH_VALUE, attribute, expected, actual);
    }

    private static LdapProblem exactSet(String attribute, List<Object> expected, List<Object> actualValues,
                                   boolean ignoreCase, boolean secret) {
        List<Object> wanted = distinct(expected, ignoreCase);
        List<Object> missing = absentFrom(wanted, actualValues, ignoreCase);
        List<Object> unexpected = absentFrom(actualValues, wanted, ignoreCase);
        if (missing.isEmpty() && unexpected.isEmpty()) {
            return null;
        }
        if (secret) {
            return LdapProblem.of(LdapProblem.Kind.MATCH_VALUES_SECRET, attribute);
        }
        if (unexpected.isEmpty()) {
            return LdapProblem.of(LdapProblem.Kind.MATCH_EXACT_MISSING, attribute, wanted, missing);
        }
        if (missing.isEmpty()) {
            return LdapProblem.of(LdapProblem.Kind.MATCH_EXACT_UNEXPECTED, attribute, wanted, unexpected);
        }
        return LdapProblem.of(LdapProblem.Kind.MATCH_EXACT_BOTH, attribute, wanted, missing, unexpected);
    }

    /**
     * @return {@code null} when {@code expected} is well-formed, else what is wrong with it, as a
     *         fragment to follow the attribute's name
     */
    public static String shapeProblem(Object expected) {
        return english(shape(expected, false));
    }

    /**
     * As {@link #shapeProblem(Object)}, but for a secret-named attribute the fragment never quotes
     * a value or a pattern.
     */
    public static String shapeProblem(String attribute, Object expected) {
        return english(problemOfShape(attribute, expected));
    }

    /** As {@link #shapeProblem(String, Object)}, structured. */
    public static LdapProblem problemOfShape(String attribute, Object expected) {
        return shape(expected, SecretNames.isSecret(attribute));
    }

    private static String english(LdapProblem problem) {
        return problem == null ? null : problem.english();
    }

    private static LdapProblem shape(Object expected, boolean secret) {
        if (expected instanceof Map<?, ?> matcher) {
            if (matcher.isEmpty()) {
                return LdapProblem.of(LdapProblem.Kind.SHAPE_EMPTY_MAP);
            }
            Set<String> unknown = new TreeSet<>();
            for (Object key : matcher.keySet()) {
                if (!KEYS.contains(String.valueOf(key))) {
                    unknown.add(String.valueOf(key));
                }
            }
            if (!unknown.isEmpty()) {
                return LdapProblem.of(LdapProblem.Kind.SHAPE_UNKNOWN_KEYS, unknown);
            }
            for (String key : VALUE_KEYS) {
                if (!matcher.containsKey(key)) {
                    continue;
                }
                Object value = matcher.get(key);
                if (value == null || value instanceof Collection<?> c && c.isEmpty()) {
                    return LdapProblem.of(LdapProblem.Kind.SHAPE_EMPTY_KEY, key);
                }
                if (value instanceof Map<?, ?> || value instanceof Collection<?> c && !plain(c)) {
                    return LdapProblem.of(LdapProblem.Kind.SHAPE_PLAIN_KEY, key);
                }
            }
            if (matcher.containsKey(COUNT) && !wholeNonNegative(matcher.get(COUNT))) {
                return secret ? LdapProblem.of(LdapProblem.Kind.SHAPE_COUNT)
                        : LdapProblem.of(LdapProblem.Kind.SHAPE_COUNT_VALUE, matcher.get(COUNT));
            }
            if (matcher.containsKey(MATCHES)) {
                LdapProblem patternProblem = patternProblem(matcher.get(MATCHES), secret);
                if (patternProblem != null) {
                    return patternProblem;
                }
            }
            boolean ignoreCase = false;
            if (matcher.containsKey(IGNORE_CASE)) {
                if (!(matcher.get(IGNORE_CASE) instanceof Boolean flag)) {
                    return LdapProblem.of(LdapProblem.Kind.SHAPE_IGNORE_CASE_TYPE);
                }
                if (VALUE_KEYS.stream().noneMatch(matcher::containsKey)) {
                    return LdapProblem.of(LdapProblem.Kind.SHAPE_IGNORE_CASE_ALONE);
                }
                ignoreCase = flag;
            }
            if (matcher.containsKey(CONTAINS) && matcher.containsKey(EXCLUDES)) {
                List<Object> overlap = presentIn(distinct(values(matcher.get(CONTAINS)), ignoreCase),
                        values(matcher.get(EXCLUDES)), ignoreCase);
                if (!overlap.isEmpty()) {
                    return secret ? LdapProblem.of(LdapProblem.Kind.SHAPE_OVERLAP_SECRET)
                            : LdapProblem.of(LdapProblem.Kind.SHAPE_OVERLAP, overlap);
                }
            }
            return null;
        }
        if (expected instanceof Collection<?> list) {
            if (list.isEmpty()) {
                return LdapProblem.of(LdapProblem.Kind.SHAPE_EMPTY_LIST);
            }
            if (!plain(list)) {
                return LdapProblem.of(LdapProblem.Kind.SHAPE_PLAIN_LIST);
            }
        }
        return null;
    }

    private static LdapProblem patternProblem(Object raw, boolean secret) {
        List<Object> patterns = values(raw);
        if (patterns.isEmpty()) {
            return LdapProblem.of(LdapProblem.Kind.SHAPE_EMPTY_MATCHES);
        }
        for (Object pattern : patterns) {
            if (!(pattern instanceof String text)) {
                return LdapProblem.of(LdapProblem.Kind.SHAPE_MATCHES_TYPE);
            }
            try {
                Pattern.compile(text);
            } catch (PatternSyntaxException e) {
                return secret ? LdapProblem.of(LdapProblem.Kind.SHAPE_PATTERN_SECRET, e.getDescription())
                        : LdapProblem.of(LdapProblem.Kind.SHAPE_PATTERN, text, e.getDescription());
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
