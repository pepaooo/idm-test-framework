package com.idm.test.engine.steps;

import java.util.Collection;
import java.util.Map;

/**
 * Checks the lists an {@code ldap.create} or {@code ldap.update} writes as multi-valued
 * attributes, before the directory is called.
 */
final class LdapLists {

    private LdapLists() {
    }

    /**
     * @param step       the step name, for the message
     * @param where      the entry, as the step names it
     * @param emptyAllowed whether an empty list is meaningful (it removes the attribute in an update)
     * @return the failure message, or {@code null} when every list can be written
     */
    static String problem(String step, String where, Map<String, Object> attributes, boolean emptyAllowed) {
        for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
            if (!(attribute.getValue() instanceof Collection<?> values)) {
                continue;
            }
            String prefix = step + " for " + where + ": attribute '" + attribute.getKey() + "' ";
            if (values.isEmpty() && !emptyAllowed) {
                return prefix + "has an empty list; there is nothing to write";
            }
            if (values.stream().anyMatch(v -> v == null || v instanceof Map<?, ?> || v instanceof Collection<?>)) {
                return prefix + "has a null, map or list value inside its list; give plain values";
            }
        }
        return null;
    }
}
