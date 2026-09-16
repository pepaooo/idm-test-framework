package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.RestResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Asserts that an HTTP call returns the expected status and body.
 * <p>
 * Retryable: provisioning is asynchronous, so the engine may call this repeatedly under a
 * {@code wait} until it passes or the timeout expires.
 * <p>
 * {@code status} and {@code body} are reserved keys inside {@code expect}, never merged with
 * the payload, so a response with its own field named {@code status} cannot change how a
 * scenario parses (spec §7.3).
 */
public final class RestVerifyStep implements StepType {

    private static final String STATUS = "status";
    private static final String BODY = "body";
    private static final Set<String> EXPECT_KEYS = Set.of(STATUS, BODY);

    private final IdmRestConnector connector;
    private final RestProperties properties;

    public RestVerifyStep(IdmRestConnector connector, RestProperties properties) {
        this.connector = connector;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "rest.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("target", "path", "expect");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("target", "method", "path", "headers", "expect", "wait");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String target = String.valueOf(params.get("target"));
        Object rawExpect = params.get("expect");

        StepOutcome rejection = rejectMalformedExpect(rawExpect, target);
        if (rejection != null) {
            return rejection;
        }
        Map<String, Object> expect = (Map<String, Object>) rawExpect;

        RestProperties.RestApiConfig config = properties.getRest().get(target);
        if (config == null) {
            return StepOutcome.failure("rest.verify names unknown target '" + target
                    + "'. Configured targets: " + new TreeSet<>(properties.getRest().keySet()));
        }

        String url = config.getBaseUrl() + String.valueOf(params.get("path"));
        Object rawMethod = params.get("method");
        String method = rawMethod == null ? "GET" : String.valueOf(rawMethod);

        Map<String, String> headers = new LinkedHashMap<>();
        StepValues.toMap(params.get("headers"))
                .forEach((key, value) -> headers.put(key, String.valueOf(value)));

        OperationResult<RestResponse> result =
                connector.exchange(method, url, null, headers);

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "rest.verify failed for " + url + ": " + result.getMessage());
        }

        RestResponse response = result.getData().get();

        if (expect.containsKey(STATUS)) {
            Object expectedStatus = expect.get(STATUS);
            if (!String.valueOf(expectedStatus).equals(String.valueOf(response.status()))) {
                return StepOutcome.failure("rest.verify on " + url + " expected status "
                        + expectedStatus + " but got " + response.status());
            }
        }

        if (expect.containsKey(BODY)) {
            String mismatch = mismatchIn(
                    StepValues.toMap(expect.get(BODY)), response.body(), "");
            if (mismatch != null) {
                return StepOutcome.failure("rest.verify on " + url + " " + mismatch);
            }
        }

        String message = method + " " + url + " verified, status " + response.status();
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("status", response.status());
        capture.put("headers", response.headers());
        capture.put("body", response.body());

        return captureRequested
                ? StepOutcome.success(message, null, capture)
                : StepOutcome.success(message);
    }

    /**
     * Compares only the fields the scenario named, recursing into nested maps, so a scenario
     * need not restate a whole payload to assert one field.
     *
     * @return a description of the first mismatch, or null when everything named matches
     */
    @SuppressWarnings("unchecked")
    private static String mismatchIn(Map<String, Object> expected, Object actual, String path) {
        if (!(actual instanceof Map<?, ?> actualMap)) {
            return "expected an object at '" + (path.isEmpty() ? "body" : path)
                    + "' but the response held: " + actual;
        }

        for (Map.Entry<String, Object> field : expected.entrySet()) {
            String where = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
            Object actualValue = ((Map<String, Object>) actualMap).get(field.getKey());

            if (field.getValue() instanceof Map<?, ?> nested) {
                String deeper = mismatchIn((Map<String, Object>) nested, actualValue, where);
                if (deeper != null) {
                    return deeper;
                }
                continue;
            }

            // Compared as text so a JSON number and a YAML number agree without either side
            // having to know which boxed type Jackson chose.
            if (!String.valueOf(field.getValue()).equals(String.valueOf(actualValue))) {
                return "found a mismatch for '" + where + "': expected="
                        + field.getValue() + ", actual=" + actualValue;
            }
        }
        return null;
    }

    /**
     * An {@code expect} that asserts nothing, whose keys are typos, or whose {@code body} is
     * not a map, would pass having compared nothing at all — the vacuous-assertion trap, now
     * caught four times in this project. Checked before the request is made, since none of
     * these problems depend on the response.
     * <p>
     * Takes the raw, unvalidated value from {@code params.get("expect")} rather than an
     * already-cast {@code Map}: a scenario can write {@code expect} as a scalar, and
     * {@code StepType.execute} must never throw, so the type check has to happen here, before
     * any caller casts it.
     */
    @SuppressWarnings("unchecked")
    private static StepOutcome rejectMalformedExpect(Object rawExpect, String target) {
        if (rawExpect != null && !(rawExpect instanceof Map)) {
            return StepOutcome.failure("rest.verify on '" + target
                    + "' has an expect that is not a map of assertions (" + STATUS + ", " + BODY
                    + ")");
        }

        Map<String, Object> expect = (Map<String, Object>) rawExpect;
        if (expect == null || expect.isEmpty()) {
            return StepOutcome.failure("rest.verify on '" + target
                    + "' has an empty expect: block. Assert " + STATUS + ", " + BODY
                    + ", or both — an empty expect would verify nothing.");
        }

        Set<String> unknown = new TreeSet<>(expect.keySet());
        unknown.removeAll(EXPECT_KEYS);
        if (!unknown.isEmpty()) {
            return StepOutcome.failure("rest.verify on '" + target
                    + "' has unknown key(s) in expect: " + unknown + ". expect accepts: "
                    + new TreeSet<>(EXPECT_KEYS));
        }

        // Positive rule: if BODY is present at all, it must be a Map with at least one
        // entry. This is one condition rather than a "not a map" check plus an "empty map"
        // check so that a present-but-null value (expect: { body: null }) cannot slip past
        // both — containsKey agrees with execute() about what "present" means.
        if (expect.containsKey(BODY)
                && !(expect.get(BODY) instanceof Map<?, ?> bodyMap && !bodyMap.isEmpty())) {
            return StepOutcome.failure("rest.verify on '" + target + "' has a " + BODY
                    + " that must be a map of field names to expected values with at least "
                    + "one entry — a missing, null, non-map, or empty " + BODY
                    + " would verify nothing");
        }

        return null;
    }
}
