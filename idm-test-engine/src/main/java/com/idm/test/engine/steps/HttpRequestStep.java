package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;

/**
 * Makes one HTTP request with a raw string body and captures the raw string response.
 * <p>
 * The generic counterpart of rest.request: nothing is serialized or parsed, so the same step
 * sends a JSON document, an XML payload or a SOAP envelope — a SOAP call is only a POST with an
 * envelope and a SOAPAction header. The capture is {status, headers, body} with lower-case
 * header names and the body as text. Any HTTP status is an outcome, never a failure; only a bad
 * parameter or a transport problem fails the step. Like rest.*, it addresses a named target, so
 * base URLs stay in per-environment configuration.
 */
public final class HttpRequestStep implements StepType {

    private final IdmRestConnector connector;
    private final RestProperties properties;
    private final RestAuthenticator authenticator;

    public HttpRequestStep(IdmRestConnector connector, RestProperties properties, RestAuthenticator authenticator) {
        this.connector = connector;
        this.properties = properties;
        this.authenticator = authenticator;
    }

    @Override
    public String name() {
        return "http.request";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("target", "method", "path");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("target", "method", "path", "headers", "body");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        HttpExchange.Prepared request = HttpExchange.prepare("http.request", params, properties, authenticator);
        if (request.isFailure()) {
            return request.failure();
        }
        HttpExchange.Sent sent = HttpExchange.send(connector, request);
        OperationResult<TextResponse> result = sent.result();
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("http.request failed for " + request.url() + ": " + result.getMessage())
                    .withEvidence(sent.evidence());
        }
        TextResponse response = result.getData().get();
        return StepOutcome.success(request.method() + " " + request.url() + " returned " + response.status(),
                HttpExchange.capture(response)).withEvidence(sent.evidence());
    }
}
