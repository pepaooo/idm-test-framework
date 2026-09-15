package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Asserts that an LDAP entry carries the expected attribute values.
 * <p>
 * Retryable: provisioning is asynchronous, so the engine may call this repeatedly under a
 * {@code wait} until it passes or the timeout expires.
 */
public final class LdapVerifyStep implements StepType {

    private final EdirectoryConnector connector;

    public LdapVerifyStep(EdirectoryConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "ldap.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Set<String> requiredParams() {
        // 'expect' is required, not defaulted to empty: an empty map would make every
        // comparison in EdirectoryConnector.verifyAttributes vacuously true, so a typo'd
        // key (e.g. "expct") must fail validation instead of silently verifying nothing.
        return Set.of("dn", "expect");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params) {
        String dn = String.valueOf(params.get("dn"));
        Map<String, Object> expected =
                (Map<String, Object>) params.getOrDefault("expect", Map.of());

        OperationResult<Boolean> result = connector.verifyAttributes(dn, expected);

        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "ldap.verify failed for " + dn + ": " + result.getMessage());
        }

        // Read once, only after verification passes, so a polling assertion does not double
        // its read load on every failed attempt.
        OperationResult<LdapUser> entry = connector.read(dn);
        Map<String, Object> attributes = entry.isSuccess() && entry.getData().isPresent()
                ? attributesOf(entry.getData().get())
                : Map.of();

        return StepOutcome.success("verified " + dn, null, attributes);
    }

    /**
     * The attribute view a scenario sees through {@code as:}. Null-valued attributes are
     * omitted rather than bound as null, so a path reference to one fails loudly.
     */
    private static Map<String, Object> attributesOf(LdapUser user) {
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
