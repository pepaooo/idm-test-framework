package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

    static final Set<String> METHODS = IdmRestConnector.SUPPORTED_METHODS;

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
        String method = String.valueOf(params.get("method")).toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            return StepOutcome.failure("http.request method '" + params.get("method")
                    + "' is not one of " + new TreeSet<>(METHODS));
        }

        Object rawHeaders = params.get("headers");
        if (rawHeaders != null && !(rawHeaders instanceof Map)) {
            return StepOutcome.failure("http.request headers must be a map of header name to value");
        }

        Object rawBody = params.get("body");
        if (rawBody != null && !(rawBody instanceof String)) {
            return StepOutcome.failure(
                    "http.request body must be a string sent as-is; to send a JSON object use rest.request");
        }

        String target = String.valueOf(params.get("target"));
        RestTargets.Resolution resolution = RestTargets.resolveBaseUrl("http.request", target, properties);
        if (resolution.isFailure()) {
            return StepOutcome.failure(resolution.error());
        }
        String url = resolution.baseUrl() + String.valueOf(params.get("path"));

        Map<String, String> headers = new LinkedHashMap<>();
        if (rawHeaders instanceof Map<?, ?> map) {
            map.forEach((key, value) -> headers.put(String.valueOf(key), String.valueOf(value)));
        }

        StepOutcome authFailure = RestAuthorization.apply("http.request", target, properties, authenticator, headers);
        if (authFailure != null) {
            return authFailure;
        }

        OperationResult<TextResponse> result = connector.exchangeText(method, url, (String) rawBody, headers);
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("http.request failed for " + url + ": " + result.getMessage());
        }

        TextResponse response = result.getData().get();
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("status", response.status());
        capture.put("headers", response.headers());
        capture.put("body", response.body());

        return StepOutcome.success(method + " " + url + " returned " + response.status(), capture);
    }
}
