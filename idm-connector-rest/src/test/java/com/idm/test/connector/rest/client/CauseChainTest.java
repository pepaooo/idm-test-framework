package com.idm.test.connector.rest.client;

import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Walking an exception's causes ends even when the chain loops back on itself (A caused by B caused
 * by A), for the error type, the failure message and the token failure type alike.
 */
@DisplayName("REST connector cause chains")
class CauseChainTest {

    private static final Duration QUICKLY = Duration.ofSeconds(5);

    /** {@code outer} caused by {@code inner} caused by {@code outer}. */
    private static RuntimeException loop(RuntimeException outer, Throwable inner) {
        outer.initCause(inner);
        inner.initCause(outer);
        return outer;
    }

    @Test
    @DisplayName("Should classify an exception whose causes loop, without walking forever")
    void testCyclicChainErrorType() {
        RuntimeException plain = loop(new RuntimeException("boom"), new IllegalStateException("inner"));
        RuntimeException timedOut = loop(new RuntimeException("wrapper"), new SocketTimeoutException("slow"));

        assertTimeoutPreemptively(QUICKLY, () -> {
            assertThat(IdmRestConnector.determineErrorType(plain)).isEqualTo(ErrorType.UNKNOWN_ERROR);
            assertThat(IdmRestConnector.determineErrorType(timedOut)).isEqualTo(ErrorType.TIMEOUT);
            assertThat(RestAuthenticator.tokenFailureType(timedOut)).isEqualTo(ErrorType.TIMEOUT);
            assertThat(RestAuthenticator.tokenFailureType(plain)).isEqualTo(ErrorType.AUTHENTICATION_ERROR);
        });
    }

    @Test
    @DisplayName("Should describe a failed request whose causes loop, naming the most specific one")
    void testCyclicChainProblem() {
        RuntimeException failure = loop(new RuntimeException("outer"), new IllegalStateException("inner"));
        OperationResult<Object> failed = OperationResult.failure(failure);

        RestProblem problem = assertTimeoutPreemptively(QUICKLY,
                () -> IdmRestConnector.problem("GET", "http://localhost/x", failed));

        assertThat(problem.english()).contains("inner");
    }

    @Test
    @DisplayName("Should list each exception of a looping chain once, outermost first")
    void testCauseChainEndsAtRepeat() {
        IllegalStateException inner = new IllegalStateException("inner");
        RuntimeException outer = loop(new RuntimeException("outer"), inner);

        assertThat(IdmRestConnector.causeChain(outer)).containsExactly(outer, inner);
    }
}
