package com.idm.test.connector.rest.client;

import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Turns a REST target's settings into the {@code Authorization} header its requests carry.
 * <p>
 * {@code use-oauth} wins: a client-credentials token for {@code oauth-client-name}, fetched,
 * cached and renewed by Spring Security's own authorized-client manager (no custom token code).
 * Otherwise a username and password give HTTP basic authentication, and a target with neither
 * is unauthenticated. Anything half-configured is a failure, so a request is never sent with
 * credentials the author did not fully give. A rejection from the token server and a
 * configuration error are final: retrying cannot change them. A server error or an unreachable
 * token server is not: it may succeed on a later try.
 * <p>
 * Never throws, and never puts a password, secret, token or header value in a message or log:
 * messages name the target and the registration, and may also quote Spring's own cause text
 * after {@link TokenFailureText#safe} has masked secrets and URL credentials in it and capped
 * its length.
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
        this.manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
    }

    /** An authenticator for basic and unauthenticated targets only. */
    public static RestAuthenticator withoutOAuth() {
        return new RestAuthenticator(null, null);
    }

    /**
     * The outcome of {@link #authorize}: the header value (null when the target is
     * unauthenticated) and a note, or the problem and its error type.
     *
     * @param header    the full {@code Authorization} value, or null
     * @param note      what kind of authentication the target uses, on success; null on failure
     * @param problem   why no header could be made, or null on success
     * @param errorType the problem's error type, or null on success
     */
    public record Authorization(String header, String note, RestProblem problem, ErrorType errorType) {

        static Authorization of(String header, String note) {
            return new Authorization(header, note, null, null);
        }

        static Authorization failed(ErrorType errorType, RestProblem.Kind kind, Object... values) {
            return new Authorization(null, null, RestProblem.of(kind, values), errorType);
        }

        public boolean isFailure() {
            return problem != null;
        }

        /** Never shows the header value: it carries the credentials. */
        @Override
        public String toString() {
            return "Authorization[header=" + (header == null ? null : "***") + ", note=" + note
                    + ", problem=" + problem + ", errorType=" + errorType + "]";
        }
    }

    /**
     * @param target the target's name, used in messages and as the OAuth principal
     * @param config the target's settings
     * @return the full header value; success with no data when the target is unauthenticated; or
     * a failure whose message names the target and what to fix
     */
    public OperationResult<String> authorization(String target, RestProperties.RestApiConfig config) {
        Authorization authorization = authorize(target, config);
        return authorization.isFailure()
                ? OperationResult.failure(authorization.problem().english(), authorization.errorType())
                : OperationResult.success(authorization.header(), authorization.note());
    }

    /**
     * {@link #authorization} with the failure kept as a {@link RestProblem}, so a caller can word it
     * in any language. Never throws.
     */
    public Authorization authorize(String target, RestProperties.RestApiConfig config) {
        if (config.isUseOAuth()) {
            return bearer(target, config.getOauthClientName());
        }

        boolean hasUsername = present(config.getUsername());
        boolean hasPassword = present(config.getPassword());
        if (!hasUsername && !hasPassword) {
            return Authorization.of(null, "REST target '" + target + "' is unauthenticated");
        }
        if (hasUsername != hasPassword) {
            return Authorization.failed(ErrorType.CONFIGURATION_ERROR,
                    hasUsername ? RestProblem.Kind.BASIC_NO_PASSWORD : RestProblem.Kind.BASIC_NO_USERNAME, target);
        }

        String credentials = config.getUsername() + ":" + config.getPassword();
        return Authorization.of(
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)),
                "REST target '" + target + "' uses basic authentication");
    }

    private Authorization bearer(String target, String registrationId) {
        if (!present(registrationId)) {
            return Authorization.failed(ErrorType.CONFIGURATION_ERROR, RestProblem.Kind.OAUTH_NO_CLIENT_NAME, target);
        }
        if (registrations == null) {
            return Authorization.failed(ErrorType.CONFIGURATION_ERROR, RestProblem.Kind.OAUTH_NO_REGISTRATIONS,
                    target, registrationId);
        }
        ClientRegistration registration = registrations.findByRegistrationId(registrationId);
        if (registration == null) {
            return Authorization.failed(ErrorType.CONFIGURATION_ERROR, RestProblem.Kind.OAUTH_UNKNOWN_REGISTRATION,
                    target, registrationId);
        }

        String clientSecret = registration.getClientSecret();
        String tokenUri = registration.getProviderDetails().getTokenUri();
        if (!isHttpUrl(tokenUri)) {
            return Authorization.failed(ErrorType.CONFIGURATION_ERROR, RestProblem.Kind.OAUTH_BAD_TOKEN_URI,
                    registrationId, target, TokenFailureText.safe(tokenUri, clientSecret));
        }

        try {
            OAuth2AuthorizedClient client = manager.authorize(
                    OAuth2AuthorizeRequest.withClientRegistrationId(registrationId).principal(target).build());
            if (client == null) {
                return Authorization.failed(ErrorType.CONFIGURATION_ERROR, RestProblem.Kind.OAUTH_NO_TOKEN,
                        registrationId, target);
            }
            return Authorization.of("Bearer " + client.getAccessToken().getTokenValue(),
                    "REST target '" + target + "' uses OAuth2 registration '" + registrationId + "'");
        } catch (OAuth2AuthorizationException e) {
            OAuth2Error error = e.getError();
            String description = TokenFailureText.safe(error.getDescription(), clientSecret);
            return description.isEmpty()
                    ? Authorization.failed(tokenFailureType(e), RestProblem.Kind.TOKEN_REJECTED,
                            registrationId, target, error.getErrorCode())
                    : Authorization.failed(tokenFailureType(e), RestProblem.Kind.TOKEN_REJECTED_DESCRIBED,
                            registrationId, target, error.getErrorCode(), description);
        } catch (RuntimeException e) {
            String cause = TokenFailureText.safe(e.getMessage(), clientSecret);
            String type = e.getClass().getSimpleName();
            return cause.isEmpty()
                    ? Authorization.failed(tokenFailureType(e), RestProblem.Kind.TOKEN_FAILED, registrationId, target, type)
                    : Authorization.failed(tokenFailureType(e), RestProblem.Kind.TOKEN_FAILED_CAUSE,
                            registrationId, target, type, cause);
        }
    }

    /**
     * Whether a token URI can be sent to: absolute, http or https in any letter case, with a
     * host. Anything else fails in Spring before a request is made, with a bare exception, so
     * it is caught here as a configuration error instead.
     */
    static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute() && uri.getHost() != null && scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Whether a failed token request may succeed if tried again. A token server that answered
     * with a rejection (any 4xx, including a 400 OAuth error Spring parsed itself) will answer
     * the same way next time: AUTHENTICATION_ERROR. A server error or an unreachable server may
     * recover: SERVER_ERROR, TIMEOUT or NETWORK_ERROR. Anything unidentified counts as a
     * rejection, so an unknown failure ends a wait rather than hammering the token server.
     */
    static ErrorType tokenFailureType(Throwable failure) {
        // Timeout check runs first: a SocketTimeoutException wrapped in a ResourceAccessException
        // must classify as TIMEOUT, not NETWORK_ERROR from the second loop below.
        for (Throwable cause : IdmRestConnector.causeChain(failure)) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return ErrorType.TIMEOUT;
            }
        }
        for (Throwable cause : IdmRestConnector.causeChain(failure)) {
            if (cause instanceof HttpServerErrorException) {
                return ErrorType.SERVER_ERROR;
            }
            if (cause instanceof ResourceAccessException
                    || cause instanceof ConnectException
                    || cause instanceof UnknownHostException) {
                return ErrorType.NETWORK_ERROR;
            }
        }
        return ErrorType.AUTHENTICATION_ERROR;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
