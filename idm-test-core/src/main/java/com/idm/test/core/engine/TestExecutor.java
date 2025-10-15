package com.idm.test.core.engine;

import com.idm.test.core.exception.TimeoutException;
import com.idm.test.core.model.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Test execution utilities for retry and wait logic.
 * Essential for handling async IdM provisioning operations.
 * <p>
 * This component provides:
 * - Polling with timeout for async operations
 * - Retry logic with configurable strategies
 * - Execution timing and logging
 */
@Component
public class TestExecutor {

    private static final Logger log = LoggerFactory.getLogger(TestExecutor.class);

    /**
     * Wait until a condition is met or timeout occurs.
     * <p>
     * This is the primary method for waiting on async IdM provisioning.
     * It polls the condition at regular intervals until it returns true
     * or the timeout is reached.
     *
     * @param condition    the condition to check (should return true when complete)
     * @param timeout      maximum time to wait
     * @param pollInterval time between condition checks
     * @return true if condition was met, false if timeout occurred
     */
    public boolean waitUntil(
            Supplier<Boolean> condition,
            Duration timeout,
            Duration pollInterval) {

        log.debug("Starting waitUntil: timeout={}, pollInterval={}", timeout, pollInterval);

        long startTime = System.currentTimeMillis();
        long endTime = startTime + timeout.toMillis();
        int attempt = 0;

        while (System.currentTimeMillis() < endTime) {
            attempt++;

            try {
                log.trace("Checking condition (attempt {})", attempt);

                if (condition.get()) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.debug("Condition met after {} attempts in {}ms", attempt, elapsed);
                    return true;
                }

                log.trace("Condition not yet met, waiting {}ms", pollInterval.toMillis());
                sleep(pollInterval);

            } catch (Exception e) {
                log.warn("Exception while checking condition (attempt {}): {}", attempt, e.getMessage());
                // Continue checking - don't fail on transient errors
                sleep(pollInterval);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.warn("Condition not met after {} attempts in {}ms (timeout)", attempt, elapsed);
        return false;
    }

    /**
     * Wait until a condition is met or throw TimeoutException.
     *
     * @param condition    the condition to check
     * @param timeout      maximum time to wait
     * @param pollInterval time between checks
     * @param errorMessage message for TimeoutException if condition not met
     * @throws TimeoutException if timeout occurs before condition is met
     */
    public void waitUntilOrThrow(
            Supplier<Boolean> condition,
            Duration timeout,
            Duration pollInterval,
            String errorMessage) {

        boolean result = waitUntil(condition, timeout, pollInterval);

        if (!result) {
            throw new TimeoutException(errorMessage, timeout.toMillis());
        }
    }

    /**
     * Execute an operation with retry logic.
     * <p>
     * Retries failed operations based on retry strategy.
     * Only retries on transient/retryable errors.
     *
     * @param operation              the operation to execute
     * @param maxAttempts            maximum number of attempts
     * @param delayBetweenAttempts   delay between retry attempts
     * @param <T>                    the return type
     * @return the operation result
     */
    public <T> OperationResult<T> executeWithRetry(
            Supplier<OperationResult<T>> operation,
            int maxAttempts,
            Duration delayBetweenAttempts) {

        log.debug("Starting executeWithRetry: maxAttempts={}, delay={}",
                maxAttempts, delayBetweenAttempts);

        OperationResult<T> result = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.debug("Executing operation (attempt {} of {})", attempt, maxAttempts);

            long startTime = System.currentTimeMillis();

            try {
                result = operation.get();
                long duration = System.currentTimeMillis() - startTime;

                if (result.isSuccess()) {
                    log.debug("Operation succeeded on attempt {} in {}ms", attempt, duration);
                    return result;
                }

                log.warn("Operation failed on attempt {}: {}", attempt, result.getMessage());

                // Check if we should retry
                if (attempt < maxAttempts && result.isRetryable()) {
                    log.debug("Error is retryable, will retry after {}ms", delayBetweenAttempts.toMillis());
                    sleep(delayBetweenAttempts);
                } else if (!result.isRetryable()) {
                    log.warn("Error is not retryable, stopping attempts");
                    return result;
                }

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log.error("Exception on attempt {} after {}ms: {}", attempt, duration, e.getMessage());

                if (attempt == maxAttempts) {
                    return OperationResult.failure(e);
                }

                sleep(delayBetweenAttempts);
            }
        }

        return result != null ? result : OperationResult.failure("All retry attempts failed");
    }

