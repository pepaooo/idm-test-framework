package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;

/**
 * Replaces attribute values on an existing LDAP entry. Registers nothing: to restore prior
 * values, capture them with {@code ldap.verify ... as:} and write an {@code ldap.update} in
 * the scenario's {@code cleanup:} block.
 */
public final class LdapUpdateStep implements StepType {

    private final LdapDirectories directories;

    public LdapUpdateStep(LdapDirectories directories) {
        this.directories = directories;
    }

    @Override
    public String name() {
        return "ldap.update";
    }

    @Override
    public Set<String> requiredParams() {
        // 'attributes' is required, not optional: an update with nothing to change is always
        // an authoring mistake, and silently succeeding would hide it.
        return Set.of("dn", "attributes");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("dn", "attributes", "directory");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the dn is already in hand, so there is no extra work
        // to skip when nobody asked to capture it.
        String dn = String.valueOf(params.get("dn"));
        LdapTarget target = LdapTarget.of("ldap.update", params, directories);
        if (target.isFailure()) {
            return target.failure();
        }
        EdirectoryConnector connector = target.connector();
        Object where = target.at(dn);
        Map<String, Object> attributes = (Map<String, Object>) params.get("attributes");

        if (attributes == null || attributes.isEmpty()) {
            return StepOutcome.failure(Message.of("step.ldap.update.noAttributes", where));
        }

        Message listProblem = LdapLists.problem("ldap.update", where, attributes, true);
        if (listProblem != null) {
            return StepOutcome.failure(listProblem);
        }

        LdapEvidence evidence = LdapEvidence.written(LdapTarget.requested(params), dn, "update", attributes);
        OperationResult<Void> result = connector.update(dn, attributes);
        if (!result.isSuccess()) {
            return StepOutcome.failure(Message.of("step.ldap.update.failed", where,
                    LdapProblems.failure(result, e -> EdirectoryConnector.updateProblem(attributes, e))))
                    .withEvidence(evidence);
        }

        return StepOutcome.success(Message.of("step.ldap.update.updated", where, attributes.keySet()), dn)
                .withEvidence(evidence);
    }
}
