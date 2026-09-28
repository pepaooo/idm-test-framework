package com.idm.test.engine.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestClientConfiguration;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("http.verify")
class HttpVerifyStepsTest {

    private static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    private static final IdmRestConnector CONNECTOR = new IdmRestConnector(
            RestClient.builder().requestFactory(RestClientConfiguration.requestFactory()).build());

    private static RestProperties properties() {
        RestProperties properties = new RestProperties();
        RestProperties.RestApiConfig mock = new RestProperties.RestApiConfig();
        mock.setBaseUrl(WIREMOCK.baseUrl());
        properties.getRest().put("mock", mock);
        RestProperties.RestApiConfig secured = new RestProperties.RestApiConfig();
        secured.setBaseUrl(WIREMOCK.baseUrl());
        secured.setUsername("svc");
        secured.setPassword("s3cret");
        properties.getRest().put("secured", secured);
        return properties;
    }

    private static final HttpVerifyStep STEP =
            new HttpVerifyStep(CONNECTOR, properties(), RestAuthenticator.withoutOAuth());

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIREMOCK.resetAll();
    }

    private static StepResult run(Map<String, Object> params) {
        return runSteps(List.of(new StepSpec("http.verify", params))).steps().get(0);
    }

    private static ScenarioResult runSteps(List<StepSpec> steps) {
        ScenarioEngine engine = new ScenarioEngine(Map.of(STEP.name(), STEP),
                new FixtureFactory(new TestDataGenerator()), new TestExecutor(),
                Duration.ofSeconds(2), Duration.ofMillis(100), new ExpressionEvaluator());
        return engine.run(new com.idm.test.engine.model.Scenario("verify", null, Map.of(), steps), "verify.yaml");
    }

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("target", "mock");
        params.put("method", "GET");
        params.put("path", "/users/1");
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    private static void stubJson(int status, String body) {
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("Passes when every check is true, and reads status and json")
    void testChecksPass() {
        stubJson(200, "{\"department\":\"Finance\"}");

        StepResult step = run(params("check", List.of(
                "js:{ response.status === 200 }", "js:{ response.json.department === 'Finance' }")));

        assertThat(step.success()).as(step.message()).isTrue();
        assertThat(step.message()).isEqualTo("GET " + WIREMOCK.baseUrl() + "/users/1 passed 2 check(s) (status 200)");
    }

    @Test
    @DisplayName("Names the first false check with its index, status and body excerpt; later checks are not evaluated")
    void testFirstFalseCheckNamed() {
        stubJson(200, "{\"department\":\"HR\"}");

        StepResult step = run(params("check", List.of(
                "js:{ response.status === 200 }",
                "js:{ response.json.department === 'Finance' }",
                "js:{ response.json.missing.deep === 1 }")));

        assertThat(step.success()).isFalse();
        assertThat(step.message()).isEqualTo("http.verify GET " + WIREMOCK.baseUrl()
                + "/users/1: check 2 of 3 is false: js:{ response.json.department === 'Finance' }"
                + " (status 200, body: {\"department\":\"HR\"})");
    }

    @Test
    @DisplayName("json is parsed for application/json and +json, and null for text or invalid JSON")
    void testJsonParsing() {
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/problem+json; charset=UTF-8").withBody("{\"a\":1}")));
        assertThat(run(params("check", "js:{ response.json.a === 1 }")).success()).isTrue();

        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/plain").withBody("{\"a\":1}")));
        assertThat(run(params("check", "js:{ response.json === null && response.body === '{\"a\":1}' }")).success()).isTrue();

        stubJson(200, "not json");
        assertThat(run(params("check", "js:{ response.json === null }")).success()).isTrue();
    }

    @Test
    @DisplayName("A throwing check is an ordinary failure, retried under wait")
    void testThrowingCheckRetried() {
        stubJson(200, "{}");

        StepResult step = run(params("check", "js:{ response.json.missing.deep === 1 }",
                "wait", Map.of("timeout", "1s", "poll", "1s")));

        assertThat(step.success()).isFalse();
        assertThat(step.message()).contains("check 1 of 1 failed:").endsWith("(last observed value after retrying)");
    }

    @Test
    @DisplayName("A non-boolean result is a final failure that stops a wait at once")
    void testNonBooleanIsFinal() {
        stubJson(200, "{\"status\":\"ACTIVE\"}");

        StepResult step = run(params("check", "js:{ response.json.status }", "wait", Boolean.TRUE));

        assertThat(step.success()).isFalse();
        assertThat(step.attempts()).isEqualTo(1);
        assertThat(step.message()).contains("check 1 of 1 must return true or false, got \"ACTIVE\"")
                .endsWith("(not retried: waiting cannot change this)");
    }

    @Test
    @DisplayName("Reading response.xml fails with the ITF-25 hint and ends a wait at once")
    void testXmlHint() {
        stubJson(200, "{}");

        StepResult step = run(params("check", "js:{ response.xml.Envelope !== undefined }",
                "wait", Map.of("timeout", "2s", "poll", "1s")));

        assertThat(step.success()).isFalse();
        assertThat(step.attempts()).isEqualTo(1);
        assertThat(step.message()).contains("response.xml is not available yet: it arrives with ITF-25")
                .endsWith("(not retried: waiting cannot change this)");
    }

    private static final String SECRET_BODY =
            ("Bearer abcdefgh123456" + "x".repeat(2000)).substring(0, 2000);

    @Test
    @DisplayName("A throwing check's error text is capped and secrets in the body are masked")
    void testThrowingCheckMessageIsCapped() {
        stubJson(200, SECRET_BODY);

        StepResult step = run(params("check", "js:{ response.body.contain('x') }"));

        assertThat(step.success()).isFalse();
        assertThat(step.message()).doesNotContain("abcdefgh123456");
        assertThat(step.message().length()).isLessThan(900);
    }

    @Test
    @DisplayName("A non-boolean check result is capped and secrets in it are masked")
    void testNonBooleanMessageIsCapped() {
        stubJson(200, SECRET_BODY);

        StepResult step = run(params("check", "js:{ response }"));

        assertThat(step.success()).isFalse();
        assertThat(step.message()).doesNotContain("abcdefgh123456");
        assertThat(step.message().length()).isLessThan(900);
    }

    @Test
    @DisplayName("Under wait, a 404 then a 200 passes on the second attempt")
    void testWaitsForProvisioning() {
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).inScenario("provisioning")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("ready")
                .willReturn(aResponse().withStatus(404).withBody("not yet")));
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).inScenario("provisioning")
                .whenScenarioStateIs("ready")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"active\":true}")));

        StepResult step = run(params("check", List.of("js:{ response.status === 200 }", "js:{ response.json.active }"),
                "wait", Map.of("timeout", "2s", "poll", "1s")));

        assertThat(step.success()).as(step.message()).isTrue();
        assertThat(step.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("as: binds the passing response, and a later step can read into it")
    void testCapture() {
        stubJson(200, "{\"id\":7}");

        ScenarioResult result = runSteps(List.of(
                new StepSpec("http.verify", params("check", "js:{ response.status === 200 }", "as", "account")),
                new StepSpec("set", Map.of("accountId", "${account.json.id}"))));

        StepResult verifyStep = result.steps().get(0);
        StepResult setStep = result.steps().get(1);
        assertThat(verifyStep.success()).as(verifyStep.message()).isTrue();
        assertThat(setStep.success()).as(setStep.message()).isTrue();
        assertThat(setStep.resolvedParams()).containsEntry("accountId", 7);
    }

    @Test
    @DisplayName("Target authentication applies, and the author's own header wins")
    void testAuthentication() {
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse().withStatus(200)));

        run(params("target", "secured", "check", "js:{ response.status === 200 }"));
        WIREMOCK.verify(getRequestedFor(urlEqualTo("/users/1")).withHeader("Authorization", equalTo("Basic c3ZjOnMzY3JldA==")));

        WIREMOCK.resetRequests();
        run(params("target", "secured", "headers", Map.of("authorization", "Basic bad"), "check", "js:{ true }"));
        WIREMOCK.verify(getRequestedFor(urlEqualTo("/users/1")).withHeader("Authorization", equalTo("Basic bad")));
    }

    @Test
    @DisplayName("An unknown target, bad method, non-text body or non-map headers stop a wait after one attempt")
    void testConfigurationErrorsAreFinal() {
        for (Map<String, Object> bad : List.of(
                params("target", "nope", "check", "js:{ true }", "wait", Boolean.TRUE),
                params("method", "TRACE", "check", "js:{ true }", "wait", Boolean.TRUE),
                params("body", Map.of("a", 1), "check", "js:{ true }", "wait", Boolean.TRUE),
                params("headers", "x", "check", "js:{ true }", "wait", Boolean.TRUE))) {
            StepResult step = run(bad);
            assertThat(step.success()).as(bad.toString()).isFalse();
            assertThat(step.attempts()).as(bad.toString()).isEqualTo(1);
        }
    }
}
