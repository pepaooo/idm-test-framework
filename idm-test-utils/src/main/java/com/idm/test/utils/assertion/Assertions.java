package com.idm.test.utils.assertion;

import com.idm.test.core.model.OperationResult;

/**
 * Entry point for all custom assertions in the IdM Test Framework.
 * Designed to be used with static imports for maximum readability.
 * <p>
 * Usage:
 * <pre>
 * import static com.idm.test.utils.assertion.Assertions.*;
 *
 * assertThat(result).isSuccessful().hasData("expected");
 * assertThat(result).isFailed().hasErrorType(ErrorType.NETWORK_ERROR);
 * </pre>
 */
public final class Assertions {

    private Assertions() {
        // Utility class
    }

    /**
     * Creates an assertion for an OperationResult.
     *
     * @param actual the result to assert
     * @param <T>    the data type
     * @return assertion object
     */
    public static <T> OperationResultAssert<T> assertThat(OperationResult<T> actual) {
        return new OperationResultAssert<>(actual);
    }

    /**
     * Alias for assertThat to avoid conflicts with AssertJ's assertThat.
     *
     * @param actual the result to assert
     * @param <T>    the data type
     * @return assertion object
     */
    public static <T> OperationResultAssert<T> assertResult(OperationResult<T> actual) {
        return assertThat(actual);
    }
}
