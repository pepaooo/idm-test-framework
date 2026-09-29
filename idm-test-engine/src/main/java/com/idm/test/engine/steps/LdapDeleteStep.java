package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;

/**
 * Deletes an LDAP entry. Registers nothing, like every step; recreating a deleted entry is the
 * author's job, with an explicit {@code ldap.create}.
 */
public final class LdapDeleteStep implements StepType {

    private final LdapDirectories directories;

    public LdapDeleteStep(LdapDirectories directories) {
        this.directories = directories;
    }

    @Override
    public String name() {
        return "ldap.delete";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("dn");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("dn", "directory");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the dn is already in hand, so there is no extra work
        // to skip when nobody asked to capture it.
        String dn = String.valueOf(params.get("dn"));
        LdapTarget target = LdapTarget.of("ldap.delete", params, directories);
        if (target.isFailure()) {
            return target.failure();
        }
        EdirectoryConnector connector = target.connector();
        String where = target.at(dn);

        // Checked explicitly because LdapTemplate.unbind succeeds silently on a DN that was
        // never there, so the connector reports success for a deletion that deleted nothing.
        // A scenario that asks to delete an entry believes it exists; if it does not, the
        // scenario's model of the world is already wrong and saying "deleted" would hide that.
        OperationResult<Boolean> exists = connector.exists(dn);
        if (!exists.isSuccess()) {
            return StepOutcome.failure("ldap.delete could not check whether " + where
                    + " exists: " + exists.getMessage());
        }
        if (!Boolean.TRUE.equals(exists.getData().orElse(false))) {
            return StepOutcome.failure("ldap.delete found no entry at " + where + " to delete");
        }

        OperationResult<Void> result = connector.delete(dn);

        if (!result.isSuccess()) {
            return StepOutcome.failure("ldap.delete failed for " + where + ": " + result.getMessage());
        }

        return StepOutcome.success("deleted " + where, dn);
    }
}
