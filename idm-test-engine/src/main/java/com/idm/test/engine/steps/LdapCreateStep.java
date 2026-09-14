package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates an LDAP entry.
 * <p>
 * DNs are relative to the configured base, matching the connector's contract.
 */
public final class LdapCreateStep implements StepType {

    private final EdirectoryConnector connector;

    public LdapCreateStep(EdirectoryConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "ldap.create";
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params) {
        String dn = String.valueOf(params.get("dn"));
        Map<String, Object> attributes =
                (Map<String, Object>) params.getOrDefault("attributes", Map.of());

        LdapUser user = toLdapUser(dn, attributes);
        OperationResult<LdapUser> result = connector.create(user);

        if (!result.isSuccess()) {
            return StepOutcome.failure("ldap.create failed for " + dn + ": " + result.getMessage());
        }

        return StepOutcome.success("created " + dn, () -> connector.delete(dn));
    }

    /**
     * Maps the well-known attribute names onto LdapUser; anything else becomes a custom
     * attribute, which is how scenarios carry directory-specific fields.
     */
    private static LdapUser toLdapUser(String dn, Map<String, Object> attributes) {
        LdapUser user = new LdapUser();
        user.setDn(dn);

        Map<String, Object> remaining = new LinkedHashMap<>(attributes);
        user.setCn(take(remaining, "cn"));
        user.setSn(take(remaining, "sn"));
        user.setGivenName(take(remaining, "givenName"));
        user.setMail(take(remaining, "mail"));
        user.setUid(take(remaining, "uid"));
        user.setTelephoneNumber(take(remaining, "telephoneNumber"));
        user.setTitle(take(remaining, "title"));
        user.setOu(take(remaining, "ou"));
        user.setDescription(take(remaining, "description"));
        user.setUserPassword(take(remaining, "userPassword"));

        remaining.forEach(user::addCustomAttribute);
        return user;
    }

    private static String take(Map<String, Object> attributes, String key) {
        Object value = attributes.remove(key);
        return value == null ? null : value.toString();
    }
}
