package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Creates an LDAP entry.
 * <p>
 * DNs are relative to the configured base, matching the connector's contract.
 */
public final class LdapCreateStep implements StepType {

    private final LdapDirectories directories;

    public LdapCreateStep(LdapDirectories directories) {
        this.directories = directories;
    }

    @Override
    public String name() {
        return "ldap.create";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("dn");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("dn", "attributes", "directory");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the created DN is always cheap to return, so there is
        // no extra work to skip when nobody asked to capture it.
        String dn = String.valueOf(params.get("dn"));
        LdapTarget target = LdapTarget.of("ldap.create", params, directories);
        if (target.isFailure()) {
            return target.failure();
        }
        EdirectoryConnector connector = target.connector();
        Object where = target.at(dn);
        Map<String, Object> attributes =
                (Map<String, Object>) params.getOrDefault("attributes", Map.of());

        Message listProblem = LdapLists.problem("ldap.create", where, attributes, false);
        if (listProblem != null) {
            return StepOutcome.finalFailure(listProblem);
        }

        LdapEvidence evidence = LdapEvidence.written(LdapTarget.requested(params), dn, "create", attributes);
        LdapUser user = toLdapUser(dn, attributes);
        OperationResult<LdapUser> result = connector.create(user);

        if (!result.isSuccess()) {
            return StepOutcome.failure(Message.of("step.ldap.create.failed", where,
                    LdapProblems.failure(result, EdirectoryConnector::createProblem)),
                    result.getErrorType().orElse(ErrorType.UNKNOWN_ERROR))
                    .withEvidence(evidence);
        }

        return StepOutcome.success(Message.of("step.ldap.create.created", where), dn).withEvidence(evidence);
    }

    /**
     * Maps the well-known attribute names onto LdapUser; anything else becomes a custom
     * attribute, which is how scenarios carry directory-specific fields.
     * A list, for any name, is written as several values.
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
        if (attributes.get(key) instanceof Collection<?>) {
            // Several values: the typed field holds one, so leave it to be written as a
            // custom attribute with every value.
            return null;
        }
        Object value = attributes.remove(key);
        return value == null ? null : value.toString();
    }
}
