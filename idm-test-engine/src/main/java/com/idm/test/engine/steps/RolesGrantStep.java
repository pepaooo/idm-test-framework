package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.connector.soap.generated.role.RequestRolesAssignmentResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grants one or more roles to a user.
 * <p>
 * <b>Registers no undo, deliberately.</b> The inverse, {@code removeRoles}, consumes role
 * assignment DNs rather than role definition DNs, and nothing in the role service maps a user
 * and a role back to an assignment DN. Handing back the DNs this call returned would be
 * plausible, but it cannot be validated against anything but a stub until a live eDirectory
 * exists — so a scenario that needs cleanup writes an explicit {@code roles.revoke} using the
 * DNs captured here through {@code as:}.
 */
public final class RolesGrantStep implements StepType {

    private final RoleServiceConnector connector;

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
        // captureRequested is ignored: the assignment DNs come back from the call itself, so
        // there is no extra work to skip when nobody asked to capture them.
        String user = String.valueOf(params.get("user"));
        List<String> roles = StepValues.toStringList(params.get("roles"));
        Object reason = params.get("reason");

        if (roles.isEmpty()) {
            return StepOutcome.failure(
                    "roles.grant for " + user + " has no roles to grant");
        }

        OperationResult<RequestRolesAssignmentResponse> result = connector.assignRoles(
                user, roles.toArray(new String[0]),
                reason == null ? null : String.valueOf(reason));

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "roles.grant failed for " + user + ": " + result.getMessage());
        }

        List<String> assignments = SoapDns.of(result.getData().get().getResult());

        return StepOutcome.success(
                "granted " + roles + " to " + user, null, assignments);
    }
}
