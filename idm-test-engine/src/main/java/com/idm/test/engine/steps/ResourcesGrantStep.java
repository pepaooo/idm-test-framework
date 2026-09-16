package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.generated.resource.RequestResourceGrantResponse;
import com.idm.test.connector.soap.generated.resource.RequestResourceRevokeResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;

/**
 * Grants a resource to a user.
 * <p>
 * Registers an undo, unlike {@code roles.grant}: {@code revokeResource} takes exactly the
 * arguments {@code grantResource} was given, so the inverse is built from inputs already in
 * hand rather than from a server-returned DN whose semantics cannot be checked. That is the
 * same rule that gave {@code ldap.create} an undo and denied one to {@code ldap.delete}.
 */
public final class ResourcesGrantStep implements StepType {

    private final ResourceServiceConnector connector;

    public ResourcesGrantStep(ResourceServiceConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "resources.grant";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("user", "resource");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("user", "resource", "reason");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the request identifier comes back from the call
        // itself, so there is no extra work to skip.
        String user = String.valueOf(params.get("user"));
        String resource = String.valueOf(params.get("resource"));
        Object rawReason = params.get("reason");
        String reason = rawReason == null ? null : String.valueOf(rawReason);

        OperationResult<RequestResourceGrantResponse> result =
                connector.grantResource(user, resource, reason);

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "resources.grant failed for " + user + ": " + result.getMessage());
        }

        String requestId = SoapValues.of(result.getData().get().getResult());

        return StepOutcome.success(
                "granted " + resource + " to " + user,
                () -> {
                    OperationResult<RequestResourceRevokeResponse> revoked =
                            connector.revokeResource(user, resource, reason);
                    if (!revoked.isSuccess()) {
                        throw new IllegalStateException("cleanup failed to revoke " + resource
                                + " from " + user + ": " + revoked.getMessage());
                    }
                },
                requestId);
    }
}
