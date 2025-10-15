package com.idm.test.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for OperationResult class.
 */
class OperationResultTest {

    @Test
    @DisplayName("Should create successful result with data")
    void testSuccessWithData() {
        String data = "test-data";
        OperationResult<String> result = OperationResult.success(data);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isFailure()).isFalse();
        assertThat(result.getData()).isPresent().contains(data);
        assertThat(result.getMessage()).isNotNull();
        assertThat(result.getErrorType()).isEmpty();
    }

    @Test
    @DisplayName("Should create successful result with custom message")
    void testSuccessWithMessage() {
        String data = "test-data";
        String message = "Custom success message";
        OperationResult<String> result = OperationResult.success(data, message);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo(message);
        assertThat(result.getData()).isPresent().contains(data);
    }

    @Test
    @DisplayName("Should create failed result with message")
    void testFailureWithMessage() {
        String message = "Operation failed";
        OperationResult<String> result = OperationResult.failure(message);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isFailure()).isTrue();
        assertThat(result.getData()).isEmpty();
        assertThat(result.getMessage()).isEqualTo(message);
        assertThat(result.getErrorType()).isPresent().contains(ErrorType.UNKNOWN_ERROR);
    }

    @Test
    @DisplayName("Should create failed result with error type")
    void testFailureWithErrorType() {
        String message = "Network error";
        ErrorType errorType = ErrorType.NETWORK_ERROR;
        OperationResult<String> result = OperationResult.failure(message, errorType);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getErrorType()).isPresent().contains(errorType);
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("Should create failed result from exception")
    void testFailureFromException() {
        RuntimeException exception = new RuntimeException("Test exception");
        OperationResult<String> result = OperationResult.failure(exception);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Test exception");
        assertThat(result.getThrowable()).isPresent().contains(exception);
    }

    @Test
    @DisplayName("Should get data or throw on success")
    void testGetDataOrThrow() {
        String data = "test-data";
        OperationResult<String> result = OperationResult.success(data);

        assertThatCode(() -> result.getDataOrThrow()).doesNotThrowAnyException();
        assertThat(result.getDataOrThrow()).isEqualTo(data);
    }

    @Test
    @DisplayName("Should throw on getDataOrThrow when failed")
    void testGetDataOrThrowOnFailure() {
        OperationResult<String> result = OperationResult.failure("Error");

        assertThatThrownBy(() -> result.getDataOrThrow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Operation failed");
    }

    @Test
    @DisplayName("Should get data or default")
    void testGetDataOrDefault() {
        String defaultValue = "default";

        OperationResult<String> success = OperationResult.success("actual");
        assertThat(success.getDataOrDefault(defaultValue)).isEqualTo("actual");

        OperationResult<String> failure = OperationResult.failure("Error");
        assertThat(failure.getDataOrDefault(defaultValue)).isEqualTo(defaultValue);
    }

    @Test
    @DisplayName("Should execute ifSuccess consumer")
    void testIfSuccess() {
        AtomicInteger counter = new AtomicInteger(0);
        String data = "test";

        OperationResult<String> success = OperationResult.success(data);
        success.ifSuccess(d -> counter.incrementAndGet());

        assertThat(counter.get()).isEqualTo(1);

        OperationResult<String> failure = OperationResult.failure("Error");
        failure.ifSuccess(d -> counter.incrementAndGet());

        assertThat(counter.get()).isEqualTo(1); // Should not increment
    }

    @Test
    @DisplayName("Should execute ifFailure consumer")
    void testIfFailure() {
        AtomicInteger counter = new AtomicInteger(0);

        OperationResult<String> failure = OperationResult.failure("Error");
        failure.ifFailure(msg -> counter.incrementAndGet());

        assertThat(counter.get()).isEqualTo(1);

        OperationResult<String> success = OperationResult.success("data");
        success.ifFailure(msg -> counter.incrementAndGet());

        assertThat(counter.get()).isEqualTo(1); // Should not increment
    }

    @Test
    @DisplayName("Should map successful result")
    void testMap() {
        OperationResult<String> result = OperationResult.success("test");
        OperationResult<Integer> mapped = result.map(String::length);

        assertThat(mapped.isSuccess()).isTrue();
        assertThat(mapped.getData()).isPresent().contains(4);
    }

    @Test
    @DisplayName("Should propagate failure on map")
    void testMapOnFailure() {
        OperationResult<String> result = OperationResult.failure("Error", ErrorType.NETWORK_ERROR);
        OperationResult<Integer> mapped = result.map(String::length);

        assertThat(mapped.isFailure()).isTrue();
        assertThat(mapped.getMessage()).contains("Error");
        assertThat(mapped.getErrorType()).isPresent().contains(ErrorType.NETWORK_ERROR);
    }

    @Test
    @DisplayName("Should handle exception in map function")
    void testMapWithException() {
        OperationResult<String> result = OperationResult.success("test");
        OperationResult<Integer> mapped = result.map(s -> {
            throw new RuntimeException("Mapping error");
        });

        assertThat(mapped.isFailure()).isTrue();
        assertThat(mapped.getMessage()).contains("Mapping failed");
    }

    @Test
    @DisplayName("Should determine retryable based on error type")
    void testIsRetryable() {
        OperationResult<String> networkError =
                OperationResult.failure("Error", ErrorType.NETWORK_ERROR);
        assertThat(networkError.isRetryable()).isTrue();

        OperationResult<String> validationError =
                OperationResult.failure("Error", ErrorType.VALIDATION_ERROR);
        assertThat(validationError.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("Should build result with all fields")
    void testBuilder() {
        String data = "test-data";
        String message = "Test message";
        ErrorType errorType = ErrorType.NETWORK_ERROR;
        Duration duration = Duration.ofMillis(100);

        OperationResult<String> result = OperationResult.<String>builder()
                .success(true)
                .data(data)
                .message(message)
                .errorType(errorType)
                .duration(duration)
                .build();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent().contains(data);
        assertThat(result.getMessage()).isEqualTo(message);
        assertThat(result.getErrorType()).isPresent().contains(errorType);
        assertThat(result.getDuration()).isPresent().contains(duration);
        assertThat(result.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("Should generate meaningful toString")
    void testToString() {
        OperationResult<String> result = OperationResult.success("data", "Success!");
        String str = result.toString();

        assertThat(str)
                .contains("success=true")
                .contains("data=data")
                .contains("message='Success!'");
    }

    @Test
    @DisplayName("Should chain ifSuccess and ifFailure")
    void testChaining() {
        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger failureCounter = new AtomicInteger(0);

        OperationResult<String> result = OperationResult.success("data")
                .ifSuccess(d -> successCounter.incrementAndGet())
                .ifFailure(msg -> failureCounter.incrementAndGet());

        assertThat(successCounter.get()).isEqualTo(1);
        assertThat(failureCounter.get()).isEqualTo(0);
    }
}
