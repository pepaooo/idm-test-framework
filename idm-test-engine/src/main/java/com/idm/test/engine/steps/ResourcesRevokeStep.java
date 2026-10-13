package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.SoapProblem;
import com.idm.test.connector.soap.generated.resource.RequestResourceRevokeResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;

/**
 * Revokes a resource from a user. Registers nothing. A scenario that wants the resource back
 * grants it again explicitly.
 */
public final class ResourcesRevokeStep implements StepType {

    private final ResourceServiceConnector connector;

    /**
     * @param connector the SOAP connector, or null when the service is not configured; execute then fails without calling anything
     */
    public ResourcesRevokeStep(ResourceServiceConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "resources.revoke";
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
        if (connector == null) {
            return StepOutcome.finalFailure(Message.of("step.resources.revoke.notConfigured"));
        }
        String user = String.valueOf(params.get("user"));
        String resource = String.valueOf(params.get("resource"));
        Object rawReason = params.get("reason");

        OperationResult<RequestResourceRevokeResponse> result = connector.revokeResource(
                user, resource, rawReason == null ? null : String.valueOf(rawReason));
        HttpEvidence evidence = SoapExchanges.evidence(connector.lastExchange(), result);

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(Message.of("step.resources.revoke.failed",
                    user, ConnectorProblems.soap(result, SoapProblem::revokeResource))).withEvidence(evidence);
        }

        return StepOutcome.success(
                Message.of("step.resources.revoke.revoked", resource, user),
                SoapValues.of(result.getData().get().getResult())).withEvidence(evidence);
    }
}
