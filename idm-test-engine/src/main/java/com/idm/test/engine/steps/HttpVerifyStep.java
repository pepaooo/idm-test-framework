package com.idm.test.engine.steps;

import com.idm.test.engine.spi.JsonMappers;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.client.TokenFailureText;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.DeferredExpression;
import com.idm.test.engine.spi.Explained;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import tools.jackson.databind.json.JsonMapper;

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
 * something other than true or false is an authoring mistake and ends the wait at once.
 * <p>
 * {@code response.json} and {@code response.xml} are the body parsed for a JSON or XML media
 * type, else null. When a failing check reads {@code response.xml} while it is null, the
 * message says why (the Content-Type, an empty body, or where the XML is malformed).
 */
public final class HttpVerifyStep implements StepType {

    private static final JsonMapper JSON = JsonMappers.builder().build();

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
        HttpExchange.Sent sent = HttpExchange.send(connector, request);
        return judge(checks, request, sent.result(), captureRequested).withEvidence(sent.evidence());
    }

    /** Evaluates the checks against the response; every outcome is about the exchange just made. */
    private static StepOutcome judge(List<DeferredExpression> checks, HttpExchange.Prepared request,
                                     OperationResult<TextResponse> result, boolean captureRequested) {
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("http.verify failed for " + request.url() + ": " + result.getMessage());
        }

        TextResponse response = result.getData().get();
        Bound bound = responseOf(response);
        String where = "http.verify " + request.method() + " " + request.url();
        int total = checks.size();
        for (int i = 0; i < total; i++) {
            DeferredExpression check = checks.get(i);
            String label = where + ": check " + (i + 1) + " of " + total;
            String hint = bound.xmlNullReason() != null && check.source().contains("response.xml")
                    ? " (response.xml is null: " + TokenFailureText.safe(bound.xmlNullReason(), null) + ")"
                    : "";
            Explained explained;
            try {
                explained = check.explain(Map.of("response", bound.response()));
            } catch (RuntimeException e) {
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return StepOutcome.failure(label + " failed: " + TokenFailureText.safe(error, null) + context(response) + hint);
            }
            Object value = explained.value();
            if (!(value instanceof Boolean passed)) {
                return StepOutcome.finalFailure(
                        label + " must return true or false, got " + TokenFailureText.safe(describe(value), null));
            }
            if (!passed) {
                String notes = explained.notes().isEmpty()
                        ? ""
                        : " — " + TokenFailureText.safe(String.join("; ", explained.notes()), null);
                return StepOutcome.failure(label + " is false: " + check.source() + notes + context(response) + hint);
            }
        }

        String message = request.method() + " " + request.url() + " passed " + total
                + " check(s) (status " + response.status() + ")";
        return captureRequested ? StepOutcome.success(message, bound.response()) : StepOutcome.success(message);
    }

    /** The response map the checks read, and why its xml is null (null when xml was parsed). */
    private record Bound(Map<String, Object> response, String xmlNullReason) {
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

    /** status, headers and body as http.request captures them, plus the parsed JSON and XML bodies. */
    private static Bound responseOf(TextResponse response) {
        Map<String, Object> bound = new LinkedHashMap<>(HttpExchange.capture(response));
        String mediaType = mediaTypeOf(response);
        bound.put("json", jsonOf(mediaType, response.body()));
        Object xml = null;
        String reason;
        if (response.body().isBlank()) {
            reason = "the body is empty";
        } else if (mediaType.isEmpty()) {
            reason = "no Content-Type";
        } else if (!(mediaType.equals("text/xml") || mediaType.equals("application/xml") || mediaType.endsWith("+xml"))) {
            reason = "Content-Type is " + mediaType;
        } else {
            try {
                xml = XmlObjects.parse(response.body());
                reason = null;
            } catch (IllegalArgumentException malformed) {
                reason = "the body is " + malformed.getMessage();
            }
        }
        bound.put("xml", xml);
        return new Bound(bound, reason);
    }

    /** The first Content-Type value before any parameters, lower-cased; "" when absent. */
    private static String mediaTypeOf(TextResponse response) {
        List<String> types = response.headers().getOrDefault("content-type", List.of());
        return types.isEmpty() ? "" : types.get(0).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static Object jsonOf(String mediaType, String body) {
        if (!(mediaType.equals("application/json") || mediaType.endsWith("+json")) || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(body, Object.class);
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
