package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.connector.soap.generated.role.RemoveRolesResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes role assignments.
 * <p>
 * Takes assignment DNs rather than a user and a role, because that is what the underlying
 * {@code removeRoles} operation consumes and nothing in the role service maps a role
 * definition DN back to an assignment DN. Capture them from {@code roles.grant} with
 * {@code as:} and pass them here.
 */
public final class RolesRevokeStep implements StepType {

    private final RoleServiceConnector connector;

    /**
     * @param connector the SOAP connector, or null when the service is not configured; execute then fails without calling anything
     */
    public RolesRevokeStep(RoleServiceConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "roles.revoke";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("assignments");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        if (connector == null) {
            return StepOutcome.finalFailure(
                    "roles.revoke cannot run: no Role service is configured (idm.test.soap.url)");
        }
        List<String> assignments = StepValues.toStringList(params.get("assignments"));

        if (assignments.isEmpty()) {
            // Revoking nothing is always an authoring mistake — most likely a capture that
            // came back empty — and succeeding would hide it.
            return StepOutcome.failure("roles.revoke has no assignments to remove");
        }

        OperationResult<RemoveRolesResponse> result =
                connector.removeRoles(assignments.toArray(new String[0]));
        HttpEvidence evidence = SoapExchanges.evidence(connector.lastExchange(), result);

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("roles.revoke failed for " + assignments
                    + ": " + result.getMessage()).withEvidence(evidence);
        }

        return StepOutcome.success("revoked " + assignments,
                SoapDns.of(result.getData().get().getResult())).withEvidence(evidence);
    }
}
