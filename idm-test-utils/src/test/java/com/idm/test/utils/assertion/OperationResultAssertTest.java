package com.idm.test.utils.assertion;

import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.idm.test.utils.assertion.Assertions.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for OperationResultAssert custom assertions.
 */
class OperationResultAssertTest {

    @Test
    @DisplayName("Should pass when operation is successful")
    void testIsSuccessful() {
        OperationResult<String> result = OperationResult.success("data");

        assertThat(result).isSuccessful();
    }

    @Test
    @DisplayName("Should fail when asserting successful on failed result")
    void testIsSuccessfulFails() {
        OperationResult<String> result = OperationResult.failure("error");

        assertThatThrownBy(() -> assertThat(result).isSuccessful())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected operation to be successful");
    }

    @Test
    @DisplayName("Should pass when operation failed")
    void testIsFailed() {
        OperationResult<String> result = OperationResult.failure("error");

        assertThat(result).isFailed();
    }

    @Test
    @DisplayName("Should pass when result has data")
    void testHasData() {
        OperationResult<String> result = OperationResult.success("test-data");

        assertThat(result).hasData();
    }

    @Test
    @DisplayName("Should pass when data matches expected")
    void testHasDataWithValue() {
        OperationResult<String> result = OperationResult.success("test-data");

        assertThat(result).hasData("test-data");
    }

    @Test
    @DisplayName("Should pass when data satisfies requirements")
    void testHasDataSatisfying() {
        OperationResult<String> result = OperationResult.success("TEST");

        assertThat(result).hasDataSatisfying(data -> {
            org.assertj.core.api.Assertions.assertThat(data).isEqualTo("TEST");
            org.assertj.core.api.Assertions.assertThat(data).hasSize(4);
        });
    }

    @Test
    @DisplayName("Should pass when result has no data")
    void testHasNoData() {
        OperationResult<String> result = OperationResult.failure("error");

        assertThat(result).hasNoData();
    }

    @Test
    @DisplayName("Should pass when message matches")
    void testHasMessage() {
        OperationResult<String> result = OperationResult.success("data", "Success!");

        assertThat(result).hasMessage("Success!");
    }

    @Test
    @DisplayName("Should pass when message contains substring")
    void testHasMessageContaining() {
        OperationResult<String> result = OperationResult.success("data", "Operation completed successfully");

        assertThat(result).hasMessageContaining("completed");
    }

    @Test
    @DisplayName("Should pass when error type matches")
    void testHasErrorType() {
        OperationResult<String> result = OperationResult.failure("error", ErrorType.NETWORK_ERROR);

        assertThat(result).hasErrorType(ErrorType.NETWORK_ERROR);
    }

    @Test
    @DisplayName("Should pass when error type is present")
    void testHasErrorTypePresent() {
        OperationResult<String> result = OperationResult.failure("error", ErrorType.TIMEOUT);

        assertThat(result).hasErrorType();
    }

    @Test
    @DisplayName("Should pass when throwable is present")
    void testHasThrowable() {
        RuntimeException exception = new RuntimeException("Test error");
        OperationResult<String> result = OperationResult.failure(exception);

        assertThat(result).hasThrowable();
    }

    @Test
    @DisplayName("Should pass when throwable is instance of expected type")
    void testHasThrowableInstanceOf() {
        RuntimeException exception = new RuntimeException("Test error");
        OperationResult<String> result = OperationResult.failure(exception);

        assertThat(result).hasThrowableInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Should pass when error is retryable")
    void testIsRetryable() {
        OperationResult<String> result = OperationResult.failure("error", ErrorType.NETWORK_ERROR);

        assertThat(result).isRetryable();
    }

    @Test
    @DisplayName("Should pass when error is not retryable")
    void testIsNotRetryable() {
        OperationResult<String> result = OperationResult.failure("error", ErrorType.VALIDATION_ERROR);

        assertThat(result).isNotRetryable();
    }

    @Test
    @DisplayName("Should pass when duration is present")
    void testHasDuration() {
        OperationResult<String> result = OperationResult.<String>builder()
                .success(true)
                .data("test")
                .duration(Duration.ofMillis(100))
                .build();

        assertThat(result).hasDuration();
    }

    @Test
    @DisplayName("Should pass when operation completed within expected duration")
    void testCompletedWithin() {
        OperationResult<String> result = OperationResult.<String>builder()
                .success(true)
                .data("test")
                .duration(Duration.ofMillis(50))
                .build();

        assertThat(result).completedWithin(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("Should pass when operation took at least minimum duration")
    void testTookAtLeast() {
        OperationResult<String> result = OperationResult.<String>builder()
                .success(true)
                .data("test")
                .duration(Duration.ofMillis(100))
                .build();

        assertThat(result).tookAtLeast(Duration.ofMillis(50));
    }

    @Test
    @DisplayName("Should pass when timestamp is present")
    void testHasTimestamp() {
        OperationResult<String> result = OperationResult.success("data");

        assertThat(result).hasTimestamp();
    }

    @Test
    @DisplayName("Should support method chaining")
    void testMethodChaining() {
        OperationResult<String> result = OperationResult.<String>builder()
                .success(true)
                .data("test-data")
                .message("Success")
                .duration(Duration.ofMillis(100))
                .build();

        assertThat(result)
                .isSuccessful()
                .hasData("test-data")
                .hasMessage("Success")
                .hasDuration()
                .completedWithin(Duration.ofMillis(200))
                .hasTimestamp();
    }

    @Test
    @DisplayName("Should support assertResult alias")
    void testAssertResultAlias() {
        OperationResult<String> result = OperationResult.success("data");

        assertResult(result).isSuccessful().hasData();
    }
}
