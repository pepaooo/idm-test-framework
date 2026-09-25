package com.idm.test.engine.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.config.RestClientConfiguration;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.ValidationException;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.spi.StepOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives http.request against WireMock: raw string in, raw string out, REST and SOAP alike.
 */
@DisplayName("http.request")
class HttpStepsTest {

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
        RestProperties.RestApiConfig mock = new RestProperties.RestApiConfig();
        mock.setBaseUrl("http://localhost:" + WIREMOCK.port());
        properties.getRest().put("mock", mock);
        RestProperties.RestApiConfig down = new RestProperties.RestApiConfig();
        down.setBaseUrl("http://localhost:1");
        properties.getRest().put("down", down);
        return properties;
    }

    private static final HttpRequestStep STEP = new HttpRequestStep(CONNECTOR, PROPERTIES);

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

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> capture(StepOutcome outcome) {
        return (Map<String, Object>) outcome.value();
    }

    @Test
    @DisplayName("Sends a JSON body byte-for-byte with the author's Content-Type, in any method case")
    void testJsonBodySentAsIs() {
        stubFor(post(urlEqualTo("/users")).willReturn(aResponse().withStatus(201).withBody("{\"id\":7}")));

        StepOutcome outcome = STEP.execute(params(
                "target", "mock", "method", "post", "path", "/users",
                "headers", Map.of("Content-Type", "application/json"),
                "body", "{\"userId\":\"jdoe\"}"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(capture(outcome)).containsEntry("status", 201).containsEntry("body", "{\"id\":7}");
        verify(postRequestedFor(urlEqualTo("/users"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalTo("{\"userId\":\"jdoe\"}")));
    }

    @Test
    @DisplayName("Sends a SOAP envelope with SOAPAction and returns the XML response as raw text")
    void testSoapEnvelope() {
        String envelope = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><getUser>jdoe</getUser></soapenv:Body></soapenv:Envelope>";
        String reply = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body><user>jdoe</user></soapenv:Body></soapenv:Envelope>";
        stubFor(post(urlEqualTo("/IDMProv/role/service")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "text/xml; charset=UTF-8").withBody(reply)));

        StepOutcome outcome = STEP.execute(params(
                "target", "mock", "method", "POST", "path", "/IDMProv/role/service",
                "headers", Map.of("Content-Type", "text/xml; charset=UTF-8", "SOAPAction", "urn:getUser"),
                "body", envelope), true);

        assertThat(outcome.success()).isTrue();
        assertThat(capture(outcome).get("body")).isEqualTo(reply);
        verify(postRequestedFor(urlEqualTo("/IDMProv/role/service"))
                .withHeader("SOAPAction", equalTo("urn:getUser"))
                .withHeader("Content-Type", containing("text/xml"))
                .withRequestBody(equalTo(envelope)));
    }

    @Test
    @DisplayName("Captures an error status as a successful step, with the status in the message")
    void testErrorStatusCaptured() {
        stubFor(get(urlEqualTo("/boom")).willReturn(aResponse().withStatus(500).withBody("boom")));

        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "GET", "path", "/boom"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.message()).contains("returned 500");
        assertThat(capture(outcome)).containsEntry("status", 500).containsEntry("body", "boom");
    }

    @Test
    @DisplayName("Defaults Content-Type to text/plain; charset=UTF-8 and Accept to */*")
    void testDefaultContentTypeAndAccept() {
        stubFor(post(urlEqualTo("/echo")).willReturn(aResponse().withStatus(200)));

        StepOutcome outcome =
                STEP.execute(params("target", "mock", "method", "POST", "path", "/echo", "body", "héllo"), true);

        assertThat(outcome.success()).isTrue();
        verify(postRequestedFor(urlEqualTo("/echo"))
                .withHeader("Content-Type", containing("text/plain"))
                .withHeader("Content-Type", containing("UTF-8"))
                .withHeader("Accept", equalTo("*/*"))
                .withRequestBody(equalTo("héllo")));
    }

    @Test
    @DisplayName("Refuses a map body without sending anything")
    void testMapBodyRefused() {
        StepOutcome outcome = STEP.execute(params(
                "target", "mock", "method", "POST", "path", "/users", "body", Map.of("a", 1)), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).isEqualTo(
                "http.request body must be a string sent as-is; to send a JSON object use rest.request");
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Refuses non-map headers without sending anything")
    void testNonMapHeadersRefused() {
        StepOutcome outcome = STEP.execute(params(
                "target", "mock", "method", "GET", "path", "/x", "headers", "Accept: text/xml"), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).isEqualTo("http.request headers must be a map of header name to value");
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Lower-cases response header names")
    @SuppressWarnings("unchecked")
    void testHeaderNamesLowerCased() {
        stubFor(get(urlEqualTo("/h")).willReturn(aResponse().withStatus(200).withHeader("X-Custom-Header", "v")));

        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "GET", "path", "/h"), true);

        Map<String, List<String>> headers = (Map<String, List<String>>) capture(outcome).get("headers");
        assertThat(headers).containsEntry("x-custom-header", List.of("v")).doesNotContainKey("X-Custom-Header");
    }

    @Test
    @DisplayName("Decodes a response with its declared non-UTF-8 charset")
    void testNonUtf8ResponseDecoded() {
        stubFor(get(urlEqualTo("/latin")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/plain; charset=ISO-8859-1")
                .withBody("café".getBytes(StandardCharsets.ISO_8859_1))));

        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "GET", "path", "/latin"), true);

        assertThat(capture(outcome).get("body")).isEqualTo("café");
    }

    @Test
    @DisplayName("Sends PATCH with its body on the production HTTP client")
    void testPatchSupported() {
        stubFor(patch(urlEqualTo("/users/7")).willReturn(aResponse().withStatus(200).withBody("ok")));

        StepOutcome outcome = STEP.execute(params(
                "target", "mock", "method", "PATCH", "path", "/users/7",
                "headers", Map.of("Content-Type", "application/json"),
                "body", "{\"mail\":\"j@x\"}"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(capture(outcome)).containsEntry("status", 200).containsEntry("body", "ok");
        verify(patchRequestedFor(urlEqualTo("/users/7")).withRequestBody(equalTo("{\"mail\":\"j@x\"}")));
    }

    @Test
    @DisplayName("Encodes the request body in the charset its Content-Type declares")
    void testRequestCharsetFromContentType() {
        stubFor(post(urlEqualTo("/latin")).willReturn(aResponse().withStatus(200)));

        StepOutcome outcome = STEP.execute(params(
                "target", "mock", "method", "POST", "path", "/latin",
                "headers", Map.of("Content-Type", "text/plain; charset=ISO-8859-1"),
                "body", "café"), true);

        assertThat(outcome.success()).isTrue();
        List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/latin")));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getBody()).containsExactly(99, 97, 102, -23);
    }

    @Test
    @DisplayName("Falls back to UTF-8 for an unsupported response charset, and still succeeds")
    void testUnsupportedResponseCharsetFallsBack() {
        stubFor(get(urlEqualTo("/bogus")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "text/plain; charset=bogus-cs")
                .withBody("oops")));

        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "GET", "path", "/bogus"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(capture(outcome)).containsEntry("status", 404).containsEntry("body", "oops");
    }

    @Test
    @DisplayName("Captures an empty response body as an empty string, never null")
    void testEmptyBodyIsEmptyString() {
        stubFor(delete(urlEqualTo("/gone")).willReturn(aResponse().withStatus(204)));

        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "DELETE", "path", "/gone"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(capture(outcome)).containsEntry("status", 204).containsEntry("body", "");
    }

    @Test
    @DisplayName("Refuses an unknown method without sending anything")
    void testUnknownMethodRefused() {
        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "FETCH", "path", "/x"), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).isEqualTo(
                "http.request method 'FETCH' is not one of " + new TreeSet<>(IdmRestConnector.SUPPORTED_METHODS));
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Fails on a transport problem")
    void testTransportFailure() {
        StepOutcome outcome = STEP.execute(params("target", "down", "method", "GET", "path", "/x"), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).startsWith("http.request failed for http://localhost:1/x");
        // The JDK client's ConnectException has a null message; the failure must still say what
        // went wrong rather than end in "null".
        assertThat(outcome.message()).doesNotEndWith("null")
                .containsAnyOf("ConnectException", "Connection refused");
    }

    @Test
    @DisplayName("Reports a read timeout as TIMEOUT with a real message")
    void testReadTimeout() {
        stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withBody("late")
                .withFixedDelay(3000)));
        IdmRestConnector impatient = new IdmRestConnector(RestClient.builder()
                .requestFactory(RestClientConfiguration.requestFactory(Duration.ofSeconds(1))).build());

        OperationResult<TextResponse> result = impatient.exchangeText(
                "GET", "http://localhost:" + WIREMOCK.port() + "/slow", null, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).contains(ErrorType.TIMEOUT);
        assertThat(result.getMessage()).doesNotEndWith("null").contains("Timeout");
    }

    @Test
    @DisplayName("Speaks HTTP/1.1: no h2c upgrade headers are sent")
    void testNoHttp2Upgrade() {
        stubFor(get(urlEqualTo("/plain")).willReturn(aResponse().withStatus(200)));

        STEP.execute(params("target", "mock", "method", "GET", "path", "/plain"), true);

        verify(getRequestedFor(urlEqualTo("/plain"))
                .withoutHeader("Upgrade")
                .withoutHeader("HTTP2-Settings"));
        // WireMock serves an accepted h2c upgrade as HTTP/2 and does not log the upgrade headers,
        // so the protocol it served is the check that actually discriminates.
        LoggedRequest request = findAll(getRequestedFor(urlEqualTo("/plain"))).get(0);
        assertThat(request.getProtocol()).isEqualTo("HTTP/1.1");
        assertThat(request.getAllHeaderKeys())
                .noneMatch(key -> key.equalsIgnoreCase("Upgrade") || key.equalsIgnoreCase("HTTP2-Settings"));
    }

    @Test
    @DisplayName("Captures a HEAD response with an empty body")
    void testHeadHasEmptyBody() {
        stubFor(head(urlEqualTo("/head")).willReturn(aResponse().withStatus(200).withBody("ok")));

        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "HEAD", "path", "/head"), true);

        assertThat(outcome.success()).isTrue();
        assertThat(capture(outcome)).containsEntry("status", 200).containsEntry("body", "");
    }

    @Test
    @DisplayName("Fails on an unknown target, naming it")
    void testUnknownTarget() {
        StepOutcome outcome = STEP.execute(params("target", "nope", "method", "GET", "path", "/x"), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("http.request").contains("'nope'");
    }

    @Test
    @DisplayName("Declares its parameters and is an action, not an assertion")
    void testContract() {
        assertThat(STEP.name()).isEqualTo("http.request");
        assertThat(STEP.requiredParams()).containsExactlyInAnyOrder("target", "method", "path");
        assertThat(STEP.acceptedParams()).containsExactlyInAnyOrder("target", "method", "path", "headers", "body");
        assertThat(STEP.retryable()).isFalse();
    }

    @Test
    @DisplayName("Rejects wait: at load time, because it is an action")
    void testWaitRejectedAtLoad() {
        ScenarioValidator validator = new ScenarioValidator(Map.of("http.request", STEP), Set.of());
        Scenario scenario = new Scenario("t", null, Map.of(), List.of(new StepSpec("http.request",
                Map.of("target", "mock", "method", "GET", "path", "/x", "wait", true))));

        assertThatThrownBy(() -> validator.validate(scenario, "t.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'wait' is only valid on assertions");
    }
}
