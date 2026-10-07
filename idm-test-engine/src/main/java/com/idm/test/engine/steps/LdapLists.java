package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.LdapValueChanges;

import java.util.Collection;
import java.util.Map;

/**
 * Checks the lists, and the add/remove changes, an {@code ldap.create} or {@code ldap.update}
 * writes as multi-valued attributes, before the directory is called.
 */
final class LdapLists {

    private LdapLists() {
    }

    /**
     * @param step   the step name, for the message
     * @param where  the entry, as the step names it
     * @param update whether this is ldap.update: an empty list is meaningful there (it removes the
     *               attribute), and so is an add/remove map; ldap.create allows neither
     * @return the failure message, or {@code null} when every value can be written
     */
    static String problem(String step, String where, Map<String, Object> attributes, boolean update) {
        for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
            String prefix = step + " for " + where + ": attribute '" + attribute.getKey() + "' ";
            Object value = attribute.getValue();
            if (value instanceof Map<?, ?>) {
                if (!update) {
                    return prefix + "uses add/remove, which only apply to ldap.update";
                }
                String changeProblem = LdapValueChanges.shapeProblem(attribute.getKey(), value);
                if (changeProblem != null) {
                    return prefix + changeProblem;
                }
                continue;
            }
            if (!(value instanceof Collection<?> values)) {
                continue;
            }
            if (values.isEmpty() && !update) {
                return prefix + "has an empty list; there is nothing to write";
            }
            if (values.stream().anyMatch(v -> v == null || v instanceof Map<?, ?> || v instanceof Collection<?>)) {
                return prefix + "has a null, map or list value inside its list; give plain values";
            }
        }
        return null;
    }
}
