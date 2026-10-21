package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.ValueComparison;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.RestResponse;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.Check;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final RestAuthenticator authenticator;

    public RestVerifyStep(IdmRestConnector connector, RestProperties properties, RestAuthenticator authenticator) {
        this.connector = connector;
        this.properties = properties;
        this.authenticator = authenticator;
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

        RestTargets.Resolution resolution = RestTargets.resolveBaseUrl("rest.verify", target, properties);
        if (resolution.isFailure()) {
            return StepOutcome.finalFailure(resolution.error());
        }

        String url = resolution.baseUrl() + String.valueOf(params.get("path"));
        Object rawMethod = params.get("method");
        String method = rawMethod == null ? "GET" : String.valueOf(rawMethod);

        Map<String, String> headers = new LinkedHashMap<>();
        StepValues.toMap(params.get("headers"))
                .forEach((key, value) -> headers.put(key, String.valueOf(value)));

        StepOutcome authFailure = RestAuthorization.apply("rest.verify", target, properties, authenticator, headers);
        if (authFailure != null) {
            return authFailure;
        }

        RestExchange.Sent sent = RestExchange.send(connector, method, url, null, headers, resolution.timeouts());
        return judge(sent.result(), expect, method, url, captureRequested).withEvidence(sent.evidence());
    }

    private static StepOutcome judge(OperationResult<RestResponse> result, Map<String, Object> expect, String method,
                                     String url, boolean captureRequested) {
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(Message.of("step.rest.verify.failed",
                    url, ConnectorProblems.request(method, url, result)),
                    result.getErrorType().orElse(ErrorType.UNKNOWN_ERROR));
        }

        RestResponse response = result.getData().get();

        // Every expectation is compared, so a finding shows each one; the message names the first
        // that differs, the status before the body.
        List<Check> checks = new ArrayList<>();
        Message failure = null;

        if (expect.containsKey(STATUS)) {
            Object expectedStatus = expect.get(STATUS);
            boolean matched = ValueComparison.equal(expectedStatus, response.status());
            checks.add(Check.compared(STATUS, expectedStatus, response.status(), matched));
            if (!matched) {
                failure = Message.of("step.rest.verify.statusMismatch", url, expectedStatus, response.status());
            }
        }

        if (expect.containsKey(BODY)) {
            Message mismatch = compareIn(StepValues.toMap(expect.get(BODY)), response.body(), "", checks);
            if (mismatch != null && failure == null) {
                failure = Message.of("step.rest.verify.bodyMismatch", url, mismatch);
            }
        }

        if (failure != null) {
            return StepOutcome.failure(failure).withChecks(checks);
        }

        Message message = Message.of("step.rest.verify.verified", method, url, response.status());
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("status", response.status());
        capture.put("headers", response.headers());
        capture.put("body", response.body());

        return (captureRequested
                ? StepOutcome.success(message, capture)
                : StepOutcome.success(message)).withChecks(checks);
    }

    /**
     * Compares only the fields the scenario named, recursing into nested maps, so a scenario
     * need not restate a whole payload to assert one field. Every named field is compared and
     * added to {@code checks} by its dotted path; a field under a part of the body that is not an
     * object was not found.
     *
     * @return a description of the first mismatch, or null when everything named matches
     */
    @SuppressWarnings("unchecked")
    private static Message compareIn(Map<String, Object> expected, Object actual, String path, List<Check> checks) {
        Message first = null;
        Map<String, Object> actualMap = Map.of();
        if (actual instanceof Map<?, ?> map) {
            actualMap = (Map<String, Object>) map;
        } else {
            first = Message.of("step.rest.verify.notObject", path.isEmpty() ? "body" : path, actual);
        }

        for (Map.Entry<String, Object> field : expected.entrySet()) {
            String where = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
            Object actualValue = actualMap.get(field.getKey());

            if (field.getValue() instanceof Map<?, ?> nested) {
                Message deeper = compareIn((Map<String, Object>) nested, actualValue, where, checks);
                if (first == null) {
                    first = deeper;
                }
                continue;
            }

            // Shared with db.verify so a JSON number and a YAML number agree, and so the same
            // authoring gesture (expect: 1 against an actual 1.0) does not pass in one
            // assertion step and fail in the other — falls back to comparing string form, so
            // 200 still matches "200".
            boolean matched = actual instanceof Map<?, ?> && ValueComparison.equal(field.getValue(), actualValue);
            checks.add(Check.compared(where, field.getValue(), actualValue, matched));
            if (!matched && first == null) {
                first = Message.of("step.rest.verify.fieldMismatch", where, field.getValue(), actualValue);
            }
        }
        return first;
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
            return StepOutcome.finalFailure(Message.of("step.rest.verify.expectNotMap", target));
        }

        Map<String, Object> expect = (Map<String, Object>) rawExpect;
        if (expect == null || expect.isEmpty()) {
            return StepOutcome.finalFailure(Message.of("step.rest.verify.expectEmpty", target));
        }

        Set<String> unknown = new TreeSet<>(expect.keySet());
        unknown.removeAll(EXPECT_KEYS);
        if (!unknown.isEmpty()) {
            return StepOutcome.finalFailure(Message.of("step.rest.verify.unknownKeys",
                    target, unknown, new TreeSet<>(EXPECT_KEYS)));
        }

        // Positive rule: if BODY is present at all, it must be a Map with at least one
        // entry. This is one condition rather than a "not a map" check plus an "empty map"
        // check so that a present-but-null value (expect: { body: null }) cannot slip past
        // both — containsKey agrees with execute() about what "present" means.
        if (expect.containsKey(BODY)
                && !(expect.get(BODY) instanceof Map<?, ?> bodyMap && !bodyMap.isEmpty())) {
            return StepOutcome.finalFailure(Message.of("step.rest.verify.bodyShape", target));
        }

        if (expect.containsKey(BODY)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> bodyMap = (Map<String, Object>) expect.get(BODY);
            Message vacuous = rejectVacuousField(bodyMap, "");
            if (vacuous != null) {
                return StepOutcome.finalFailure(Message.of("step.rest.verify.vacuous", target, vacuous));
            }
        }

        return null;
    }

    /**
     * The same hole as the top-level {@code body} check, one level in, and at every level
     * below that: a field whose expected value is null (a misspelled key parses the same way,
     * since a missing key and an explicit null are indistinguishable after {@code Map.get()}),
     * or a nested object with nothing in it (a scenario writing {@code profile: {}} asserts
     * only "profile is an object", the exact vacuous shape rejected at the top level), would
     * let {@link #compareIn} report a match having compared nothing for that field. Recurses
     * so the rule holds no matter how deep the {@code body} matcher nests.
     *
     * @return a description of the first offending field, or null when the matcher is sound
     */
    private static Message rejectVacuousField(Map<String, Object> expected, String path) {
        for (Map.Entry<String, Object> field : expected.entrySet()) {
            String where = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
            Object value = field.getValue();

            if (value == null) {
                return Message.of("step.rest.verify.nullExpected", where);
            }

            if (value instanceof Map<?, ?> nested) {
                if (nested.isEmpty()) {
                    return Message.of("step.rest.verify.emptyObject", where);
                }
                @SuppressWarnings("unchecked")
                Message deeper = rejectVacuousField((Map<String, Object>) nested, where);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }
}
