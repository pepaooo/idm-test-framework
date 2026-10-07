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
     * @return {@code null} when {@code value} is not a change (not a map) or is a well-formed one,
     *         else what is wrong with it, as a fragment to follow the attribute's name
     */
    public static String shapeProblem(Object value) {
        if (!(value instanceof Map<?, ?> change)) {
            return null;
        }
        if (change.isEmpty()) {
            return "is an empty map; use add and/or remove";
        }
        Set<String> unknown = new TreeSet<>();
        for (Object key : change.keySet()) {
            if (!ADD.equals(key) && !REMOVE.equals(key)) {
                unknown.add(String.valueOf(key));
            }
        }
        if (!unknown.isEmpty()) {
            return "has unknown keys " + unknown + "; use add and/or remove";
        }
        for (String key : List.of(ADD, REMOVE)) {
            if (!change.containsKey(key)) {
                continue;
            }
            Object values = change.get(key);
            if (values == null || values instanceof Collection<?> c && c.isEmpty()) {
                return "has an empty '" + key + "'";
            }
            if (values instanceof Map<?, ?> || values instanceof Collection<?> c
                    && c.stream().anyMatch(v -> v == null || v instanceof Map<?, ?> || v instanceof Collection<?>)) {
                return "has a null, map or list value in '" + key + "'; give plain values";
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
                return "lists " + overlap + " in both 'add' and 'remove'";
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
