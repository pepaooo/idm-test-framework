package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.RestResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Makes one HTTP request against a configured target.
 * <p>
 * An HTTP error status is an outcome, not a failure: a 404 produces a successful step whose
 * captured {@code status} is 404, so a scenario can assert that an account is gone. Only a
 * transport-level problem fails the step.
 */
public final class RestRequestStep implements StepType {

    private final IdmRestConnector connector;
    private final RestProperties properties;
    private final RestAuthenticator authenticator;

    public RestRequestStep(IdmRestConnector connector, RestProperties properties, RestAuthenticator authenticator) {
        this.connector = connector;
        this.properties = properties;
        this.authenticator = authenticator;
    }

    @Override
    public String name() {
        return "rest.request";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("target", "method", "path");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("target", "method", "path", "body", "bodyFrom", "headers");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String target = String.valueOf(params.get("target"));

        if (params.containsKey("body") && params.containsKey("bodyFrom")) {
            // They mean the same thing, so supplying both says nothing about which was
            // intended. Guessing would make the scenario's behaviour depend on our ordering.
            return StepOutcome.failure("rest.request supplies both 'body' and 'bodyFrom'; "
                    + "they mean the same thing, so give only one");
        }

        RestTargets.Resolution resolution = RestTargets.resolveBaseUrl("rest.request", target, properties);
        if (resolution.isFailure()) {
            return StepOutcome.failure(resolution.error());
        }

        String url = resolution.baseUrl() + String.valueOf(params.get("path"));
        Object body = params.containsKey("bodyFrom")
                ? params.get("bodyFrom")
                : params.get("body");

        Map<String, String> headers = new LinkedHashMap<>();
        StepValues.toMap(params.get("headers"))
                .forEach((key, value) -> headers.put(key, String.valueOf(value)));

        String authError = RestAuthorization.apply("rest.request", target, properties, authenticator, headers);
        if (authError != null) {
            return StepOutcome.failure(authError);
        }

        OperationResult<RestResponse> result = connector.exchange(
                String.valueOf(params.get("method")), url, body, headers);

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "rest.request failed for " + url + ": " + result.getMessage());
        }

        RestResponse response = result.getData().get();

        return StepOutcome.success(
                String.valueOf(params.get("method")) + " " + url + " returned " + response.status(),
                captureOf(response));
    }

    /**
     * The {status, headers, body} view a scenario sees through {@code as:}, as a plain Map so
     * {@code ${created.body.id}} resolves — Interpolator.lookup walks Maps.
     */
    private static Map<String, Object> captureOf(RestResponse response) {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("status", response.status());
        capture.put("headers", response.headers());
        capture.put("body", response.body());
        return capture;
    }
}
