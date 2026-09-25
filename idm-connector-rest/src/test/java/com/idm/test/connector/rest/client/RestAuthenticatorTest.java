package com.idm.test.connector.rest.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RestAuthenticator")
class RestAuthenticatorTest {

    private static WireMockServer wiremock;

    @BeforeAll
    static void start() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stop() {
        wiremock.stop();
    }

    @BeforeEach
    void reset() {
        wiremock.resetAll();
    }

    private static RestProperties.RestApiConfig basic(String username, String password) {
        RestProperties.RestApiConfig config = new RestProperties.RestApiConfig();
        config.setBaseUrl("http://localhost:1");
        config.setUsername(username);
        config.setPassword(password);
        return config;
    }

    private static RestProperties.RestApiConfig oauth(String registration) {
        RestProperties.RestApiConfig config = new RestProperties.RestApiConfig();
        config.setBaseUrl("http://localhost:1");
        config.setUseOAuth(true);
        config.setOauthClientName(registration);
        return config;
    }

    private static RestAuthenticator withRegistration(String id) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(id)
                .clientId("svc-client")
                .clientSecret("svc-secret-value")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(wiremock.baseUrl() + "/oauth/token")
                .build();
        return new RestAuthenticator(new InMemoryClientRegistrationRepository(registration), null);
    }

    private static void stubToken(String token) {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    }

    @Test
    @DisplayName("Username and password give a Basic header")
    void testBasic() {
        OperationResult<String> result = RestAuthenticator.withoutOAuth()
                .authorization("idm", basic("alice", "secret"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).contains("Basic YWxpY2U6c2VjcmV0");
    }

    @Test
    @DisplayName("Basic credentials are encoded as UTF-8")
    void testBasicUtf8() {
        OperationResult<String> result = RestAuthenticator.withoutOAuth()
                .authorization("idm", basic("bob", "päss"));

        assertThat(result.getData()).contains("Basic Ym9iOnDDpHNz");
    }

    @Test
    @DisplayName("A target with no credentials is unauthenticated")
    void testNone() {
        OperationResult<String> result = RestAuthenticator.withoutOAuth()
                .authorization("public", basic(null, " "));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isEmpty();
    }

    @Test
    @DisplayName("A username without a password is a configuration error naming the target")
    void testUsernameOnly() {
        OperationResult<String> result = RestAuthenticator.withoutOAuth()
                .authorization("idm", basic("alice", null));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getErrorType()).contains(ErrorType.CONFIGURATION_ERROR);
        assertThat(result.getMessage()).contains("'idm'").contains("username but no password");
    }

    @Test
    @DisplayName("A password without a username is a configuration error that does not echo the password")
    void testPasswordOnly() {
        OperationResult<String> result = RestAuthenticator.withoutOAuth()
                .authorization("idm", basic("", "secret-value"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).contains("password but no username").doesNotContain("secret-value");
    }

    @Test
    @DisplayName("OAuth fetches a client-credentials token and sends it as Bearer")
    void testOAuthBearer() {
        stubToken("tok-1");

        OperationResult<String> result = withRegistration("svc").authorization("api", oauth("svc"));

        assertThat(result.getData()).contains("Bearer tok-1");
        wiremock.verify(postRequestedFor(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("grant_type=client_credentials")));
    }

    @Test
    @DisplayName("OAuth tokens are cached: two calls make one token request")
    void testOAuthCached() {
        stubToken("tok-1");
        RestAuthenticator authenticator = withRegistration("svc");

        authenticator.authorization("api", oauth("svc"));
        OperationResult<String> second = authenticator.authorization("api", oauth("svc"));

        assertThat(second.getData()).contains("Bearer tok-1");
        wiremock.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    @DisplayName("use-oauth without oauth-client-name is a configuration error")
    void testOAuthWithoutName() {
        OperationResult<String> result = withRegistration("svc").authorization("api", oauth(null));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).contains("'api'").contains("oauth-client-name");
    }

    @Test
    @DisplayName("An unknown registration is a configuration error naming it")
    void testUnknownRegistration() {
        OperationResult<String> result = withRegistration("svc").authorization("api", oauth("nope"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).contains("'nope'").contains("not configured");
        wiremock.verify(0, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    @DisplayName("OAuth with no registrations configured at all is a configuration error")
    void testNoRegistrations() {
        OperationResult<String> result = RestAuthenticator.withoutOAuth().authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).contains("no OAuth2 client registrations are configured");
    }

    @Test
    @DisplayName("A rejected token request fails with the OAuth error, never the secret")
    void testTokenRejected() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_client\",\"error_description\":\"bad client\"}")));

        OperationResult<String> result = withRegistration("svc").authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getErrorType()).contains(ErrorType.AUTHENTICATION_ERROR);
        assertThat(result.getMessage())
                .contains("'svc'").contains("'api'").contains("invalid_client")
                .doesNotContain("svc-secret-value");
    }
}
