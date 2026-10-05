package com.idm.test.engine.spi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One LDAP operation as a step made it. The constructor masks secret-named attributes
 * ({@code userPassword}, {@code unicodePwd}, …) and keeps at most
 * {@link #MAX_ATTRIBUTE_VALUES} values per attribute.
 *
 * @param directory the {@code directory:} the step named, or null for the default
 * @param operation create, update, delete, read or exists
 * @param present   whether the entry was there, for exists and for a missing entry; else null
 */
public record LdapEvidence(String directory, String dn, String operation, Map<String, Object> attributes,
                           Boolean present) implements Evidence {

    public LdapEvidence {
        attributes = EvidenceMasking.attributes(attributes);
    }

    public static LdapEvidence written(String directory, String dn, String operation, Map<String, ?> attributes) {
        return new LdapEvidence(directory, dn, operation, copy(attributes), null);
    }

    public static LdapEvidence read(String directory, String dn, Map<String, ?> attributes) {
        return new LdapEvidence(directory, dn, "read", copy(attributes), null);
    }

    public static LdapEvidence deleted(String directory, String dn) {
        return new LdapEvidence(directory, dn, "delete", Map.of(), null);
    }

    public static LdapEvidence exists(String directory, String dn, boolean present) {
        return new LdapEvidence(directory, dn, "exists", Map.of(), present);
    }

    /** The operation found no entry at the DN. */
    public static LdapEvidence absent(String directory, String dn, String operation) {
        return new LdapEvidence(directory, dn, operation, Map.of(), false);
    }

    private static Map<String, Object> copy(Map<String, ?> attributes) {
        return attributes == null ? Map.of() : new LinkedHashMap<>(attributes);
    }
}
