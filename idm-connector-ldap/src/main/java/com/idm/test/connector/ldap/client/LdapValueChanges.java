package com.idm.test.connector.ldap.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rules for an {@code ldap.update} attribute written as {@code { add: …, remove: … }}: values added
 * to or removed from the attribute, leaving its other values as they are. Values compare DN-aware,
 * as in {@link LdapValueMatcher}.
 */
public final class LdapValueChanges {

    public static final String ADD = "add";
    public static final String REMOVE = "remove";

    private LdapValueChanges() {
    }

    /**
     * @param attribute the attribute's name; the values of a secret-named one are never quoted
     * @return {@code null} when {@code value} is not a change (not a map) or is a well-formed one,
     *         else what is wrong with it, as a fragment to follow the attribute's name
     */
    public static String shapeProblem(String attribute, Object value) {
        LdapProblem problem = problem(attribute, value);
        return problem == null ? null : problem.english();
    }

    /** As {@link #shapeProblem}, structured. */
    public static LdapProblem problem(String attribute, Object value) {
        if (!(value instanceof Map<?, ?> change)) {
            return null;
        }
        if (change.isEmpty()) {
            return LdapProblem.of(LdapProblem.Kind.CHANGES_EMPTY_MAP);
        }
        Set<String> unknown = new TreeSet<>();
        for (Object key : change.keySet()) {
            if (!ADD.equals(key) && !REMOVE.equals(key)) {
                unknown.add(String.valueOf(key));
            }
        }
        if (!unknown.isEmpty()) {
            return LdapProblem.of(LdapProblem.Kind.CHANGES_UNKNOWN_KEYS, unknown);
        }
        for (String key : List.of(ADD, REMOVE)) {
            if (!change.containsKey(key)) {
                continue;
            }
            Object values = change.get(key);
            if (values == null || values instanceof Collection<?> c && c.isEmpty()) {
                return LdapProblem.of(LdapProblem.Kind.CHANGES_EMPTY_KEY, key);
            }
            if (values instanceof Map<?, ?> || values instanceof Collection<?> c
                    && c.stream().anyMatch(v -> v == null || v instanceof Map<?, ?> || v instanceof Collection<?>)) {
                return LdapProblem.of(LdapProblem.Kind.CHANGES_PLAIN_KEY, key);
            }
        }
        if (change.containsKey(ADD) && change.containsKey(REMOVE)) {
            List<Object> overlap = new ArrayList<>();
            for (Object added : values(change.get(ADD))) {
                boolean removed = values(change.get(REMOVE)).stream()
                        .anyMatch(other -> LdapValueMatcher.sameValue(added, other));
                if (removed && overlap.stream().noneMatch(kept -> LdapValueMatcher.sameValue(kept, added))) {
                    overlap.add(added);
                }
            }
            if (!overlap.isEmpty()) {
                return SecretNames.isSecret(attribute)
                        ? LdapProblem.of(LdapProblem.Kind.CHANGES_OVERLAP_SECRET)
                        : LdapProblem.of(LdapProblem.Kind.CHANGES_OVERLAP, overlap);
            }
        }
        return null;
    }

    /** For add, the wanted values the entry already has; for remove, those it does not have. */
    static List<Object> offending(String key, Object wanted, Object actual) {
        List<Object> current = values(actual);
        boolean add = ADD.equals(key);
        List<Object> result = new ArrayList<>();
        for (Object value : values(wanted)) {
            boolean present = current.stream().anyMatch(other -> LdapValueMatcher.sameValue(value, other, true));
            if (present == add) {
                result.add(value);
            }
        }
        return result;
    }

    static String reason(String key, String attribute, List<Object> offending) {
        String pronoun = offending.size() == 1 ? "it" : "them";
        return ADD.equals(key)
                ? "cannot add " + offending + " to " + attribute + ": the entry already has " + pronoun
                : "cannot remove " + offending + " from " + attribute + ": the entry does not have " + pronoun;
    }

    /** As {@link #reason}, for a secret-named attribute: no values, and no claim about the entry. */
    static String secretReason(String key, String attribute) {
        return ADD.equals(key)
                ? "cannot add to " + attribute + ": the directory refused it because a value already exists"
                : "cannot remove from " + attribute + ": the directory refused it because a value is missing";
    }

    static List<Object> values(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of(value);
    }
}
