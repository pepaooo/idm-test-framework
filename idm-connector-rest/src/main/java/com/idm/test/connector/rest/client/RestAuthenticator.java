package com.idm.test.connector.rest.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Turns a REST target's settings into the {@code Authorization} header its requests carry.
 * <p>
 * {@code use-oauth} wins: a client-credentials token for {@code oauth-client-name}, fetched,
 * cached and renewed by Spring Security's own authorized-client manager (no custom token code).
 * Otherwise a username and password give HTTP basic authentication, and a target with neither
 * is unauthenticated. Anything half-configured is a failure, so a request is never sent with
 * credentials the author did not fully give.
 * <p>
 * Never throws, and never puts a password, secret, token or header value in a message or log:
 * messages name the target and the registration only.
 */
public class RestAuthenticator {

    private final ClientRegistrationRepository registrations;
    private final AuthorizedClientServiceOAuth2AuthorizedClientManager manager;

    /**
     * @param registrations the OAuth2 client registrations, or null when none are configured
     * @param clients       where authorized clients (tokens) are kept, or null for an in-memory store
     */
    public RestAuthenticator(ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
        this.registrations = registrations;
        if (registrations == null) {
            this.manager = null;
            return;
        }
        OAuth2AuthorizedClientService service =
                clients != null ? clients : new InMemoryOAuth2AuthorizedClientService(registrations);
        this.manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, service);
        this.manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(grant -> grant.accessTokenResponseClient(tokenResponseClient()))
                .build());
    }

    /**
     * A client-credentials token response client whose error handling covers any HTTP error
     * status, not only {@code 400}.
     * <p>
     * Deviation from a Spring Security default: {@code OAuth2ErrorResponseErrorHandler}, which
     * {@link RestClientClientCredentialsTokenResponseClient} uses out of the box, only parses the
     * {@code {"error": "..."}} body into an {@link OAuth2Error} when the response status is
     * exactly {@code 400 Bad Request}; any other error status (authorization servers commonly
     * answer a rejected {@code client_secret} with {@code 401}) falls through to Spring's generic
     * {@code DefaultResponseErrorHandler}, which discards the body and produces an opaque
     * {@code invalid_token_response} error. That would surface as an unhelpful message here and,
     * more importantly, would swallow the very detail (e.g. {@code invalid_client}) the target's
     * author needs to fix the registration. {@link #tokenErrorHandler()} parses the body as an
     * OAuth2 error for every error status, still via Spring's own token-response client.
     */
    private static RestClientClientCredentialsTokenResponseClient tokenResponseClient() {
        RestClientClientCredentialsTokenResponseClient client = new RestClientClientCredentialsTokenResponseClient();
        client.setRestClient(RestClient.builder()
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(tokenErrorHandler())
                .build());
        return client;
    }

    private static ResponseErrorHandler tokenErrorHandler() {
        return new ResponseErrorHandler() {
            private final ObjectMapper mapper = new ObjectMapper();
            private final ResponseErrorHandler fallback = new DefaultResponseErrorHandler();

            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return fallback.hasError(response);
            }

            @Override
            public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
                throw new OAuth2AuthorizationException(readError(response));
            }

            private OAuth2Error readError(ClientHttpResponse response) throws IOException {
                byte[] body = response.getBody().readAllBytes();
                try {
                    JsonNode node = mapper.readTree(body);
                    if (node.hasNonNull("error")) {
                        String code = node.get("error").asText();
                        String description = node.hasNonNull("error_description")
                                ? node.get("error_description").asText() : null;
                        String uri = node.hasNonNull("error_uri") ? node.get("error_uri").asText() : null;
                        return new OAuth2Error(code, description, uri);
                    }
                } catch (IOException notJson) {
                    // fall through: body is not an OAuth2 error response
                }
                return new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR,
                        response.getStatusCode() + " " + response.getStatusText(), null);
            }
        };
    }

    /** An authenticator for basic and unauthenticated targets only. */
    public static RestAuthenticator withoutOAuth() {
        return new RestAuthenticator(null, null);
    }

    /**
     * @param target the target's name, used in messages and as the OAuth principal
     * @param config the target's settings
     * @return the full header value; success with no data when the target is unauthenticated; or
     * a failure whose message names the target and what to fix
     */
    public OperationResult<String> authorization(String target, RestProperties.RestApiConfig config) {
        if (config.isUseOAuth()) {
            return bearer(target, config.getOauthClientName());
        }

        boolean hasUsername = present(config.getUsername());
        boolean hasPassword = present(config.getPassword());
        if (!hasUsername && !hasPassword) {
            return OperationResult.success(null, "REST target '" + target + "' is unauthenticated");
        }
        if (hasUsername != hasPassword) {
            return OperationResult.failure("REST target '" + target + "' has a "
                    + (hasUsername ? "username but no password" : "password but no username")
                    + "; set both idm.test.rest." + target + ".username and .password, or neither",
                    ErrorType.CONFIGURATION_ERROR);
        }

        String credentials = config.getUsername() + ":" + config.getPassword();
        return OperationResult.success(
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)),
                "REST target '" + target + "' uses basic authentication");
    }

    private OperationResult<String> bearer(String target, String registrationId) {
        if (!present(registrationId)) {
            return OperationResult.failure("REST target '" + target + "' sets use-oauth but no oauth-client-name",
                    ErrorType.CONFIGURATION_ERROR);
        }
        if (registrations == null) {
            return OperationResult.failure("REST target '" + target + "' uses OAuth2 registration '" + registrationId
                    + "', but no OAuth2 client registrations are configured (spring.security.oauth2.client.registration)",
                    ErrorType.CONFIGURATION_ERROR);
        }
        if (registrations.findByRegistrationId(registrationId) == null) {
            return OperationResult.failure("REST target '" + target + "' names OAuth2 client registration '"
                    + registrationId + "', which is not configured under spring.security.oauth2.client.registration",
                    ErrorType.CONFIGURATION_ERROR);
        }

        String failed = "OAuth2 token request for registration '" + registrationId + "' (target '" + target + "') failed: ";
        try {
            OAuth2AuthorizedClient client = manager.authorize(
                    OAuth2AuthorizeRequest.withClientRegistrationId(registrationId).principal(target).build());
            if (client == null) {
                return OperationResult.failure("OAuth2 registration '" + registrationId + "' (target '" + target
                        + "') gave no access token; its authorization-grant-type must be client_credentials",
                        ErrorType.CONFIGURATION_ERROR);
            }
            return OperationResult.success("Bearer " + client.getAccessToken().getTokenValue(),
                    "REST target '" + target + "' uses OAuth2 registration '" + registrationId + "'");
        } catch (OAuth2AuthorizationException e) {
            OAuth2Error error = e.getError();
            String description = error.getDescription() == null ? "" : " " + error.getDescription();
            return OperationResult.failure(failed + "[" + error.getErrorCode() + "]" + description,
                    ErrorType.AUTHENTICATION_ERROR);
        } catch (RuntimeException e) {
            return OperationResult.failure(failed + e.getClass().getSimpleName(), ErrorType.AUTHENTICATION_ERROR);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
