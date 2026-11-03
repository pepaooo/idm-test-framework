package com.idm.test.connector.file.client;

import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Why a file operation failed, as a kind plus the values to quote, so a caller can word it in any
 * language (as {@code DatabaseProblem} does for SQL). {@link #english()} is the one English wording and
 * the message of the failed {@link OperationResult}; {@link #of(OperationResult)} recovers the problem
 * from it.
 * <p>
 * The values are folder settings names, file names, setting values and short reasons. Never a path:
 * messages end up in reports that are shared beyond the consultant's machine.
 */
public record FileProblem(Kind kind, List<Object> values) {

    /** Every failure the connector reports, and whether waiting could change it. */
    public enum Kind {
        /** values: folder, available folders. */
        NOT_CONFIGURED(ErrorType.CONFIGURATION_ERROR),
        /** values: folder. */
        NO_PATH(ErrorType.CONFIGURATION_ERROR),
        /** values: folder. */
        FOLDER_MISSING(ErrorType.CONFIGURATION_ERROR),
        /** values: folder. */
        NOT_A_DIRECTORY(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, declared access, needed access ("read" or "write"). */
        ACCESS_DENIED(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, name. */
        UNSAFE_NAME(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, name, temporary suffix. */
        RESERVED_NAME(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, pattern, reason. */
        BAD_PATTERN(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, setting, value. */
        BAD_FORMAT(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, reason. */
        BAD_ROWS(ErrorType.CONFIGURATION_ERROR),
        /** values: folder. */
        NO_HEADER(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, name. */
        ALREADY_EXISTS(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, temporary name. */
        TEMP_EXISTS(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, name. */
        NOT_A_FILE(ErrorType.CONFIGURATION_ERROR),
        /** values: folder. */
        NO_ATOMIC_MOVE(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, name, encoding. */
        UNENCODABLE(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, reason. */
        NOT_READABLE(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, reason. */
        NOT_WRITABLE(ErrorType.CONFIGURATION_ERROR),
        /** values: folder, name, reason. Retryable: a share can come back. */
        IO_FAILED(ErrorType.NETWORK_ERROR),
        /** values: folder, name, reason. Retryable: the driver may still be writing the file. */
        PARSE_FAILED(ErrorType.VALIDATION_ERROR);

        private final ErrorType errorType;

        Kind(ErrorType errorType) {
            this.errorType = errorType;
        }

        public ErrorType errorType() {
            return errorType;
        }
    }

    public FileProblem {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    static FileProblem of(Kind kind, Object... values) {
        List<Object> list = new ArrayList<>();
        Collections.addAll(list, values);
        return new FileProblem(kind, list);
    }

    /** @return the problem behind a failed result of {@link DelimitedFileConnector}, if it has one */
    public static Optional<FileProblem> of(OperationResult<?> result) {
        return result.getThrowable()
                .filter(Failure.class::isInstance)
                .map(t -> ((Failure) t).problem());
    }

    /** @return the error type: {@link ErrorType#CONFIGURATION_ERROR} means waiting cannot change it */
    public ErrorType errorType() {
        return kind.errorType();
    }

    <T> OperationResult<T> toResult() {
        return OperationResult.failure(new Failure(this), kind.errorType());
    }

    private String text(int index) {
        return String.valueOf(values.get(index));
    }

    /** The English wording. */
    public String english() {
        return switch (kind) {
            case NOT_CONFIGURED -> "Folder '" + text(0) + "' is not configured. Available folders: " + text(1);
            case NO_PATH -> "Folder '" + text(0) + "' has no valid path in the settings";
            case FOLDER_MISSING -> "Folder '" + text(0) + "' does not exist";
            case NOT_A_DIRECTORY -> "Folder '" + text(0) + "' is not a directory";
            case ACCESS_DENIED -> "Folder '" + text(0) + "' is declared with access '" + text(1)
                    + "', which does not allow " + text(2);
            case UNSAFE_NAME -> "File name '" + text(1) + "' is not a plain name inside folder '" + text(0) + "'";
            case RESERVED_NAME -> "File name '" + text(1) + "' ends with the temporary suffix '" + text(2)
                    + "' of folder '" + text(0) + "'";
            case BAD_PATTERN -> "File pattern '" + text(1) + "' for folder '" + text(0) + "' is not valid: " + text(2);
            case BAD_FORMAT -> "Format setting '" + text(1) + "' for folder '" + text(0) + "' is not valid: '"
                    + text(2) + "'";
            case BAD_ROWS -> "Rows for folder '" + text(0) + "' are not valid: " + text(1);
            case NO_HEADER -> "Reading files in folder '" + text(0) + "' needs a header row (header: true)";
            case ALREADY_EXISTS -> "File '" + text(1) + "' already exists in folder '" + text(0) + "'";
            case TEMP_EXISTS -> "Temporary file '" + text(1) + "' already exists in folder '" + text(0) + "'";
            case NOT_A_FILE -> "'" + text(1) + "' in folder '" + text(0) + "' is not a regular file";
            case NO_ATOMIC_MOVE -> "Folder '" + text(0) + "' does not support an atomic rename";
            case UNENCODABLE -> "File '" + text(1) + "' for folder '" + text(0) + "' has a value that "
                    + text(2) + " cannot encode";
            case NOT_READABLE -> "Folder '" + text(0) + "' cannot be read: " + text(1);
            case NOT_WRITABLE -> "Folder '" + text(0) + "' cannot be written: " + text(1);
            case IO_FAILED -> "File '" + text(1) + "' in folder '" + text(0) + "' could not be accessed: " + text(2);
            case PARSE_FAILED -> "File '" + text(1) + "' in folder '" + text(0) + "' is not valid delimited text: "
                    + text(2);
        };
    }

    /** Carries the problem inside a failed {@link OperationResult}. No stack trace: it is not a bug. */
    static final class Failure extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient FileProblem problem;

        Failure(FileProblem problem) {
            super(problem.english(), null, false, false);
            this.problem = problem;
        }

        FileProblem problem() {
            return problem;
        }
    }
}
