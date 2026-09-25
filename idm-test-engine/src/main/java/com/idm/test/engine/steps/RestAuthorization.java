package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.core.model.OperationResult;

import java.util.Map;

/**
 * Adds a target's {@code Authorization} header to a request, shared by rest.request,
 * rest.verify and http.request so the three cannot drift apart.
 * <p>
 * The step's own header wins: if the author wrote {@code Authorization} in any letter case, the
 * target's settings are not consulted at all, so no token is fetched for a request that will not
 * use it and a scenario can send bad credentials on purpose. The added header is never a step
 * parameter, so it never reaches a message, a capture or the results file.
 */
final class RestAuthorization {

    private static final String AUTHORIZATION = "Authorization";

    private RestAuthorization() {
    }

    /**
     * Call only after {@link RestTargets#resolveBaseUrl} succeeded, so the target exists.
     *
     * @return null when {@code headers} is ready to send, or a failure message naming the step
     */
    static String apply(String stepName, String target, RestProperties properties,
                        RestAuthenticator authenticator, Map<String, String> headers) {
        if (headers.keySet().stream().anyMatch(AUTHORIZATION::equalsIgnoreCase)) {
            return null;
        }
        OperationResult<String> result = authenticator.authorization(target, properties.getRest().get(target));
        if (result.isFailure()) {
            return stepName + " could not authenticate: " + result.getMessage();
        }
        result.getData().ifPresent(value -> headers.put(AUTHORIZATION, value));
        return null;
    }
}
