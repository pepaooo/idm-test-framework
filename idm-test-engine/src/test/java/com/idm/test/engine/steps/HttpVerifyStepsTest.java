package com.idm.test.engine.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.client.TokenFailureText;
import com.idm.test.connector.rest.config.RestClientConfiguration;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.i18n.Messages;
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
import java.util.Locale;
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
    @DisplayName("A containsAll check passes, and a failing one names the missing values")
    void testContainsAllNote() {
        stubJson(200, "{\"groups\":[\"HR\",\"Finance\"]}");

        assertThat(run(params("check", "js:{ containsAll(response.json.groups, ['HR']) }")).success()).isTrue();

        StepResult step = run(params("check", "js:{ containsAll(response.json.groups, ['HR', 'VPN']) }"));
        assertThat(step.success()).isFalse();
        assertThat(step.message()).contains(
                "check 1 of 1 is false: js:{ containsAll(response.json.groups, ['HR', 'VPN']) } — containsAll: missing [VPN] (status 200, body: ");
    }

    @Test
    @DisplayName("A failing sameMembers check names missing and unexpected values")
    void testSameMembersNote() {
        stubJson(200, "{\"groups\":[\"HR\",\"Finance\"]}");

        StepResult step = run(params("check", "js:{ sameMembers(response.json.groups, ['HR', 'VPN']) }"));

        assertThat(step.message()).contains("— sameMembers: missing [VPN]; unexpected [Finance]");
    }

    @Test
    @DisplayName("A misspelled values argument fails the check instead of passing")
    void testMissingValuesFails() {
        stubJson(200, "{\"groups\":[\"HR\"]}");

        StepResult step = run(params("check", "js:{ excludesAll(response.json.groups, response.json.blocked) }"));

        assertThat(step.success()).isFalse();
        assertThat(step.message()).contains("check 1 of 1 failed:")
                .contains("excludesAll: the values to look for are undefined");
    }

    @Test
    @DisplayName("Helpers read XML elements whether they occur once or several times")
    void testHelpersOnXml() {
        stubBody("application/xml", "<user><role>Admin</role><role>HR</role></user>");
        assertThat(run(params("check", "js:{ containsAll(response.xml.user.role, ['HR', 'Admin']) }")).success()).isTrue();

        stubBody("application/xml", "<user><role>HR</role></user>");
        assertThat(run(params("check", "js:{ containsAll(response.xml.user.role, 'HR') }")).success()).isTrue();
    }

    @Test
    @DisplayName("A false check without helpers keeps today's message")
    void testPlainFalseUnchanged() {
        stubJson(200, "{\"department\":\"HR\"}");

        StepResult step = run(params("check", "js:{ response.json.department === 'Finance' }"));

        assertThat(step.message()).contains("is false: js:{ response.json.department === 'Finance' } (status 200")
                .doesNotContain(" — ");
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

    private static final String SOAP = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
              <soapenv:Body>
                <ns2:getUserResponse xmlns:ns2="http://idm.example/users">
                  <ns2:user id="42">
                    <ns2:status>ACTIVE</ns2:status>
                    <ns2:role>Admin</ns2:role>
                    <ns2:role>HR</ns2:role>
                  </ns2:user>
                </ns2:getUserResponse>
              </soapenv:Body>
            </soapenv:Envelope>
            """;

    private static void stubBody(String contentType, String body) {
        var response = aResponse().withStatus(200).withBody(body);
        if (contentType != null) {
            response.withHeader("Content-Type", contentType);
        }
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).willReturn(response));
    }

    @Test
    @DisplayName("A SOAP envelope is read through response.xml by local names, attributes and lists")
    void testSoapEnvelope() {
        stubBody("text/xml; charset=UTF-8", SOAP);

        StepResult step = run(params("check", List.of(
                "js:{ response.xml.Envelope.Body.getUserResponse.user.status === 'ACTIVE' }",
                "js:{ response.xml.Envelope.Body.getUserResponse.user['@id'] === '42' }",
                "js:{ [].concat(response.xml.Envelope.Body.getUserResponse.user.role).includes('HR') }",
                "js:{ response.json === null }")));

        assertThat(step.success()).as(step.message()).isTrue();
    }

    @Test
    @DisplayName("application/soap+xml and application/xml are parsed")
    void testXmlMediaTypes() {
        for (String type : List.of("application/soap+xml; charset=UTF-8", "application/xml")) {
            stubBody(type, SOAP);

            StepResult step = run(params("check", "js:{ response.xml.Envelope.Body.getUserResponse.user.status === 'ACTIVE' }"));

            assertThat(step.success()).as(type + ": " + step.message()).isTrue();
        }
    }

    @Test
    @DisplayName("When response.xml is null, a failing check that reads it says why")
    void testXmlNullHints() {
        stubBody("text/html", "<html><body>down</body></html>");
        assertThat(run(params("check", "js:{ response.xml.Envelope !== undefined }")).message())
                .endsWith(" (response.xml is null: Content-Type is text/html)");

        stubBody(null, SOAP);
        assertThat(run(params("check", "js:{ response.xml !== null }")).message())
                .endsWith(" (response.xml is null: no Content-Type)");

        stubBody("text/xml", "");
        assertThat(run(params("check", "js:{ response.xml !== null }")).message())
                .endsWith(" (response.xml is null: the body is empty)");

        stubBody("text/xml", "<Envelope>");
        assertThat(run(params("check", "js:{ response.xml !== null }")).message())
                .contains(" (response.xml is null: the body is not well-formed XML at line 1, column ");
    }

    @Test
    @DisplayName("A body nested 90 elements deep binds, and checks can still run")
    void testDeepXmlStillCheckable() {
        stubBody("text/xml", "<a>".repeat(90) + "x" + "</a>".repeat(90));
        assertThat(run(params("check", "js:{ response.status === 200 && response.xml !== null }")).success()).isTrue();
    }

    @Test
    @DisplayName("A failing check that doesn't read response.xml, or reads a parsed one, gets no hint")
    void testNoHintOtherwise() {
        stubBody("text/html", "<html/>");
        assertThat(run(params("check", "js:{ response.status === 201 }")).message())
                .doesNotContain("response.xml is null");

        stubBody("text/xml", SOAP);
        assertThat(run(params("check", "js:{ response.xml.Envelope.Body.nothing === 'x' }")).message())
                .doesNotContain("response.xml is null");
    }

    @Test
    @DisplayName("A failing response.xml check is retried under wait, not final")
    void testXmlFailureRetried() {
        stubJson(200, "{}");

        StepResult step = run(params("check", "js:{ response.xml.Envelope !== undefined }",
                "wait", Map.of("timeout", "2s", "poll", "1s")));

        assertThat(step.success()).isFalse();
        assertThat(step.attempts()).isGreaterThan(1);
        assertThat(step.message()).contains("(response.xml is null: Content-Type is application/json)")
                .endsWith("(last observed value after retrying)")
                .doesNotContain("ITF-25");
    }

    @Test
    @DisplayName("Under wait, malformed XML then a valid envelope passes on the second attempt")
    void testMalformedThenValid() {
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).inScenario("restart")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("up")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml").withBody("<Envelope>")));
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).inScenario("restart")
                .whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml").withBody(SOAP)));

        StepResult step = run(params("check", "js:{ response.xml.Envelope.Body.getUserResponse.user.status === 'ACTIVE' }",
                "wait", Map.of("timeout", "2s", "poll", "1s")));

        assertThat(step.success()).as(step.message()).isTrue();
        assertThat(step.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("as: binds response.xml, and a later step can read into it")
    void testCaptureXml() {
        stubBody("text/xml", SOAP);

        ScenarioResult result = runSteps(List.of(
                new StepSpec("http.verify", params("check", "js:{ response.status === 200 }", "as", "soap")),
                new StepSpec("set", Map.of("status", "${soap.xml.Envelope.Body.getUserResponse.user.status}"))));

        StepResult setStep = result.steps().get(1);
        assertThat(setStep.success()).as(setStep.message()).isTrue();
        assertThat(setStep.resolvedParams()).containsEntry("status", "ACTIVE");
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

    @Test
    @DisplayName("A failed check keeps the response it judged as evidence")
    void testEvidenceOnFailedCheck() {
        stubJson(200, "{\"department\":\"HR\"}");

        StepResult step = run(params("check", List.of("js:{ response.json.department === 'Finance' }")));

        assertThat(step.success()).isFalse();
        com.idm.test.engine.spi.HttpEvidence evidence = (com.idm.test.engine.spi.HttpEvidence) step.evidence();
        assertThat(evidence.status()).isEqualTo(200);
        assertThat((String) evidence.responseBody()).contains("HR");
    }

    @Test
    @DisplayName("A recursive check fails the step with the recursion message")
    void testRecursiveCheckFails() {
        stubJson(200, "{}");

        StepResult step = run(params("check", "js:{ (function f() { return 1 + f(); })() }"));

        assertThat(step.success()).isFalse();
        assertThat(step.message()).contains("check 1 of 1 failed: ").contains("recursed too deeply");
    }

    @Test
    @DisplayName("A JSON body followed by more content is read as its first value, as under Jackson 2")
    void testTrailingContentAfterJson() {
        stubJson(200, "{\"a\":1} {\"b\":2}");

        assertThat(run(params("check", "js:{ response.json.a === 1 }")).success()).isTrue();
    }

    private static String spanish(StepResult step) {
        return Messages.render(step.messageDetail(), Locale.of("es"));
    }

    @Test
    @DisplayName("A false check renders in Spanish, its list-helper notes too")
    void testFalseCheckRendersInSpanish() {
        stubJson(200, "{\"groups\":[\"HR\",\"Finance\"]}");

        StepResult step = run(params("check",
                "js:{ containsAll(response.json.groups, ['VPN']) || sameMembers(response.json.groups, ['HR']) }"));

        String head = "http.verify GET " + WIREMOCK.baseUrl() + "/users/1: ";
        String check = "js:{ containsAll(response.json.groups, ['VPN']) || sameMembers(response.json.groups, ['HR']) }";
        assertThat(step.message()).isEqualTo(head + "check 1 of 1 is false: " + check
                + " — containsAll: missing [VPN]; sameMembers: unexpected [Finance]"
                + " (status 200, body: {\"groups\":[\"HR\",\"Finance\"]})");
        assertThat(spanish(step)).isEqualTo(head + "la comprobación 1 de 1 es falsa: " + check
                + " — containsAll: faltan [VPN]; sameMembers: inesperados [Finance]"
                + " (estado 200, body: {\"groups\":[\"HR\",\"Finance\"]})");
    }

    @Test
    @DisplayName("The response.xml hint renders in Spanish")
    void testXmlHintRendersInSpanish() {
        stubBody("text/html", "<p>hi</p>");
        assertThat(spanish(run(params("check", "js:{ response.xml !== null }"))))
                .endsWith(" (response.xml es null: el Content-Type es text/html)");

        stubBody("text/xml", "<Envelope>");
        assertThat(spanish(run(params("check", "js:{ response.xml !== null }"))))
                .contains(" (response.xml es null: el body es XML mal formado en la línea 1, columna ");
    }

    @Test
    @DisplayName("Notes are masked in both languages, and notes past the cap keep their English text exactly")
    void testNotesMaskedAndCapped() {
        stubJson(200, "{}");

        StepResult masked = run(params("check", "js:{ containsAll([], 'Bearer abcdefgh123456') }"));
        assertThat(masked.message()).contains(" — containsAll: missing [Bearer ****] (status 200");
        assertThat(spanish(masked)).contains(" — containsAll: faltan [Bearer ****] (estado 200");

        String a = "a".repeat(200);
        String b = "b".repeat(200);
        StepResult capped = run(params("check", "js:{ containsAll([], ['" + a + "', '" + b + "']) }"));
        String notes = TokenFailureText.safe("containsAll: missing [" + a + ", " + b + "]", null);
        assertThat(notes).endsWith("…");
        assertThat(capped.message()).contains(" — " + notes + " (status 200, body: {})");
        assertThat(spanish(capped)).contains(" — " + notes + " (estado 200, body: {})");
    }

    @Test
    @DisplayName("A check whose expression fails renders the expression error in Spanish too")
    void testThrowingCheckRendersInSpanish() {
        stubJson(200, "{}");

        StepResult step = run(params("check", "js:{ response.json.missing.deeper }"));

        String head = "http.verify GET " + WIREMOCK.baseUrl() + "/users/1: ";
        assertThat(step.message()).startsWith(head + "check 1 of 1 failed: could not evaluate js:{ response.json.missing.deeper }: ");
        assertThat(spanish(step)).startsWith(head + "la comprobación 1 de 1 falló: no se pudo evaluar"
                + " js:{ response.json.missing.deeper }: ").endsWith(" (estado 200, body: {})");
    }
}
