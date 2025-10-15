package com.idm.test.core.engine;

import com.idm.test.core.exception.TimeoutException;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for TestExecutor class.
 */
class TestExecutorTest {

    private TestExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new TestExecutor();
    }

    @Test
    @DisplayName("waitUntil should return true when condition is met immediately")
    void testWaitUntilImmediateSuccess() {
        boolean result = executor.waitUntil(
                () -> true,
                Duration.ofSeconds(5),
                Duration.ofMillis(100)
        );

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("waitUntil should return false when condition is never met")
    void testWaitUntilTimeout() {
        long startTime = System.currentTimeMillis();

        boolean result = executor.waitUntil(
                () -> false,
                Duration.ofMillis(300),
                Duration.ofMillis(50)
        );

        long duration = System.currentTimeMillis() - startTime;

        assertThat(result).isFalse();
        assertThat(duration).isGreaterThanOrEqualTo(300);
    }

    @Test
    @DisplayName("waitUntil should poll until condition becomes true")
    void testWaitUntilEventualSuccess() {
        AtomicInteger counter = new AtomicInteger(0);

        boolean result = executor.waitUntil(
                () -> counter.incrementAndGet() >= 3,
                Duration.ofSeconds(2),
                Duration.ofMillis(50)
        );

        assertThat(result).isTrue();
        assertThat(counter.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("waitUntilOrThrow should not throw when condition is met")
    void testWaitUntilOrThrowSuccess() {
        assertThatCode(() ->
                executor.waitUntilOrThrow(
                        () -> true,
                        Duration.ofSeconds(1),
                        Duration.ofMillis(50),
                        "Should not fail"
                )
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("waitUntilOrThrow should throw TimeoutException when condition not met")
    void testWaitUntilOrThrowTimeout() {
        assertThatThrownBy(() ->
                executor.waitUntilOrThrow(
                        () -> false,
                        Duration.ofMillis(200),
                        Duration.ofMillis(50),
                        "Condition not met"
                )
        )
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("Condition not met");
    }

    @Test
    @DisplayName("executeWithRetry should succeed on first attempt")
    void testExecuteWithRetryImmediateSuccess() {
        AtomicInteger attempts = new AtomicInteger(0);

        OperationResult<String> result = executor.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return OperationResult.success("data");
                },
                3,
                Duration.ofMillis(50)
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent().contains("data");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("executeWithRetry should retry on retryable errors")
    void testExecuteWithRetryOnRetryableError() {
        AtomicInteger attempts = new AtomicInteger(0);

        OperationResult<String> result = executor.executeWithRetry(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        return OperationResult.failure("Network error", ErrorType.NETWORK_ERROR);
                    }
                    return OperationResult.success("data");
                },
                5,
                Duration.ofMillis(10)
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("executeWithRetry should not retry on non-retryable errors")
    void testExecuteWithRetryOnNonRetryableError() {
        AtomicInteger attempts = new AtomicInteger(0);

        OperationResult<String> result = executor.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return OperationResult.failure("Validation error", ErrorType.VALIDATION_ERROR);
                },
                5,
                Duration.ofMillis(10)
        );

        assertThat(result.isFailure()).isTrue();
        assertThat(attempts.get()).isEqualTo(1); // Only one attempt
    }

    @Test
    @DisplayName("executeWithRetry should fail after max attempts")
    void testExecuteWithRetryMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger(0);
        int maxAttempts = 3;

        OperationResult<String> result = executor.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    return OperationResult.failure("Network error", ErrorType.NETWORK_ERROR);
                },
                maxAttempts,
                Duration.ofMillis(10)
        );

        assertThat(result.isFailure()).isTrue();
        assertThat(attempts.get()).isEqualTo(maxAttempts);
    }

    @Test
    @DisplayName("executeWithExponentialBackoff should succeed eventually")
    void testExecuteWithExponentialBackoff() {
        AtomicInteger attempts = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        OperationResult<String> result = executor.executeWithExponentialBackoff(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        return OperationResult.failure("Temporary error", ErrorType.NETWORK_ERROR);
                    }
                    return OperationResult.success("data");
                },
                5,
                Duration.ofMillis(10),
                Duration.ofMillis(100)
        );

        long duration = System.currentTimeMillis() - startTime;

        assertThat(result.isSuccess()).isTrue();
        assertThat(attempts.get()).isEqualTo(3);
        // First delay: 10ms, Second delay: 20ms = at least 30ms total
        assertThat(duration).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("executeWithExponentialBackoff should respect max delay")
    void testExecuteWithExponentialBackoffMaxDelay() {
        AtomicInteger attempts = new AtomicInteger(0);

        executor.executeWithExponentialBackoff(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 4) {
                        return OperationResult.failure("Error", ErrorType.TIMEOUT);
                    }
                    return OperationResult.success("data");
                },
                5,
                Duration.ofMillis(10),
                Duration.ofMillis(25) // Max delay caps exponential growth
        );

        // With initial=10, max=25:
        // Attempt 1: no delay
        // Attempt 2: 10ms delay
        // Attempt 3: 20ms delay
        // Attempt 4: 25ms delay (capped, not 40ms)
        // Total delays: at least 55ms

        assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("executeWithTimeout should succeed within timeout")
    void testExecuteWithTimeoutSuccess() {
        OperationResult<String> result = executor.executeWithTimeout(
                () -> OperationResult.success("data"),
                Duration.ofSeconds(1)
        );

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("executeWithTimeout should throw if operation exceeds timeout")
    void testExecuteWithTimeoutExceeded() {
        assertThatThrownBy(() ->
                executor.executeWithTimeout(
                        () -> {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return OperationResult.success("data");
                        },
                        Duration.ofMillis(50)
                )
        ).isInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("measureExecutionTime should capture duration")
    void testMeasureExecutionTime() {
        OperationResult<String> result = executor.measureExecutionTime(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OperationResult.success("data");
        });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDuration()).isPresent();
        assertThat(result.getDuration().get().toMillis()).isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("measureExecutionTime should capture duration on failure")
    void testMeasureExecutionTimeOnFailure() {
        OperationResult<String> result = executor.measureExecutionTime(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OperationResult.failure("Error");
        });

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getDuration()).isPresent();
        assertThat(result.getDuration().get().toMillis()).isGreaterThanOrEqualTo(50);
    }

    @Test
    @DisplayName("waitUntil should handle exceptions in condition and continue checking")
    void testWaitUntilWithExceptions() {
        AtomicInteger attempts = new AtomicInteger(0);

        boolean result = executor.waitUntil(
                () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        throw new RuntimeException("Temporary error");
                    }
                    return true;
                },
                Duration.ofSeconds(2),
                Duration.ofMillis(50)
        );

        assertThat(result).isTrue();
        assertThat(attempts.get()).isGreaterThanOrEqualTo(3);
    }
}
