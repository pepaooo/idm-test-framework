package com.idm.test.utils.assertion;

import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.assertj.core.api.AbstractAssert;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Custom AssertJ assertion for OperationResult.
 * Provides fluent assertions specifically designed for testing operation results.
 * <p>
 * Usage:
 * <pre>
 * assertThat(result).isSuccessful()
 *                   .hasData("expected-data")
 *                   .hasMessage("Success");
 *
 * assertThat(result).isFailed()
 *                   .hasErrorType(ErrorType.NETWORK_ERROR)
 *                   .isRetryable();
 * </pre>
 */
public class OperationResultAssert<T> extends AbstractAssert<OperationResultAssert<T>, OperationResult<T>> {

    protected OperationResultAssert(OperationResult<T> actual) {
        super(actual, OperationResultAssert.class);
    }

    /**
     * Entry point for assertions on OperationResult.
     *
     * @param actual the result to assert
     * @param <T>    the data type
     * @return assertion object
     */
    public static <T> OperationResultAssert<T> assertThat(OperationResult<T> actual) {
        return new OperationResultAssert<>(actual);
    }

    /**
     * Verifies that the operation was successful.
     *
     * @return this assertion object
     */
    public OperationResultAssert<T> isSuccessful() {
        isNotNull();
        if (!actual.isSuccess()) {
            failWithMessage("Expected operation to be successful but it failed with message: %s",
                    actual.getMessage());
        }
        return this;
    }

    /**
     * Verifies that the operation failed.
     *
     * @return this assertion object
     */
    public OperationResultAssert<T> isFailed() {
        isNotNull();
        if (actual.isSuccess()) {
            failWithMessage("Expected operation to have failed but it was successful");
        }
        return this;
    }

    /**
     * Verifies that the result has data present.
     *
     * @return this assertion object
     */
    public OperationResultAssert<T> hasData() {
        isNotNull();
        isSuccessful();
        if (actual.getData().isEmpty()) {
            failWithMessage("Expected data to be present but it was empty");
        }
        return this;
    }

    /**
     * Verifies that the result has specific data.
     *
     * @param expected the expected data
     * @return this assertion object
     */
    public OperationResultAssert<T> hasData(T expected) {
        hasData();
        T actualData = actual.getData().orElse(null);
        if (!expected.equals(actualData)) {
            failWithMessage("Expected data to be <%s> but was <%s>", expected, actualData);
        }
        return this;
    }

    /**
     * Verifies that the result data satisfies the given requirements.
     *
     * @param requirements consumer that verifies data requirements
     * @return this assertion object
     */
    public OperationResultAssert<T> hasDataSatisfying(Consumer<T> requirements) {
        hasData();
        T actualData = actual.getData().orElseThrow();
        requirements.accept(actualData);
        return this;
    }

    /**
     * Verifies that the result has no data.
     *
     * @return this assertion object
     */
    public OperationResultAssert<T> hasNoData() {
        isNotNull();
        if (actual.getData().isPresent()) {
            failWithMessage("Expected no data but found: %s", actual.getData().get());
        }
        return this;
    }

    /**
     * Verifies that the message contains the expected text.
     *
     * @param expectedMessage expected message or substring
     * @return this assertion object
     */
    public OperationResultAssert<T> hasMessage(String expectedMessage) {
        isNotNull();
        String actualMessage = actual.getMessage();
        if (actualMessage == null || !actualMessage.equals(expectedMessage)) {
            failWithMessage("Expected message <%s> but was <%s>", expectedMessage, actualMessage);
        }
        return this;
    }

    /**
     * Verifies that the message contains the expected substring.
     *
     * @param substring expected substring
     * @return this assertion object
     */
    public OperationResultAssert<T> hasMessageContaining(String substring) {
        isNotNull();
        String actualMessage = actual.getMessage();
        if (actualMessage == null || !actualMessage.contains(substring)) {
            failWithMessage("Expected message to contain <%s> but was <%s>", substring, actualMessage);
        }
        return this;
    }

