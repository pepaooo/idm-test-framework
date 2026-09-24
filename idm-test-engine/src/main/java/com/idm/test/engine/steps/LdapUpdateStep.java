package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.core.model.OperationResult;
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

    private final EdirectoryConnector connector;

    public LdapUpdateStep(EdirectoryConnector connector) {
        this.connector = connector;
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
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the dn is already in hand, so there is no extra work
        // to skip when nobody asked to capture it.
        String dn = String.valueOf(params.get("dn"));
        Map<String, Object> attributes = (Map<String, Object>) params.get("attributes");

        if (attributes == null || attributes.isEmpty()) {
            return StepOutcome.failure(
                    "ldap.update for " + dn + " has no attributes to change");
        }

        OperationResult<Void> result = connector.update(dn, attributes);
        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "ldap.update failed for " + dn + ": " + result.getMessage());
        }

        return StepOutcome.success("updated " + dn + " " + attributes.keySet(), dn);
    }
}
