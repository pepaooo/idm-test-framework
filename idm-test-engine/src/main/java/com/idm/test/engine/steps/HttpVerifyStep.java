package com.idm.test.engine.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.client.TokenFailureText;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.DeferredExpression;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sends a raw-text request like http.request, then evaluates each js:{} condition in
 * {@code check} against {@code response}. All must return true. Retryable: under wait:, the
 * request and the checks repeat until they pass or the timeout expires. A check that throws is
 * retried (for example before provisioning, response.json can be null); a check that returns
 * something other than true or false is an authoring mistake and ends the wait at once. A
 * failing check (false or throwing) whose source reads {@code response.xml} also ends the wait
 * at once, since {@code response.xml} cannot become available before ITF-25 no matter how long
 * the wait polls.
 */
public final class HttpVerifyStep implements StepType {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String XML_HINT =
            " (response.xml is not available yet: it arrives with ITF-25; check response.body for now)";

    private final IdmRestConnector connector;
    private final RestProperties properties;
    private final RestAuthenticator authenticator;

    public HttpVerifyStep(IdmRestConnector connector, RestProperties properties, RestAuthenticator authenticator) {
        this.connector = connector;
        this.properties = properties;
        this.authenticator = authenticator;
    }

    @Override
    public String name() {
        return "http.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("target", "method", "path", "check");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("target", "method", "path", "check", "headers", "body", "wait");
    }

    @Override
    public Set<String> deferredParams() {
        return Set.of("check");
    }

    @Override
    public Set<String> deferredBindings() {
        return Set.of("response");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        List<DeferredExpression> checks = checksOf(params.get("check"));
        if (checks == null || checks.isEmpty()) {
            return StepOutcome.finalFailure("http.verify 'check' must be js:{ … } expressions");
        }

        HttpExchange.Prepared request = HttpExchange.prepare("http.verify", params, properties, authenticator);
        if (request.isFailure()) {
            return request.failure();
        }
        OperationResult<TextResponse> result = HttpExchange.send(connector, request);
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("http.verify failed for " + request.url() + ": " + result.getMessage());
        }

        TextResponse response = result.getData().get();
        Map<String, Object> bound = responseOf(response);
        String where = "http.verify " + request.method() + " " + request.url();
        int total = checks.size();
        for (int i = 0; i < total; i++) {
            DeferredExpression check = checks.get(i);
            String label = where + ": check " + (i + 1) + " of " + total;
            String hint = check.source().contains("response.xml") ? XML_HINT : "";
            Object value;
            try {
                value = check.evaluate(Map.of("response", bound));
            } catch (RuntimeException e) {
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                String message = label + " failed: " + TokenFailureText.safe(error, null) + context(response) + hint;
                return hint.isEmpty() ? StepOutcome.failure(message) : StepOutcome.finalFailure(message);
            }
            if (!(value instanceof Boolean passed)) {
                return StepOutcome.finalFailure(
                        label + " must return true or false, got " + TokenFailureText.safe(describe(value), null) + hint);
            }
            if (!passed) {
                String message = label + " is false: " + check.source() + context(response) + hint;
                return hint.isEmpty() ? StepOutcome.failure(message) : StepOutcome.finalFailure(message);
            }
        }

        String message = request.method() + " " + request.url() + " passed " + total
                + " check(s) (status " + response.status() + ")";
        return captureRequested ? StepOutcome.success(message, bound) : StepOutcome.success(message);
    }

    private static List<DeferredExpression> checksOf(Object raw) {
        List<?> items = raw instanceof List<?> list ? list : raw == null ? List.of() : List.of(raw);
        List<DeferredExpression> checks = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof DeferredExpression expression)) {
                return null;
            }
            checks.add(expression);
        }
        return checks;
    }

    /** status, headers and body as http.request captures them, plus the parsed JSON body. */
    private static Map<String, Object> responseOf(TextResponse response) {
        Map<String, Object> bound = new LinkedHashMap<>(HttpExchange.capture(response));
        bound.put("json", jsonOf(response));
        return bound;
    }

    private static Object jsonOf(TextResponse response) {
        List<String> types = response.headers().getOrDefault("content-type", List.of());
        String mediaType = types.isEmpty() ? "" : types.get(0).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!(mediaType.equals("application/json") || mediaType.endsWith("+json")) || response.body().isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(response.body(), Object.class);
        } catch (Exception notJson) {
            return null; // declared JSON but isn't: the check sees null, as IdmRestConnector does
        }
    }

    private static String context(TextResponse response) {
        return " (status " + response.status() + ", body: " + TokenFailureText.safe(response.body(), null) + ")";
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text + "\"";
        }
        return String.valueOf(value);
    }
}
