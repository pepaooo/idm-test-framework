package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.connector.soap.client.SoapProblem;
import com.idm.test.connector.soap.generated.role.RequestRolesAssignmentResponse;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grants one or more roles to a user. Registers nothing. A scenario that needs cleanup writes
 * an explicit {@code roles.revoke} in its {@code cleanup:} block, using the DNs captured here
 * through {@code as:}.
 */
public final class RolesGrantStep implements StepType {

    private final RoleServiceConnector connector;

    /**
     * @param connector the SOAP connector, or null when the service is not configured; execute then fails without calling anything
     */
    public RolesGrantStep(RoleServiceConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "roles.grant";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("user", "roles");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("user", "roles", "reason");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        if (connector == null) {
            return StepOutcome.finalFailure(Message.of("step.roles.grant.notConfigured"));
        }
        // captureRequested is ignored: the assignment DNs come back from the call itself, so
        // there is no extra work to skip when nobody asked to capture them.
        String user = String.valueOf(params.get("user"));
        List<String> roles = StepValues.toStringList(params.get("roles"));
        Object reason = params.get("reason");

        if (roles.isEmpty()) {
            return StepOutcome.failure(Message.of("step.roles.grant.noRoles", user));
        }

        OperationResult<RequestRolesAssignmentResponse> result = connector.assignRoles(
                user, roles.toArray(new String[0]),
                reason == null ? null : String.valueOf(reason));
        HttpEvidence evidence = SoapExchanges.evidence(connector.lastExchange(), result);

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(Message.of("step.roles.grant.failed",
                    user, ConnectorProblems.soap(result, SoapProblem::assignRoles)),
                    result.getErrorType().orElse(ErrorType.UNKNOWN_ERROR)).withEvidence(evidence);
        }

        List<String> assignments = SoapDns.of(result.getData().get().getResult());

        if (assignments.isEmpty()) {
            // The service call itself succeeded, but returned no assignment DNs at all. Every
            // scenario in this corpus survives only because a roles.grant is always followed
            // by a roles.revoke, whose own empty-assignments guard happens to catch this — an
            // invariant nothing enforces. Reporting success here, with as: bound to an empty
            // list, would be the vacuous-assertion trap: a grant that verified it granted
            // nothing.
            return StepOutcome.failure(Message.of("step.roles.grant.noAssignments", user, roles))
                    .withEvidence(evidence);
        }

        return StepOutcome.success(
                Message.of("step.roles.grant.granted", roles, user), assignments).withEvidence(evidence);
    }
}
