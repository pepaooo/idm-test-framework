package com.idm.test.engine.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestClientConfiguration;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.engine.spi.StepOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the REST step types against WireMock.
 * <p>
 * Builds the connector directly rather than through Spring, because this module has no Spring
 * context by design.
 */
@DisplayName("REST step types")
class RestStepsTest {

    private static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
        WireMock.configureFor("localhost", WIREMOCK.port());
    }

    private static final IdmRestConnector CONNECTOR =
            new IdmRestConnector(RestClient.builder()
                    .requestFactory(RestClientConfiguration.requestFactory()).build());

    private static final RestProperties PROPERTIES = properties();

    private static RestProperties properties() {
        RestProperties properties = new RestProperties();
        RestProperties.RestApiConfig config = new RestProperties.RestApiConfig();
        config.setBaseUrl("http://localhost:" + WIREMOCK.port());
        properties.getRest().put("idm", config);
        return properties;
    }

    @AfterAll
    static void stopWireMock() {
        if (WIREMOCK.isRunning()) {
            WIREMOCK.stop();
        }
    }

    @BeforeEach
    void resetStubs() {
        WIREMOCK.resetAll();
    }

    @Test
    @DisplayName("rest.request should capture status, headers and body")
    @SuppressWarnings("unchecked")
    void testRequestCapturesFullResponse() {
        stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":1,\"userId\":\"jdoe\"}")));

        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/1"), true);

        assertThat(outcome.success()).isTrue();
        Map<String, Object> captured = (Map<String, Object>) outcome.value();
        assertThat(captured.get("status")).isEqualTo(200);
        // containsKey alone would still pass if every header value were dropped; assert a
        // value actually survived, not just the key.
        Map<String, List<String>> headers = (Map<String, List<String>>) captured.get("headers");
        assertThat(headers).containsEntry("content-type", List.of("application/json"));
        Map<String, Object> body = (Map<String, Object>) captured.get("body");
        assertThat(body).containsEntry("userId", "jdoe");
    }

    @Test
    @DisplayName("rest.request should send bodyFrom as a JSON object")
    void testRequestSendsBodyFrom() {
        stubFor(post(urlEqualTo("/users")).willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":7}")));

        // Task 2 makes "${user}" resolve to the map itself; before that it arrived as
        // Java's Map.toString and could never have been sent as JSON.
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "method", "POST", "path", "/users",
                "bodyFrom", Map.of("userId", "jdoe", "department", "Engineering")), false);

        assertThat(outcome.success()).isTrue();
        verify(postRequestedFor(urlEqualTo("/users"))
                .withRequestBody(matchingJsonPath("$.userId", equalTo("jdoe"))));
    }

    @Test
    @DisplayName("rest.request should succeed and capture the status for a 404")
    @SuppressWarnings("unchecked")
    void testRequestCapturesErrorStatus() {
        stubFor(get(urlEqualTo("/users/missing"))
                .willReturn(aResponse().withStatus(404)));

        // The request was made and answered; 404 is the answer. Only a transport failure is
        // a step failure.
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/missing"), true);

        assertThat(outcome.success()).isTrue();
        Map<String, Object> captured = (Map<String, Object>) outcome.value();
        assertThat(captured.get("status")).isEqualTo(404);
    }

    @Test
    @DisplayName("rest.request should fail for an unknown target")
    void testRequestUnknownTargetFails() {
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "nope", "method", "GET", "path", "/users/1"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("nope");
    }

    @Test
    @DisplayName("rest.request should fail with a distinct message for a target with no "
            + "base-url, rather than the self-contradictory 'unknown target' message")
    void testRequestTargetMissingBaseUrlFails() {
        RestProperties noBaseUrl = new RestProperties();
        noBaseUrl.getRest().put("idm", new RestProperties.RestApiConfig());

        StepOutcome outcome = new RestRequestStep(CONNECTOR, noBaseUrl, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/1"), false);

        assertThat(outcome.success()).isFalse();
        // Must name the target as configured (not "unknown") and say base-url specifically —
        // the old message said "names unknown target 'idm'. Configured targets: [idm]",
        // self-contradictory since 'idm' appears in both halves.
        assertThat(outcome.message()).contains("idm").contains("base-url");
        assertThat(outcome.message()).doesNotContain("unknown target");
    }

    @Test
    @DisplayName("rest.request should reject body and bodyFrom together")
    void testRequestRejectsBothBodyForms() {
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "method", "POST", "path", "/users",
                "body", Map.of("a", 1),
                "bodyFrom", Map.of("b", 2)), false);

        // They mean the same thing, so supplying both says nothing about which was intended.
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("body").contains("bodyFrom");
    }

    @Test
    @DisplayName("rest.request should send custom headers")
    void testRequestSendsHeaders() {
        stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse().withStatus(200)));

        new RestRequestStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/1",
                "headers", Map.of("X-Correlation-Id", "corr-9")), false);

        verify(getRequestedFor(urlEqualTo("/users/1"))
                .withHeader("X-Correlation-Id", equalTo("corr-9")));
    }

    @Test
    @DisplayName("rest.request should fail rather than throw on a transport error")
    void testRequestTransportFailure() {
        RestProperties dead = new RestProperties();
        RestProperties.RestApiConfig config = new RestProperties.RestApiConfig();
        config.setBaseUrl("http://localhost:1");
        dead.getRest().put("idm", config);

        StepOutcome outcome = new RestRequestStep(CONNECTOR, dead, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/1"), false);

        assertThat(outcome.success()).isFalse();
    }

    @Test
    @DisplayName("rest.verify should pass when status and body both match")
    void testVerifyMatches() {
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"userId\":\"jdoe\",\"department\":\"Engineering\"}")));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("status", 200,
                        "body", Map.of("department", "Engineering"))), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("rest.verify should match a subset of the body")
    void testVerifyMatchesBodySubset() {
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"userId\":\"jdoe\",\"department\":\"Engineering\",\"extra\":1}")));

        // A scenario should not have to restate a whole payload to assert one field.
        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("body", Map.of("userId", "jdoe"))), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("rest.verify should assert a 404 as an expected status")
    void testVerifyExpectsNotFound() {
        stubFor(get(urlEqualTo("/users/gone")).willReturn(aResponse().withStatus(404)));

        // The pattern this whole task exists for: after a deprovision, the account is gone.
        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/gone",
                "expect", Map.of("status", 404)), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("rest.verify should fail on a status mismatch and name both codes")
    void testVerifyStatusMismatchFails() {
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse().withStatus(500)));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("status", 200)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("200").contains("500");
    }

    @Test
    @DisplayName("rest.verify should fail on a body mismatch and name the field")
    void testVerifyBodyMismatchFails() {
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"department\":\"Sales\"}")));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("body", Map.of("department", "Engineering"))), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("department")
                .contains("Engineering").contains("Sales");
    }

    @Test
    @DisplayName("rest.verify should reject an expect block that asserts nothing")
    void testVerifyRejectsEmptyExpect() {
        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of()), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("status").contains("body");
    }

    @Test
    @DisplayName("rest.verify should reject an unknown key inside expect")
    void testVerifyRejectsUnknownExpectKey() {
        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("staus", 200)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("staus");
    }

    @Test
    @DisplayName("rest.verify should reject a body that is not a map")
    void testVerifyRejectsNonMapBody() {
        // A scenario author's plausible slip: `body: Engineering` instead of the nested
        // `body: { department: Engineering }`. StepValues.toMap silently turns a non-map into
        // an empty map, so without this guard the comparison would run against zero fields
        // and rest.verify would pass no matter what the response actually held.
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"department\":\"Sales\"}")));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("body", "Engineering")), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("body");
    }

    @Test
    @DisplayName("rest.verify should reject an empty body map that would verify nothing")
    void testVerifyRejectsEmptyBody() {
        // expect: { body: {} } passes the "expect is non-empty" guard (it has a body key) and
        // the "body is a Map" guard (it is, just an empty one) — the subset comparison then
        // iterates zero fields and rest.verify would pass against any response whatsoever.
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"department\":\"Sales\"}")));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("body", Map.of())), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("body");
    }

    @Test
    @DisplayName("rest.verify should reject a present-but-null body that would verify nothing")
    void testVerifyRejectsNullBody() {
        // A bare `body:` key with no value parses as { body: null } — present per containsKey,
        // but every earlier guard here started with "expect.get(BODY) != null", so a null value
        // skipped them all. StepValues.toMap(null) then silently returns an empty map and the
        // subset comparison iterates zero fields: rest.verify would report SUCCESS having
        // compared nothing. Map.of cannot hold a null value, which is exactly why this case
        // slipped past every earlier round of tests — a HashMap is required to express it.
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"department\":\"Sales\"}")));

        Map<String, Object> expect = new HashMap<>();
        expect.put("body", null);

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", expect), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("body");
    }

    @Test
    @DisplayName("rest.verify should reject a null expected value for a body field "
            + "(a misspelled field name)")
    void testVerifyRejectsNullBodyField() {
        // ITF-9: a misspelled field, e.g. `staus:` instead of `status:` inside body, is a YAML
        // bare key whose value parses as null — indistinguishable from an explicit null after
        // Map.get(). Before this guard, mismatchIn compared field.getValue()=null against
        // whatever the response held via string form, and "null".equals("null") could pass;
        // now it must be rejected before the request is made. Map.of cannot hold a null value,
        // which is why a HashMap is required to express this case.
        Map<String, Object> body = new HashMap<>();
        body.put("staus", null);

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("body", body)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("staus");
    }

    @Test
    @DisplayName("rest.verify should reject an empty nested object, which would only assert "
            + "'this field is an object'")
    void testVerifyRejectsEmptyNestedBodyObject() {
        // body: { profile: {} } passes the top-level "body is a non-empty map" guard (it has
        // a profile key) but asserts only that profile is an object — the exact vacuous shape
        // already rejected at the top level, one level further in. mismatchIn recursed into
        // this with no emptiness check, so the subset comparison would iterate zero fields and
        // pass against any object at all.
        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("body", Map.of("profile", Map.of()))), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("profile");
    }

    @Test
    @DisplayName("rest.verify should fail with a distinct message for a target with no "
            + "base-url, rather than building a URL out of 'null'")
    void testVerifyTargetMissingBaseUrlFails() {
        RestProperties noBaseUrl = new RestProperties();
        noBaseUrl.getRest().put("idm", new RestProperties.RestApiConfig());

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, noBaseUrl, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/1",
                "expect", Map.of("status", 200)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("idm").contains("base-url");
        assertThat(outcome.message()).doesNotContain("unknown target");
    }

    @Test
    @DisplayName("rest.verify should match a numeric body field across JSON and YAML types, "
            + "the same tolerance db.verify already has")
    void testVerifyMatchesNumericBodyAcrossTypes() {
        // ITF-9: a response of {"score": 1.0} against expect: { body: { score: 1 } } used to
        // FAIL here (compared as text: "1.0" != "1") while the same authoring gesture PASSES
        // in db.verify (ValueComparison.equal compares numbers numerically) — one assertion,
        // two verdicts depending on which step type asserted it.
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"score\":1.0}")));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("body", Map.of("score", 1))), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("rest.verify should still match a status given as text against the actual "
            + "numeric status")
    void testVerifyStatusStringStillMatchesNumericStatus() {
        // Guards against the ValueComparison switch losing the existing text fallback: 200
        // must still match "200".
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse().withStatus(200)));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("status", "200")), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("rest.verify should fail rather than throw when expect itself is not a map")
    void testVerifyRejectsNonMapExpect() {
        // params.get("expect") is an unchecked cast inside execute(); a scenario writing
        // `expect: something` (a scalar) must not turn into a ClassCastException — a
        // StepType.execute implementation must never throw.
        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", "something"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("expect");
    }

    @Test
    @DisplayName("rest.verify should be retryable so the engine can wait on provisioning")
    void testVerifyIsRetryable() {
        assertThat(new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).retryable()).isTrue();
    }

    @Test
    @DisplayName("rest.verify should default to GET")
    void testVerifyDefaultsToGet() {
        stubFor(get(urlEqualTo("/users/jdoe")).willReturn(aResponse().withStatus(200)));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/jdoe",
                "expect", Map.of("status", 200)), false);

        assertThat(outcome.success()).isTrue();
        verify(getRequestedFor(urlEqualTo("/users/jdoe")));
    }
}
