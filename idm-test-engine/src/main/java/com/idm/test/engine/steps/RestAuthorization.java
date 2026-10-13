package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.core.model.ErrorType;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;

import java.util.Map;

/**
 * Adds a target's {@code Authorization} header to a request, shared by rest.request,
 * rest.verify and http.request so the three cannot drift apart.
 * <p>
 * The step's own header wins: if the author wrote {@code Authorization} in any letter case, the
 * target's settings are not consulted at all, so no token is fetched for a request that will not
 * use it and a scenario can send bad credentials on purpose. The added header is never a step
 * parameter, so it never reaches a message or a capture; it reaches the results and the report only as
 * masked evidence ({@code ***}).
 */
final class RestAuthorization {

    private static final String AUTHORIZATION = "Authorization";

    private RestAuthorization() {
    }

    /**
     * Call only after {@link RestTargets#resolveBaseUrl} succeeded, so the target exists.
     *
     * @return null when {@code headers} is ready to send; otherwise a failure naming the step,
     * final when waiting cannot change it (misconfigured or rejected credentials)
     */
    static StepOutcome apply(String stepName, String target, RestProperties properties,
                             RestAuthenticator authenticator, Map<String, String> headers) {
        if (headers.keySet().stream().anyMatch(AUTHORIZATION::equalsIgnoreCase)) {
            return null;
        }
        RestAuthenticator.Authorization authorization = authenticator.authorize(target, properties.getRest().get(target));
        if (authorization.isFailure()) {
            Message message = Message.of("step.rest.auth.failed", stepName, ConnectorProblems.of(authorization.problem()));
            ErrorType type = authorization.errorType() == null ? ErrorType.UNKNOWN_ERROR : authorization.errorType();
            return type == ErrorType.CONFIGURATION_ERROR || type == ErrorType.AUTHENTICATION_ERROR
                    ? StepOutcome.finalFailure(message)
                    : StepOutcome.failure(message);
        }
        if (authorization.header() != null) {
            headers.put(AUTHORIZATION, authorization.header());
        }
        return null;
    }
}
