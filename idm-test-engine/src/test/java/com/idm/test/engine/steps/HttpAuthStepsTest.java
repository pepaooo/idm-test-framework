package com.idm.test.engine.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestClientConfiguration;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.result.JsonResultWriter;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three HTTP steps send the Authorization header their target's settings call for, unless
 * the step writes its own.
 */
@DisplayName("HTTP steps: target authentication")
class HttpAuthStepsTest {

    private static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    private static final String PASSWORD = "s3cret";
    /** base64("svc:s3cret") */
    private static final String BASIC = "Basic c3ZjOnMzY3JldA==";
    private static final String CLIENT_SECRET = "client-secret-value";

    private static final IdmRestConnector CONNECTOR = new IdmRestConnector(
            RestClient.builder().requestFactory(RestClientConfiguration.requestFactory()).build());

    private static final RestProperties PROPERTIES = properties();

    private static RestProperties properties() {
        RestProperties properties = new RestProperties();
        String base = WIREMOCK.baseUrl();

        RestProperties.RestApiConfig basic = new RestProperties.RestApiConfig();
        basic.setBaseUrl(base);
        basic.setUsername("svc");
        basic.setPassword(PASSWORD);
        properties.getRest().put("basic", basic);

        RestProperties.RestApiConfig oauth = new RestProperties.RestApiConfig();
        oauth.setBaseUrl(base);
        oauth.setUseOAuth(true);
        oauth.setOauthClientName("svc");
        properties.getRest().put("oauth", oauth);

        RestProperties.RestApiConfig open = new RestProperties.RestApiConfig();
        open.setBaseUrl(base);
        properties.getRest().put("open", open);

        RestProperties.RestApiConfig half = new RestProperties.RestApiConfig();
        half.setBaseUrl(base);
        half.setPassword(PASSWORD);
        properties.getRest().put("half", half);
        return properties;
    }