    /**
     * Execute an operation with exponential backoff retry.
     * <p>
     * Delay doubles after each failed attempt (up to maxDelay).
     *
     * @param operation     the operation to execute
     * @param maxAttempts   maximum number of attempts
     * @param initialDelay  initial delay between attempts
     * @param maxDelay      maximum delay between attempts
     * @param <T>           the return type
     * @return the operation result
     */
    public <T> OperationResult<T> executeWithExponentialBackoff(
            Supplier<OperationResult<T>> operation,
            int maxAttempts,
            Duration initialDelay,
            Duration maxDelay) {

        log.debug("Starting executeWithExponentialBackoff: maxAttempts={}, initialDelay={}, maxDelay={}",
                maxAttempts, initialDelay, maxDelay);

        OperationResult<T> result = null;
        long currentDelayMillis = initialDelay.toMillis();
        long maxDelayMillis = maxDelay.toMillis();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.debug("Executing operation (attempt {} of {}) with backoff delay {}ms",
                    attempt, maxAttempts, currentDelayMillis);

            long startTime = System.currentTimeMillis();

            try {
                result = operation.get();
                long duration = System.currentTimeMillis() - startTime;

                if (result.isSuccess()) {
                    log.debug("Operation succeeded on attempt {} in {}ms", attempt, duration);
                    return result;
                }

                log.warn("Operation failed on attempt {}: {}", attempt, result.getMessage());

                if (attempt < maxAttempts && result.isRetryable()) {
                    log.debug("Will retry with backoff delay {}ms", currentDelayMillis);
                    sleep(Duration.ofMillis(currentDelayMillis));

                    // Double the delay for next attempt (exponential backoff)
                    currentDelayMillis = Math.min(currentDelayMillis * 2, maxDelayMillis);
                } else if (!result.isRetryable()) {
                    log.warn("Error is not retryable, stopping attempts");
                    return result;
                }

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log.error("Exception on attempt {} after {}ms: {}", attempt, duration, e.getMessage());

                if (attempt == maxAttempts) {
                    return OperationResult.failure(e);
                }

                sleep(Duration.ofMillis(currentDelayMillis));
                currentDelayMillis = Math.min(currentDelayMillis * 2, maxDelayMillis);
            }
        }

        return result != null ? result : OperationResult.failure("All retry attempts failed");
    }

    /**
     * Execute an operation with a timeout.
     * <p>
     * Note: This doesn't interrupt the operation, it just fails if it takes too long.
     *
     * @param operation the operation to execute
     * @param timeout   maximum time to wait for operation
     * @param <T>       the return type
     * @return the operation result
     * @throws TimeoutException if operation exceeds timeout
     */
    public <T> OperationResult<T> executeWithTimeout(
            Supplier<OperationResult<T>> operation,
            Duration timeout) {

        log.debug("Starting executeWithTimeout: timeout={}", timeout);

        long startTime = System.currentTimeMillis();

        try {
            OperationResult<T> result = operation.get();
            long duration = System.currentTimeMillis() - startTime;

            if (duration > timeout.toMillis()) {
                log.warn("Operation exceeded timeout: {}ms > {}ms", duration, timeout.toMillis());
                throw new TimeoutException(
                        "Operation exceeded timeout of " + timeout.toMillis() + "ms",
                        timeout.toMillis()
                );
            }

            log.debug("Operation completed in {}ms (within timeout)", duration);
            return result;

        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Operation failed after {}ms: {}", duration, e.getMessage());
            return OperationResult.failure(e);
        }
    }

    /**
     * Sleep for the specified duration, handling interruptions.
     *
     * @param duration time to sleep
     */
    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Sleep interrupted");
            throw new RuntimeException("Sleep interrupted", e);
        }
    }

    /**
     * Measure the execution time of an operation.
     *
     * @param operation the operation to time
     * @param <T>       the return type
     * @return operation result with duration set
     */
    public <T> OperationResult<T> measureExecutionTime(Supplier<OperationResult<T>> operation) {
        long startTime = System.currentTimeMillis();

        try {
            OperationResult<T> result = operation.get();
            Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);

            log.debug("Operation completed in {}ms", duration.toMillis());

            return OperationResult.<T>builder()
                    .success(result.isSuccess())
                    .data(result.getData().orElse(null))
                    .message(result.getMessage())
                    .errorType(result.getErrorType().orElse(null))
                    .throwable(result.getThrowable().orElse(null))
                    .timestamp(result.getTimestamp())
                    .duration(duration)
                    .build();

        } catch (Exception e) {
            Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
            log.error("Operation failed after {}ms: {}", duration.toMillis(), e.getMessage());

            return OperationResult.<T>builder()
                    .success(false)
                    .message(e.getMessage())
                    .throwable(e)
                    .duration(duration)
                    .build();
        }
    }
}
