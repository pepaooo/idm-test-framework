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
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.client.ResourceAccessException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RestAuthenticator")
class RestAuthenticatorTest {

    private static WireMockServer wiremock;

    @BeforeAll
    static void start() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort().globalTemplating(true));
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

    private static RestAuthenticator withRegistration(String id, String secret, ClientAuthenticationMethod method) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(id)
                .clientId("svc-client")
                .clientSecret(secret)
                .clientAuthenticationMethod(method)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(wiremock.baseUrl() + "/oauth/token")
                .build();
        return new RestAuthenticator(new InMemoryClientRegistrationRepository(registration), null);
    }

    private static RestAuthenticator withTokenUri(String tokenUri) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("svc")
                .clientId("svc-client")
                .clientSecret("svc-secret-value")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokenUri)
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
        // Spring's default OAuth2 error handling only parses the {"error": ...} body on a 400
        // response; a 401 (also valid per RFC 6749 §5.2) falls back to a generic
        // "[invalid_token_response] ... 401 Unauthorized: [no body]" message. We use Spring's
        // default handling as-is, so we assert on what it actually reports here: the target, the
        // registration and the status, not the parsed OAuth2 error code.
        assertThat(result.getMessage())
                .contains("'svc'").contains("'api'").contains("401").contains("invalid_token_response")
                .doesNotContain("svc-secret-value");
    }

    @Test
    @DisplayName("A 400 token rejection surfaces the parsed OAuth2 error")
    void testTokenRejectedWith400() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_client\",\"error_description\":\"bad client\"}")));

        OperationResult<String> result = withRegistration("svc").authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getErrorType()).contains(ErrorType.AUTHENTICATION_ERROR);
        assertThat(result.getMessage())
                .contains("'svc'").contains("'api'").contains("invalid_client")
                .doesNotContain("svc-secret-value");
    }

    @Test
    @DisplayName("A 5xx from the token server is a SERVER_ERROR, which may pass later")
    void testTokenServerError() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(503)));

        OperationResult<String> result = withRegistration("svc").authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getErrorType()).contains(ErrorType.SERVER_ERROR);
        assertThat(result.getMessage()).contains("'svc'").doesNotContain("svc-secret-value");
    }

    @Test
    @DisplayName("An unreachable token server is a NETWORK_ERROR, which may pass later")
    void testTokenServerUnreachable() {
        OperationResult<String> result = withTokenUri("http://localhost:1/oauth/token")
                .authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getErrorType()).contains(ErrorType.NETWORK_ERROR);
    }

    @Test
    @DisplayName("A timeout wrapped in a ResourceAccessException is a TIMEOUT, not a NETWORK_ERROR")
    void testTokenFailureTypeTimeout() {
        assertThat(RestAuthenticator.tokenFailureType(
                new ResourceAccessException("slow", new java.net.SocketTimeoutException("read timed out"))))
                .isEqualTo(ErrorType.TIMEOUT);
        assertThat(RestAuthenticator.tokenFailureType(
                new ResourceAccessException("down", new java.net.ConnectException("refused"))))
                .isEqualTo(ErrorType.NETWORK_ERROR);
    }

    @Test
    @DisplayName("A malformed or non-http token-uri is a configuration error, caught before any request")
    void testInvalidTokenUri() {
        for (String uri : new String[] {"not-a-url", "ftp://example.org/token", "http://bad host/token"}) {
            OperationResult<String> result = withTokenUri(uri).authorization("api", oauth("svc"));

            assertThat(result.isFailure()).as(uri).isTrue();
            assertThat(result.getErrorType()).as(uri).contains(ErrorType.CONFIGURATION_ERROR);
            assertThat(result.getMessage()).as(uri)
                    .startsWith("OAuth2 registration 'svc' (target 'api') has a token-uri that is not an absolute http(s) URL: '")
                    .endsWith("'. Fix its provider's token-uri (spring.security.oauth2.client.provider.<name>.token-uri).")
                    .doesNotContain("failed:");
        }
    }

    @Test
    @DisplayName("An invalid-scheme token-uri pointing at WireMock never sends a request")
    void testInvalidTokenUriNeverRequestsWireMock() {
        String invalidUri = wiremock.baseUrl().replace("http://", "ftp://") + "/oauth/token";

        OperationResult<String> result = withTokenUri(invalidUri).authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getErrorType()).contains(ErrorType.CONFIGURATION_ERROR);
        wiremock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Credentials in an invalid token-uri are masked in the message")
    void testInvalidTokenUriMasksUserInfo() {
        OperationResult<String> result = withTokenUri("ftp://user:pa55@example.org/token")
                .authorization("api", oauth("svc"));

        assertThat(result.getErrorType()).contains(ErrorType.CONFIGURATION_ERROR);
        assertThat(result.getMessage()).contains("ftp://****@example.org/token").doesNotContain("pa55");
    }

    @Test
    @DisplayName("isHttpUrl accepts absolute http(s) URLs with a host, in any scheme case")
    void testIsHttpUrl() {
        assertThat(RestAuthenticator.isHttpUrl("https://idp.example/oauth/token")).isTrue();
        assertThat(RestAuthenticator.isHttpUrl("HTTP://localhost:8080/token")).isTrue();
        assertThat(RestAuthenticator.isHttpUrl(null)).isFalse();
        assertThat(RestAuthenticator.isHttpUrl(" ")).isFalse();
        assertThat(RestAuthenticator.isHttpUrl("/relative/token")).isFalse();
        assertThat(RestAuthenticator.isHttpUrl("http:///no-host")).isFalse();
    }

    @Test
    @DisplayName("A token server echoing the client secret never gets it into the message")
    void testEchoedSecretMasked() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(403)
                .withHeader("Content-Type", "text/plain")
                .withBody("forbidden for client_secret=svc-secret-value")));

        OperationResult<String> result = withRegistration("svc").authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).doesNotContain("svc-secret-value").contains("****");
    }

    @Test
    @DisplayName("Credentials in an unreachable token URL never reach the message")
    void testUnreachableUserInfoMasked() {
        OperationResult<String> result = withTokenUri("https://user:pa55@no-such-host.invalid/token")
                .authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).doesNotContain("pa55").contains("https://****@no-such-host.invalid");
    }

    @Test
    @DisplayName("An echoed request body masks the URL-encoded secret (client_secret_post)")
    void testEchoedSecretPostMasked() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(403)
                .withHeader("Content-Type", "text/plain")
                .withBody("echo {{request.body}} auth {{request.headers.Authorization}}")));

        OperationResult<String> result = withRegistration("svc", "Az~secret+val/ue=",
                ClientAuthenticationMethod.CLIENT_SECRET_POST).authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage())
                .doesNotContain("Az~secret+val/ue=")
                .doesNotContain("Az%7Esecret%2Bval%2Fue%3D")
                .doesNotContainPattern("Basic [A-Za-z0-9+/=]{8,}");
        wiremock.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    @DisplayName("An echoed Authorization header masks the client secret (client_secret_basic)")
    void testEchoedSecretBasicMasked() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(403)
                .withHeader("Content-Type", "text/plain")
                .withBody("echo {{request.body}} auth {{request.headers.Authorization}}")));

        OperationResult<String> result = withRegistration("svc", "Az~secret+val/ue=",
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage())
                .doesNotContain("Az~secret+val/ue=")
                .doesNotContain("Az%7Esecret%2Bval%2Fue%3D")
                .doesNotContainPattern("Basic [A-Za-z0-9+/=]{8,}");
        wiremock.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
    }

    @Test
    @DisplayName("A long error body is capped")
    void testLongBodyCapped() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(405)
                .withHeader("Content-Type", "text/html")
                .withBody("<html>" + "x".repeat(2000) + "</html>")));

        OperationResult<String> result = withRegistration("svc").authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).endsWith("…");
        assertThat(result.getMessage().length()).isLessThan(450);
    }
}
