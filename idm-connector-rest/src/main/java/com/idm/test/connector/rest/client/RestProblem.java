package com.idm.test.connector.rest.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Why a target could not be authenticated, or why a request could not be sent, as a kind plus
 * the values to quote, so a caller can word it in any language. {@link #english()} is the one
 * English wording; the failure messages of {@link RestAuthenticator} and {@link IdmRestConnector}
 * are built on it.
 * <p>
 * The values are already safe to show: target and registration names, masked token-server text
 * ({@link TokenFailureText#safe}), exception type names and transport error text. No password,
 * secret, token or header value is ever one of them.
 */
public record RestProblem(Kind kind, List<Object> values) {

    /** Every problem the authenticator and the connector's exchange methods can report. */
    public enum Kind {
        // authentication: values start with the target, except the OAuth2 registration kinds
        BASIC_NO_PASSWORD, BASIC_NO_USERNAME, OAUTH_NO_CLIENT_NAME, OAUTH_NO_REGISTRATIONS,
        OAUTH_UNKNOWN_REGISTRATION, OAUTH_BAD_TOKEN_URI, OAUTH_NO_TOKEN, TOKEN_REJECTED,
        TOKEN_REJECTED_DESCRIBED, TOKEN_FAILED, TOKEN_FAILED_CAUSE,
        // sending
        UNSUPPORTED_METHOD, REQUEST_FAILED, REQUEST_FAILED_AT
    }

    public RestProblem {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static RestProblem of(Kind kind, Object... values) {
        List<Object> list = new ArrayList<>();
        Collections.addAll(list, values);
        return new RestProblem(kind, list);
    }

    private String text(int index) {
        return String.valueOf(values.get(index));
    }

    /** The English wording, character for character what the connector has always reported. */
    public String english() {
        return switch (kind) {
            case BASIC_NO_PASSWORD -> "REST target '" + text(0) + "' has a username but no password; set both idm.test.rest."
                    + text(0) + ".username and .password, or neither";
            case BASIC_NO_USERNAME -> "REST target '" + text(0) + "' has a password but no username; set both idm.test.rest."
                    + text(0) + ".username and .password, or neither";
            case OAUTH_NO_CLIENT_NAME -> "REST target '" + text(0) + "' sets use-oauth but no oauth-client-name";
            case OAUTH_NO_REGISTRATIONS -> "REST target '" + text(0) + "' uses OAuth2 registration '" + text(1)
                    + "', but no OAuth2 client registrations are configured (spring.security.oauth2.client.registration)";
            case OAUTH_UNKNOWN_REGISTRATION -> "REST target '" + text(0) + "' names OAuth2 client registration '"
                    + text(1) + "', which is not configured under spring.security.oauth2.client.registration";
            case OAUTH_BAD_TOKEN_URI -> "OAuth2 registration '" + text(0) + "' (target '" + text(1)
                    + "') has a token-uri that is not an absolute http(s) URL: '" + text(2)
                    + "'. Fix its provider's token-uri (spring.security.oauth2.client.provider.<name>.token-uri).";
            case OAUTH_NO_TOKEN -> "OAuth2 registration '" + text(0) + "' (target '" + text(1)
                    + "') gave no access token; its authorization-grant-type must be client_credentials";
            case TOKEN_REJECTED -> tokenFailed() + "[" + text(2) + "]";
            case TOKEN_REJECTED_DESCRIBED -> tokenFailed() + "[" + text(2) + "] " + text(3);
            case TOKEN_FAILED -> tokenFailed() + text(2);
            case TOKEN_FAILED_CAUSE -> tokenFailed() + text(2) + ": " + text(3);
            case UNSUPPORTED_METHOD -> "Unsupported HTTP method: " + text(0);
            case REQUEST_FAILED -> "Request failed: " + text(0);
            case REQUEST_FAILED_AT -> "Request failed: " + text(0) + " " + text(1) + ": " + text(2);
        };
    }

    private String tokenFailed() {
        return "OAuth2 token request for registration '" + text(0) + "' (target '" + text(1) + "') failed: ";
    }
}