    /**
     * Verifies that the result has the specified error type.
     *
     * @param expectedType expected error type
     * @return this assertion object
     */
    public OperationResultAssert<T> hasErrorType(ErrorType expectedType) {
        isNotNull();
        isFailed();
        ErrorType actualType = actual.getErrorType().orElse(null);
        if (actualType != expectedType) {
            failWithMessage("Expected error type <%s> but was <%s>", expectedType, actualType);
        }
        return this;
    }

    /**
     * Verifies that the result has an error type present.
     *
     * @return this assertion object
     */
    public OperationResultAssert<T> hasErrorType() {
        isNotNull();
        isFailed();
        if (actual.getErrorType().isEmpty()) {
            failWithMessage("Expected error type to be present but it was empty");
        }
        return this;
    }

    /**
     * Verifies that the result has a throwable present.
     *
     * @return this assertion object
     */
    public OperationResultAssert<T> hasThrowable() {
        isNotNull();
        if (actual.getThrowable().isEmpty()) {
            failWithMessage("Expected throwable to be present but it was empty");
        }
        return this;
    }

    /**
     * Verifies that the result has a throwable of the specified type.
     *
     * @param expectedType expected throwable class
     * @return this assertion object
     */
    public OperationResultAssert<T> hasThrowableInstanceOf(Class<? extends Throwable> expectedType) {
        hasThrowable();
        Throwable actualThrowable = actual.getThrowable().orElseThrow();
        if (!expectedType.isInstance(actualThrowable)) {
            failWithMessage("Expected throwable of type <%s> but was <%s>",
                    expectedType.getName(), actualThrowable.getClass().getName());
        }
        return this;
    }

    /**
     * Verifies that the error is retryable.
     *
     * @return this assertion object
     */
    public OperationResultAssert<T> isRetryable() {
        isNotNull();
        isFailed();
        if (!actual.isRetryable()) {
            failWithMessage("Expected error to be retryable but it was not (error type: %s)",
                    actual.getErrorType().orElse(null));
        }
        return this;
    }

    /**
     * Verifies that the error is not retryable.
     *
     * @return this assertion object
     */
    public OperationResultAssert<T> isNotRetryable() {
        isNotNull();
        isFailed();
        if (actual.isRetryable()) {
            failWithMessage("Expected error not to be retryable but it was (error type: %s)",
                    actual.getErrorType().orElse(null));
        }
        return this;
    }

    /**
     * Verifies that the operation has duration information.
     *
     * @return this assertion object
     */
    public OperationResultAssert<T> hasDuration() {
        isNotNull();
        if (actual.getDuration().isEmpty()) {
            failWithMessage("Expected duration to be present but it was empty");
        }
        return this;
    }

    /**
     * Verifies that the operation completed within the expected duration.
     *
     * @param maxDuration maximum expected duration
     * @return this assertion object
     */
    public OperationResultAssert<T> completedWithin(Duration maxDuration) {
        hasDuration();
        Duration actualDuration = actual.getDuration().orElseThrow();
        if (actualDuration.compareTo(maxDuration) > 0) {
            failWithMessage("Expected operation to complete within <%s> but took <%s>",
                    maxDuration, actualDuration);
        }
        return this;
    }

    /**
     * Verifies that the operation took at least the minimum duration.
     *
     * @param minDuration minimum expected duration
     * @return this assertion object
     */
    public OperationResultAssert<T> tookAtLeast(Duration minDuration) {
        hasDuration();
        Duration actualDuration = actual.getDuration().orElseThrow();
        if (actualDuration.compareTo(minDuration) < 0) {
            failWithMessage("Expected operation to take at least <%s> but took <%s>",
                    minDuration, actualDuration);
        }
        return this;
    }

    /**
     * Verifies that the result has a timestamp.
     *
     * @return this assertion object
     */
    public OperationResultAssert<T> hasTimestamp() {
        isNotNull();
        if (actual.getTimestamp() == null) {
            failWithMessage("Expected timestamp to be present but it was null");
        }
        return this;
    }
}
