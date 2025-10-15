package com.idm.test.core.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Represents the result of an operation with success/failure state, optional data,
 * error information, and execution metadata.
 * <p>
 * This is the standard return type for all connector operations, providing a
 * consistent way to handle both successful and failed operations.
 *
 * @param <T> the type of data returned on success
 */
public final class OperationResult<T> {

    private final boolean success;
    private final T data;
    private final String message;
    private final ErrorType errorType;
    private final Throwable throwable;
    private final Instant timestamp;
    private final Duration duration;

    private OperationResult(Builder<T> builder) {
        this.success = builder.success;
        this.data = builder.data;
        this.message = builder.message;
        this.errorType = builder.errorType;
        this.throwable = builder.throwable;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.duration = builder.duration;
    }

    /**
     * Creates a successful result with data.
     *
     * @param data the result data
     * @param <T>  the type of data
     * @return successful OperationResult
     */
    public static <T> OperationResult<T> success(T data) {
        return OperationResult.<T>builder()
                .success(true)
                .data(data)
                .message("Operation completed successfully")
                .build();
    }

    /**
     * Creates a successful result with data and custom message.
     *
     * @param data    the result data
     * @param message success message
     * @param <T>     the type of data
     * @return successful OperationResult
     */
    public static <T> OperationResult<T> success(T data, String message) {
        return OperationResult.<T>builder()
                .success(true)
                .data(data)
                .message(message)
                .build();
    }

    /**
     * Creates a failed result with error message.
     *
     * @param message error message
     * @param <T>     the type of data
     * @return failed OperationResult
     */
    public static <T> OperationResult<T> failure(String message) {
        return OperationResult.<T>builder()
                .success(false)
                .message(message)
                .errorType(ErrorType.UNKNOWN_ERROR)
                .build();
    }

    /**
     * Creates a failed result with error message and type.
     *
     * @param message   error message
     * @param errorType type of error
     * @param <T>       the type of data
     * @return failed OperationResult
     */
    public static <T> OperationResult<T> failure(String message, ErrorType errorType) {
        return OperationResult.<T>builder()
                .success(false)
                .message(message)
                .errorType(errorType)
                .build();
    }

    /**
     * Creates a failed result from an exception.
     *
     * @param throwable the exception
     * @param <T>       the type of data
     * @return failed OperationResult
     */
    public static <T> OperationResult<T> failure(Throwable throwable) {
        return OperationResult.<T>builder()
                .success(false)
                .message(throwable.getMessage())
                .errorType(ErrorType.UNKNOWN_ERROR)
                .throwable(throwable)
                .build();
    }

    /**
     * Creates a failed result from an exception with error type.
     *
     * @param throwable the exception
     * @param errorType type of error
     * @param <T>       the type of data
     * @return failed OperationResult
     */
    public static <T> OperationResult<T> failure(Throwable throwable, ErrorType errorType) {
        return OperationResult.<T>builder()
                .success(false)
                .message(throwable.getMessage())
                .errorType(errorType)
                .throwable(throwable)
                .build();
    }

    /**
     * Creates a new builder instance.
     *
     * @param <T> the type of data
     * @return new Builder
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    // Getters

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    public Optional<T> getData() {
        return Optional.ofNullable(data);
    }

    public String getMessage() {
        return message;
    }

    public Optional<ErrorType> getErrorType() {
        return Optional.ofNullable(errorType);
    }

    public Optional<Throwable> getThrowable() {
        return Optional.ofNullable(throwable);
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Optional<Duration> getDuration() {
        return Optional.ofNullable(duration);
    }

    /**
     * Returns the data if present, otherwise throws an exception.
     *
     * @return the data
     * @throws IllegalStateException if operation failed
     */
    public T getDataOrThrow() {
        if (!success) {
            throw new IllegalStateException("Operation failed: " + message, throwable);
        }
        if (data == null) {
            throw new IllegalStateException("Operation succeeded but data is null");
        }
        return data;
    }

    /**
     * Returns the data if present, otherwise returns the default value.
     *
     * @param defaultValue value to return if data is not present
     * @return the data or default value
     */
    public T getDataOrDefault(T defaultValue) {
        return success && data != null ? data : defaultValue;
    }

    /**
     * Executes the given action if the operation was successful.
     *
     * @param action the action to execute
     * @return this OperationResult for chaining
     */
    public OperationResult<T> ifSuccess(Consumer<T> action) {
        if (success && data != null) {
            action.accept(data);
        }
        return this;
    }

    /**
     * Executes the given action if the operation failed.
     *
     * @param action the action to execute with error message
     * @return this OperationResult for chaining
     */
    public OperationResult<T> ifFailure(Consumer<String> action) {
        if (!success) {
            action.accept(message);
        }
        return this;
    }

    /**
     * Maps the data to a different type if operation was successful.
     *
     * @param mapper function to map the data
     * @param <U>    the new data type
     * @return new OperationResult with mapped data
     */
    public <U> OperationResult<U> map(Function<T, U> mapper) {
        if (!success) {
            return OperationResult.<U>builder()
                    .success(false)
                    .message(message)
                    .errorType(errorType)
                    .throwable(throwable)
                    .timestamp(timestamp)
                    .duration(duration)
                    .build();
        }

        try {
            U mappedData = data != null ? mapper.apply(data) : null;
            return OperationResult.<U>builder()
                    .success(true)
                    .data(mappedData)
                    .message(message)
                    .timestamp(timestamp)
                    .duration(duration)
                    .build();
        } catch (Exception e) {
            return OperationResult.<U>builder()
                    .success(false)
                    .message("Mapping failed: " + e.getMessage())
                    .errorType(ErrorType.UNKNOWN_ERROR)
                    .throwable(e)
                    .timestamp(timestamp)
                    .duration(duration)
                    .build();
        }
    }

    /**
     * Determines if this error is retryable based on error type.
     *
     * @return true if the operation should be retried
     */
    public boolean isRetryable() {
        return errorType != null && errorType.isRetryable();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("OperationResult{");
        sb.append("success=").append(success);
        if (data != null) {
            sb.append(", data=").append(data);
        }
        if (message != null) {
            sb.append(", message='").append(message).append('\'');
        }
        if (errorType != null) {
            sb.append(", errorType=").append(errorType);
        }
        if (duration != null) {
            sb.append(", duration=").append(duration.toMillis()).append("ms");
        }
        sb.append(", timestamp=").append(timestamp);
        sb.append('}');
        return sb.toString();
    }

    /**
     * Builder for creating OperationResult instances.
     *
     * @param <T> the type of data
     */
    public static final class Builder<T> {
        private boolean success;
        private T data;
        private String message;
        private ErrorType errorType;
        private Throwable throwable;
        private Instant timestamp;
        private Duration duration;

        private Builder() {
        }

        public Builder<T> success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder<T> data(T data) {
            this.data = data;
            return this;
        }

        public Builder<T> message(String message) {
            this.message = message;
            return this;
        }

        public Builder<T> errorType(ErrorType errorType) {
            this.errorType = errorType;
            return this;
        }

        public Builder<T> throwable(Throwable throwable) {
            this.throwable = throwable;
            return this;
        }

        public Builder<T> timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder<T> duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public OperationResult<T> build() {
            return new OperationResult<>(this);
        }
    }
}
