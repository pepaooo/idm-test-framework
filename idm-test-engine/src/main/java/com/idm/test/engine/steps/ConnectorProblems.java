package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestProblem;
import com.idm.test.connector.soap.client.SoapProblem;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.Message;

import java.util.function.Function;

/**
 * Words the REST and SOAP connectors' problems by key, so they read in the run's language. The
 * service's or transport's own text inside a problem stays an argument, unchanged.
 */
final class ConnectorProblems {

    private ConnectorProblems() {
    }

    static Message of(RestProblem problem) {
        return Message.of(key(problem.kind()), problem.values().toArray());
    }

    static Message of(SoapProblem problem) {
        return Message.of(key(problem.kind()), problem.values().toArray());
    }

    /**
     * A failed REST exchange's message as an argument: the keyed problem, or the connector's own
     * text when the problem cannot be rebuilt word for word from the result.
     */
    static Object request(String method, String url, OperationResult<?> failed) {
        RestProblem problem = IdmRestConnector.problem(method, url, failed);
        return keyedOr(problem == null ? null : of(problem), failed.getMessage());
    }

    /**
     * A failed SOAP call's message as an argument, rebuilt from its throwable by {@code problem}
     * (one of the {@link SoapProblem} factories); the connector's own text when that is not possible.
     */
    static Object soap(OperationResult<?> failed, Function<Throwable, SoapProblem> problem) {
        Message keyed = failed.getThrowable().map(problem).map(ConnectorProblems::of).orElse(null);
        return keyedOr(keyed, failed.getMessage());
    }

    /**
     * {@code keyed} when its English is exactly {@code english}, else {@code english}: a message
     * argument that keeps today's English whatever happens.
     */
    static Object keyedOr(Message keyed, String english) {
        return keyed != null && Messages.english(keyed).equals(english) ? keyed : english;
    }

    private static String key(RestProblem.Kind kind) {
        return switch (kind) {
            case BASIC_NO_PASSWORD -> "step.rest.auth.basicNoPassword";
            case BASIC_NO_USERNAME -> "step.rest.auth.basicNoUsername";
            case OAUTH_NO_CLIENT_NAME -> "step.rest.auth.noClientName";
            case OAUTH_NO_REGISTRATIONS -> "step.rest.auth.noRegistrations";
            case OAUTH_UNKNOWN_REGISTRATION -> "step.rest.auth.unknownRegistration";
            case OAUTH_BAD_TOKEN_URI -> "step.rest.auth.badTokenUri";
            case OAUTH_NO_TOKEN -> "step.rest.auth.noToken";
            case TOKEN_REJECTED -> "step.rest.auth.tokenRejected";
            case TOKEN_REJECTED_DESCRIBED -> "step.rest.auth.tokenRejectedDescribed";
            case TOKEN_FAILED -> "step.rest.auth.tokenFailed";
            case TOKEN_FAILED_CAUSE -> "step.rest.auth.tokenFailedCause";
            case UNSUPPORTED_METHOD -> "step.rest.send.unsupportedMethod";
            case REQUEST_FAILED -> "step.rest.send.failed";
            case REQUEST_FAILED_AT -> "step.rest.send.failedAt";
        };
    }

    private static String key(SoapProblem.Kind kind) {
        return switch (kind) {
            case ROLE_FAULT -> "step.roles.fault";
            case ROLE_ASSIGN_FAILED -> "step.roles.assignFailed";
            case ROLE_REMOVE_FAILED -> "step.roles.removeFailed";
            case RESOURCE_GRANT_FAILED -> "step.resources.grantFailed";
            case RESOURCE_REVOKE_FAILED -> "step.resources.revokeFailed";
        };
    }
}
