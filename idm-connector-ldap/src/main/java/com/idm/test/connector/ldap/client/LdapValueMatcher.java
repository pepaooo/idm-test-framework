package com.idm.test.connector.ldap.client;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares one attribute's expected value against what the directory holds.
 * <p>
 * The actual value is treated as a set: an absent attribute is the empty set, a scalar is one
 * value, a list is its values. LDAP keeps no value order, so a plain expected list is the exact
 * set in any order; {@code contains} and {@code excludes} check part of it. Two values that both
 * parse as DNs compare as DNs, because the directory may spell the same DN with another case or
 * spacing.
 */
public final class LdapValueMatcher {

    public static final String CONTAINS = "contains";
    public static final String EXCLUDES = "excludes";

    private LdapValueMatcher() {
    }

    /**
     * @return {@code null} when {@code actual} satisfies {@code expected}, else the failure message
     */
    public static String match(String attribute, Object expected, Object actual) {
        String problem = shapeProblem(expected);
        if (problem != null) {
            // A malformed matcher would otherwise compare nothing and pass.
            return "Attribute '" + attribute + "' " + problem;
        }
        List<Object> actualValues = values(actual);

        if (expected instanceof Map<?, ?> matcher) {
            if (matcher.containsKey(CONTAINS)) {
                List<Object> wanted = distinct(values(matcher.get(CONTAINS)));
                List<Object> missing = absentFrom(wanted, actualValues);
                if (!missing.isEmpty()) {
                    return "Attribute '" + attribute + "': expected to contain " + wanted
                            + "; missing " + missing + "; actual=" + actual;
                }
            }
            if (matcher.containsKey(EXCLUDES)) {
                List<Object> unwanted = distinct(values(matcher.get(EXCLUDES)));
                List<Object> found = presentIn(unwanted, actualValues);
                if (!found.isEmpty()) {
                    return "Attribute '" + attribute + "': expected not to contain " + unwanted
                            + "; found " + found;
                }
            }
            return null;
        }

        if (expected instanceof Collection<?> list) {
            List<Object> wanted = distinct(new ArrayList<>(list));
            List<Object> missing = absentFrom(wanted, actualValues);
            List<Object> unexpected = absentFrom(actualValues, wanted);
            if (missing.isEmpty() && unexpected.isEmpty()) {
                return null;
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

        boolean matches = expected == null
                ? actualValues.isEmpty()
                : actualValues.size() == 1 && sameValue(expected, actualValues.get(0));
        return matches ? null
                : "Attribute '" + attribute + "': expected=" + expected + ", actual=" + actual;
    }

    /**
     * @return {@code null} when {@code expected} is well-formed, else what is wrong with it, as a
     *         fragment to follow the attribute's name
     */
    public static String shapeProblem(Object expected) {
        if (expected instanceof Map<?, ?> matcher) {
            if (matcher.isEmpty()) {
                return "is an empty map; use contains and/or excludes";
            }
            Set<String> unknown = new TreeSet<>();
            for (Object key : matcher.keySet()) {
                if (!CONTAINS.equals(key) && !EXCLUDES.equals(key)) {
                    unknown.add(String.valueOf(key));
                }
            }
            if (!unknown.isEmpty()) {
                return "has unknown matcher keys " + unknown + "; use contains and/or excludes";
            }
            for (String key : List.of(CONTAINS, EXCLUDES)) {
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
            if (matcher.containsKey(CONTAINS) && matcher.containsKey(EXCLUDES)) {
                List<Object> overlap = presentIn(distinct(values(matcher.get(CONTAINS))),
                        values(matcher.get(EXCLUDES)));
                if (!overlap.isEmpty()) {
                    return "lists " + overlap + " in both 'contains' and 'excludes'";
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

    /** Equal as DNs when both parse as DNs, otherwise equal as text. */
    static boolean sameValue(Object expected, Object actual) {
        LdapName expectedDn = dn(expected);
        LdapName actualDn = dn(actual);
        if (expectedDn != null && actualDn != null) {
            return expectedDn.equals(actualDn);
        }
        return expected.equals(actual) || String.valueOf(expected).equals(String.valueOf(actual));
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

    private static List<Object> distinct(List<Object> values) {
        List<Object> result = new ArrayList<>();
        for (Object value : values) {
            if (result.stream().noneMatch(kept -> sameValue(kept, value))) {
                result.add(value);
            }
        }
        return result;
    }

    /** The values of {@code source} that have no equal in {@code target}. */
    private static List<Object> absentFrom(List<Object> source, List<Object> target) {
        return distinct(source).stream()
                .filter(value -> target.stream().noneMatch(other -> sameValue(value, other)))
                .toList();
    }

    /** The values of {@code source} that have an equal in {@code target}. */
    private static List<Object> presentIn(List<Object> source, List<Object> target) {
        return distinct(source).stream()
                .filter(value -> target.stream().anyMatch(other -> sameValue(value, other)))
                .toList();
    }
}
