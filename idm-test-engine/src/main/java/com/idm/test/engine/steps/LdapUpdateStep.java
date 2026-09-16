package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces attribute values on an existing LDAP entry.
 * <p>
 * Reads the entry first so its undo can put back exactly what was there. Attributes that had
 * no prior value are removed rather than left behind, which is why this needs
 * {@link EdirectoryConnector#removeAttributes} — a REPLACE cannot express "there was nothing
 * here before".
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

        // Read before writing, so the undo restores values rather than guessing at them.
        OperationResult<LdapUser> before = connector.read(dn);
        if (!before.isSuccess() || before.getData().isEmpty()) {
            return StepOutcome.failure("ldap.update could not read " + dn
                    + " before updating it: " + before.getMessage());
        }

        Map<String, Object> priorView = LdapAttributes.of(before.getData().get());

        Map<String, Object> restore = new LinkedHashMap<>();
        List<String> absentBefore = new ArrayList<>();
        for (String name : attributes.keySet()) {
            if (priorView.containsKey(name)) {
                restore.put(name, priorView.get(name));
            } else {
                absentBefore.add(name);
            }
        }

        OperationResult<Void> result = connector.update(dn, attributes);
        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "ldap.update failed for " + dn + ": " + result.getMessage());
        }

        return StepOutcome.success(
                "updated " + dn + " " + attributes.keySet(),
                () -> undo(dn, restore, absentBefore),
                dn);
    }

    /**
     * Puts the entry back: prior values replaced, newly-introduced attributes removed.
     * Throws on failure so {@code ScenarioEngine.unwind} logs a real cleanup problem rather
     * than reporting a restore that did not happen.
     */
    private void undo(String dn, Map<String, Object> restore, List<String> absentBefore) {
        if (!restore.isEmpty()) {
            OperationResult<Void> restored = connector.update(dn, restore);
            if (!restored.isSuccess()) {
                throw new IllegalStateException("cleanup failed to restore " + restore.keySet()
                        + " on " + dn + ": " + restored.getMessage());
            }
        }
        if (!absentBefore.isEmpty()) {
            OperationResult<Void> removed = connector.removeAttributes(dn, absentBefore);
            if (!removed.isSuccess()) {
                throw new IllegalStateException("cleanup failed to remove " + absentBefore
                        + " from " + dn + ": " + removed.getMessage());
            }
        }
    }
}