    /**
     * A new authenticator with an empty token cache, so a test that counts token requests is not
     * fooled by a token another test already fetched.
     */
    private static RestAuthenticator freshAuthenticator() {
        return new RestAuthenticator(
                new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("svc")
                        .clientId("svc-client")
                        .clientSecret(CLIENT_SECRET)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .tokenUri(WIREMOCK.baseUrl() + "/oauth/token")
                        .build()),
                null);
    }

    private static List<StepType> steps(RestAuthenticator authenticator) {
        return List.of(
                new RestRequestStep(CONNECTOR, PROPERTIES, authenticator),
                new RestVerifyStep(CONNECTOR, PROPERTIES, authenticator),
                new HttpRequestStep(CONNECTOR, PROPERTIES, authenticator));
    }

    /** Parameters that make each step send GET /thing to the target. */
    private static Map<String, Object> params(StepType step, String target, Map<String, Object> headers) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("target", target);
        params.put("method", "GET");
        params.put("path", "/thing");
        if (headers != null) {
            params.put("headers", headers);
        }
        if (step.name().equals("rest.verify")) {
            params.put("expect", Map.of("status", 200));
        }
        return params;
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlEqualTo("/thing")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        WIREMOCK.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    }

    @Test
    @DisplayName("A basic-auth target's requests carry its Basic header, and nothing reports the secret")
    void testBasicSent() {
        for (StepType step : steps(freshAuthenticator())) {
            WIREMOCK.resetRequests();

            StepOutcome outcome = step.execute(params(step, "basic", null), true);

            assertThat(outcome.success()).as(step.name() + ": " + outcome.message()).isTrue();
            WIREMOCK.verify(getRequestedFor(urlEqualTo("/thing")).withHeader("Authorization", equalTo(BASIC)));
            assertThat(outcome.message() + outcome.value()).as(step.name())
                    .doesNotContain(PASSWORD).doesNotContain(BASIC).doesNotContain("c3ZjOnMzY3JldA");
        }
    }

    @Test
    @DisplayName("An OAuth target's requests carry a Bearer token")
    void testBearerSent() {
        for (StepType step : steps(freshAuthenticator())) {
            WIREMOCK.resetRequests();

            StepOutcome outcome = step.execute(params(step, "oauth", null), true);

            assertThat(outcome.success()).as(step.name() + ": " + outcome.message()).isTrue();
            WIREMOCK.verify(getRequestedFor(urlEqualTo("/thing")).withHeader("Authorization", equalTo("Bearer tok-1")));
            assertThat(outcome.message() + outcome.value()).as(step.name()).doesNotContain("tok-1");
        }
    }

    @Test
    @DisplayName("A step's own Authorization header, in any case, is sent alone and no token is fetched")
    void testAuthorWins() {
        for (StepType step : steps(freshAuthenticator())) {
            WIREMOCK.resetRequests();

            StepOutcome outcome = step.execute(
                    params(step, "oauth", Map.of("authorization", "Basic bad")), true);

            assertThat(outcome.success()).as(step.name() + ": " + outcome.message()).isTrue();
            WIREMOCK.verify(getRequestedFor(urlEqualTo("/thing")).withHeader("Authorization", equalTo("Basic bad")));
            assertThat(WIREMOCK.findAll(getRequestedFor(urlEqualTo("/thing"))).get(0)
                    .getHeaders().getHeader("Authorization").values()).containsExactly("Basic bad");
            WIREMOCK.verify(0, postRequestedFor(urlEqualTo("/oauth/token")));
        }
    }

    @Test
    @DisplayName("An unauthenticated target's requests carry no Authorization header")
    void testNoneSent() {
        for (StepType step : steps(freshAuthenticator())) {
            WIREMOCK.resetRequests();

            StepOutcome outcome = step.execute(params(step, "open", null), true);

            assertThat(outcome.success()).as(step.name() + ": " + outcome.message()).isTrue();
            WIREMOCK.verify(getRequestedFor(urlEqualTo("/thing")).withoutHeader("Authorization"));
        }
    }

    @Test
    @DisplayName("A half-configured target fails the step before sending, without the secret")
    void testConfigurationErrorFails() {
        for (StepType step : steps(freshAuthenticator())) {
            WIREMOCK.resetRequests();

            StepOutcome outcome = step.execute(params(step, "half", null), true);

            assertThat(outcome.success()).as(step.name()).isFalse();
            assertThat(outcome.message()).startsWith(step.name())
                    .contains("'half'").contains("password but no username").doesNotContain(PASSWORD);
            WIREMOCK.verify(0, getRequestedFor(urlEqualTo("/thing")));
        }
    }

    @Test
    @DisplayName("A rejected token request fails the step without the client secret")
    void testTokenRejectedFails() {
        WIREMOCK.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_client\"}")));
        HttpRequestStep step = new HttpRequestStep(CONNECTOR, PROPERTIES, freshAuthenticator());

        StepOutcome outcome = step.execute(params(step, "oauth", null), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("invalid_client").doesNotContain(CLIENT_SECRET);
        WIREMOCK.verify(0, getRequestedFor(urlEqualTo("/thing")));
    }

    private static ScenarioResult runVerifyUnderWait(RestAuthenticator authenticator) {
        RestVerifyStep verify = new RestVerifyStep(CONNECTOR, PROPERTIES, authenticator);
        ScenarioEngine engine = new ScenarioEngine(Map.of(verify.name(), verify),
                new FixtureFactory(new TestDataGenerator()), new TestExecutor(),
                Duration.ofSeconds(2), Duration.ofMillis(200), new ExpressionEvaluator());
        return engine.run(new Scenario("wait", null, Map.of(), List.of(new StepSpec("rest.verify",
                Map.of("target", "oauth", "path", "/thing", "expect", Map.of("status", 200),
                        "wait", Map.of("timeout", "2s", "poll", "1s"))))), "wait.yaml");
    }

    @Test
    @DisplayName("Under wait, a rejected token request stops after one attempt and one token request")
    void testRejectedTokenStopsWait() {
        WIREMOCK.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(401)));

        ScenarioResult result = runVerifyUnderWait(freshAuthenticator());

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).attempts()).isEqualTo(1);
        assertThat(result.steps().get(0).message())
                .endsWith("(not retried: waiting cannot change this)")
                .doesNotContain(CLIENT_SECRET);
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
        WIREMOCK.verify(0, getRequestedFor(urlEqualTo("/thing")));
    }

    @Test
    @DisplayName("Under wait, a token server returning 503 is retried")
    void testTokenServerErrorRetried() {
        WIREMOCK.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(503)));

        ScenarioResult result = runVerifyUnderWait(freshAuthenticator());

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).attempts()).isGreaterThan(1);
        assertThat(result.steps().get(0).message()).endsWith("(last observed value after retrying)");
    }

    @Test
    @DisplayName("A half-configured target is a final failure in rest.verify")
    void testHalfConfiguredIsFinal() {
        RestVerifyStep verify = new RestVerifyStep(CONNECTOR, PROPERTIES, freshAuthenticator());

        StepOutcome outcome = verify.execute(Map.of("target", "half", "path", "/thing",
                "expect", Map.of("status", 200)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.isFinal()).isTrue();
    }

    @Test
    @DisplayName("Keyed wait messages and their arguments in the results file carry no secret")
    void testKeyedMessagesCarryNoSecret(@TempDir Path dir) throws Exception {
        WIREMOCK.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_client\"}")));
        ScenarioResult rejected = runVerifyUnderWait(freshAuthenticator());

        RestVerifyStep verify = new RestVerifyStep(CONNECTOR, PROPERTIES, freshAuthenticator());
        ScenarioResult halfConfigured = new ScenarioEngine(Map.of(verify.name(), verify),
                new FixtureFactory(new TestDataGenerator()), new TestExecutor(),
                Duration.ofSeconds(2), Duration.ofMillis(200), new ExpressionEvaluator())
                .run(new Scenario("half", null, Map.of(), List.of(new StepSpec("rest.verify",
                        Map.of("target", "half", "path", "/thing", "expect", Map.of("status", 200),
                                "wait", Map.of("timeout", "2s", "poll", "1s"))))), "half.yaml");

        assertThat(rejected.steps().get(0).messageKey()).isEqualTo("engine.wait.notRetried");
        assertThat(halfConfigured.steps().get(0).messageKey()).isEqualTo("engine.wait.notRetried");

        Path target = dir.resolve("results.json");
        JsonResultWriter.write(List.of(rejected, halfConfigured), target);
        String json = Files.readString(target);

        assertThat(json).contains("\"messageArgs\"")
                .doesNotContain(CLIENT_SECRET).doesNotContain(PASSWORD).doesNotContain(BASIC)
                .doesNotContain("Bearer ");
    }

    private static String spanish(StepOutcome outcome) {
        return Messages.render(outcome.detail(), Locale.of("es"));
    }

    @Test
    @DisplayName("A half-configured target's failure renders in Spanish, without the secret")
    void testConfigurationErrorRendersInSpanish() {
        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES, freshAuthenticator())
                .execute(Map.of("target", "half", "method", "GET", "path", "/thing"), false);

        assertThat(spanish(outcome)).isEqualTo("rest.request no pudo autenticarse: El destino REST 'half' tiene "
                + "una contraseña pero no un usuario; defina idm.test.rest.half.username y .password, o ninguno de los dos");
        assertThat(outcome.message()).isEqualTo("rest.request could not authenticate: REST target 'half' has a "
                + "password but no username; set both idm.test.rest.half.username and .password, or neither");
        assertThat(outcome.detail().args()).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("A rejected token request renders in Spanish, without the client secret")
    void testTokenRejectedRendersInSpanish() {
        WIREMOCK.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_client\"}")));
        HttpRequestStep step = new HttpRequestStep(CONNECTOR, PROPERTIES, freshAuthenticator());

        StepOutcome outcome = step.execute(params(step, "oauth", null), true);

        assertThat(outcome.message()).isEqualTo("http.request could not authenticate: OAuth2 token request for "
                + "registration 'svc' (target 'oauth') failed: [invalid_client]");
        assertThat(spanish(outcome)).isEqualTo("http.request no pudo autenticarse: La solicitud de token OAuth2 para "
                + "el registro 'svc' (destino 'oauth') falló: [invalid_client]")
                .doesNotContain(CLIENT_SECRET);
    }
}
