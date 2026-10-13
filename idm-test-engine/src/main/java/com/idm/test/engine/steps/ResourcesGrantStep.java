package com.idm.test.engine.steps;

import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.SoapProblem;
import com.idm.test.connector.soap.generated.resource.RequestResourceGrantResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;

/**
 * Grants a resource to a user. Registers nothing. To revoke afterwards, write
 * {@code resources.revoke} in the scenario's {@code cleanup:} block.
 */
public final class ResourcesGrantStep implements StepType {

    private final ResourceServiceConnector connector;

    /**
     * @param connector the SOAP connector, or null when the service is not configured; execute then fails without calling anything
     */
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
        if (connector == null) {
            return StepOutcome.finalFailure(Message.of("step.resources.grant.notConfigured"));
        }
        // captureRequested is ignored: the request identifier comes back from the call
        // itself, so there is no extra work to skip.
        String user = String.valueOf(params.get("user"));
        String resource = String.valueOf(params.get("resource"));
        Object rawReason = params.get("reason");
        String reason = rawReason == null ? null : String.valueOf(rawReason);

        OperationResult<RequestResourceGrantResponse> result =
                connector.grantResource(user, resource, reason);
        HttpEvidence evidence = SoapExchanges.evidence(connector.lastExchange(), result);

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(Message.of("step.resources.grant.failed",
                    user, ConnectorProblems.soap(result, SoapProblem::grantResource))).withEvidence(evidence);
        }

        String requestId = SoapValues.of(result.getData().get().getResult());

        return StepOutcome.success(Message.of("step.resources.grant.granted", resource, user), requestId)
                .withEvidence(evidence);
    }
}
