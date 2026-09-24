package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.model.LdapUser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flattens an {@link LdapUser} into the name→value view a scenario sees, which
 * {@code LdapVerifyStep} exposes through {@code as:}.
 */
final class LdapAttributes {

    private LdapAttributes() {
    }

    /**
     * Null-valued attributes are omitted rather than bound as null, so a path reference to one
     * fails loudly instead of rendering the literal text "null".
     */
    static Map<String, Object> of(LdapUser user) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, "dn", user.getDn());
        putIfPresent(attributes, "cn", user.getCn());
        putIfPresent(attributes, "sn", user.getSn());
        putIfPresent(attributes, "givenName", user.getGivenName());
        putIfPresent(attributes, "mail", user.getMail());
        putIfPresent(attributes, "uid", user.getUid());
        putIfPresent(attributes, "telephoneNumber", user.getTelephoneNumber());
        putIfPresent(attributes, "title", user.getTitle());
        putIfPresent(attributes, "ou", user.getOu());
        putIfPresent(attributes, "description", user.getDescription());
        attributes.putAll(user.getCustomAttributes());
        return attributes;
    }

    private static void putIfPresent(Map<String, Object> attributes, String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }
}
