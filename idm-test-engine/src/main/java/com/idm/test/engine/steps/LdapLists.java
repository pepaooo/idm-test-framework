package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.LdapValueChanges;
import com.idm.test.engine.spi.Message;

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
    static Message problem(String step, Object where, Map<String, Object> attributes, boolean update) {
        for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
            String name = attribute.getKey();
            Object value = attribute.getValue();
            if (value instanceof Map<?, ?>) {
                if (!update) {
                    return Message.of("step.ldap.lists.addRemoveOnlyUpdate", step, where, name);
                }
                String changeProblem = LdapValueChanges.shapeProblem(name, value);
                if (changeProblem != null) {
                    return Message.of("step.ldap.lists.shape", step, where, name, changeProblem);
                }
                continue;
            }
            if (!(value instanceof Collection<?> values)) {
                continue;
            }
            if (values.isEmpty() && !update) {
                return Message.of("step.ldap.lists.emptyList", step, where, name);
            }
            if (values.stream().anyMatch(v -> v == null || v instanceof Map<?, ?> || v instanceof Collection<?>)) {
                return Message.of("step.ldap.lists.badValue", step, where, name);
            }
        }
        return null;
    }
}
