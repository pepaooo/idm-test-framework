package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.client.Timeouts;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.StepOutcome;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds and sends the raw-text request shared by http.request and http.verify, so both apply
 * the same method list, header and body rules, target resolution and authentication. Problems
 * with the step's own parameters or configuration are final failures: waiting cannot fix them.
 */
final class HttpExchange {

    static final Set<String> METHODS = IdmRestConnector.SUPPORTED_METHODS;

    /** A request ready to send, or the failure that stops it. */
    record Prepared(String method, String url, String body, Map<String, String> headers,
                    Timeouts timeouts, StepOutcome failure) {

        static Prepared failed(StepOutcome failure) {
            return new Prepared(null, null, null, null, null, failure);
        }

        boolean isFailure() {
            return failure != null;
        }
    }

    private HttpExchange() {
    }

    static Prepared prepare(String stepName, Map<String, Object> params, RestProperties properties,
                            RestAuthenticator authenticator) {
        String method = String.valueOf(params.get("method")).toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            return Prepared.failed(StepOutcome.finalFailure(stepName + " method '" + params.get("method")
                    + "' is not one of " + new TreeSet<>(METHODS)));
        }

        Object rawHeaders = params.get("headers");
        if (rawHeaders != null && !(rawHeaders instanceof Map)) {
            return Prepared.failed(StepOutcome.finalFailure(stepName + " headers must be a map of header name to value"));
        }

        Object rawBody = params.get("body");
        if (rawBody != null && !(rawBody instanceof String)) {
            return Prepared.failed(StepOutcome.finalFailure(
                    stepName + " body must be a string sent as-is; to send a JSON object use rest.request"));
        }

        String target = String.valueOf(params.get("target"));
        RestTargets.Resolution resolution = RestTargets.resolveBaseUrl(stepName, target, properties);
        if (resolution.isFailure()) {
            return Prepared.failed(StepOutcome.finalFailure(resolution.error()));
        }
        String url = resolution.baseUrl() + String.valueOf(params.get("path"));

        Map<String, String> headers = new LinkedHashMap<>();
        if (rawHeaders instanceof Map<?, ?> map) {
            map.forEach((key, value) -> headers.put(String.valueOf(key), String.valueOf(value)));
        }

        StepOutcome authFailure = RestAuthorization.apply(stepName, target, properties, authenticator, headers);
        if (authFailure != null) {
            return Prepared.failed(authFailure);
        }
        return new Prepared(method, url, (String) rawBody, headers, resolution.timeouts(), null);
    }

    /** A request that went out: the connector's result and the evidence of the exchange. */
    record Sent(OperationResult<TextResponse> result, HttpEvidence evidence) {
    }

    static Sent send(IdmRestConnector connector, Prepared request) {
        long started = System.currentTimeMillis();
        OperationResult<TextResponse> result =
                connector.exchangeText(request.method(), request.url(), request.body(), request.headers(), request.timeouts());
        long millis = System.currentTimeMillis() - started;
        TextResponse response = result.isSuccess() ? result.getData().orElse(null) : null;
        HttpEvidence evidence = new HttpEvidence(request.method(), request.url(),
                HttpEvidence.headersOf(request.headers()), request.body(),
                response == null ? null : response.status(),
                response == null ? null : response.headers(),
                response == null ? null : response.body(),
                millis, response == null ? result.getMessage() : null);
        return new Sent(result, evidence);
    }

    /** The {status, headers, body} view both steps expose. */
    static Map<String, Object> capture(TextResponse response) {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("status", response.status());
        capture.put("headers", response.headers());
        capture.put("body", response.body());
        return capture;
    }
}
