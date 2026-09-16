package com.idm.test.engine.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.engine.spi.StepOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

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
            new IdmRestConnector(RestClient.builder().build());

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

        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/1"), true);

        assertThat(outcome.success()).isTrue();
        Map<String, Object> captured = (Map<String, Object>) outcome.value();
        assertThat(captured.get("status")).isEqualTo(200);
        assertThat(captured).containsKey("headers");
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
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
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
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/missing"), true);

        assertThat(outcome.success()).isTrue();
        Map<String, Object> captured = (Map<String, Object>) outcome.value();
        assertThat(captured.get("status")).isEqualTo(404);
    }

    @Test
    @DisplayName("rest.request should fail for an unknown target")
    void testRequestUnknownTargetFails() {
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
                "target", "nope", "method", "GET", "path", "/users/1"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("nope");
    }

    @Test
    @DisplayName("rest.request should reject body and bodyFrom together")
    void testRequestRejectsBothBodyForms() {
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
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

        new RestRequestStep(CONNECTOR, PROPERTIES).execute(Map.of(
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

        StepOutcome outcome = new RestRequestStep(CONNECTOR, dead).execute(Map.of(
                "target", "idm", "method", "GET", "path", "/users/1"), false);

        assertThat(outcome.success()).isFalse();
    }
}
