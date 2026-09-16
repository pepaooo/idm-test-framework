package com.idm.test.engine.steps;

import java.util.List;
import java.util.Map;

/**
 * Small conversions every step needs on YAML input.
 */
final class StepValues {

    private StepValues() {
    }

    /**
     * Accepts a list or a single value, so a scenario granting one role may write
     * {@code roles: "cn=SAP,ou=roles"} instead of a one-element list. A null or an empty
     * list both become an empty list; callers decide whether that is an error, because for
     * some steps it is and for others it would not be.
     */
    static List<String> toStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(raw));
    }

    /**
     * @return the map under {@code key}, or an empty map when absent
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> toMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
