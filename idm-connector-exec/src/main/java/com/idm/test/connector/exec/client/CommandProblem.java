package com.idm.test.connector.exec.client;

import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Why a command could not run, as a kind plus the values to quote, so a caller can word it in any
 * language. {@link #english()} is the English wording and the message of the failed
 * {@link OperationResult}; {@link #of(OperationResult)} recovers the problem from it. The values are
 * command and parameter names, patterns and short reasons; never a parameter's value, which may be
 * secret. All are setup problems: waiting cannot change them.
 */
public record CommandProblem(Kind kind, List<Object> values) {

    public enum Kind {
        /** values: command, available commands. */
        NOT_CONFIGURED,
        /** values: command, reason. */
        BAD_DEFINITION,
        /** values: command, parameter, declared parameters. */
        UNKNOWN_PARAM,
        /** values: command, parameter. */
        MISSING_PARAM,
        /** values: command, parameter, pattern. */
        PARAM_REJECTED,
        /** values: command, executable. */
        NOT_FOUND,
        /** values: command. */
        NO_WORKING_DIR,
        /** values: command, reason. */
        START_FAILED
    }

    public CommandProblem {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    static CommandProblem of(Kind kind, Object... values) {
        List<Object> list = new ArrayList<>();
        Collections.addAll(list, values);
        return new CommandProblem(kind, list);
    }

    /** @return the problem behind a failed result of {@link CommandConnector}, if it has one */
    public static Optional<CommandProblem> of(OperationResult<?> result) {
        return result.getThrowable()
                .filter(Failure.class::isInstance)
                .map(t -> ((Failure) t).problem());
    }

    /** @return always {@link ErrorType#CONFIGURATION_ERROR}: none of these change by waiting */
    public ErrorType errorType() {
        return ErrorType.CONFIGURATION_ERROR;
    }

    <T> OperationResult<T> toResult() {
        return OperationResult.failure(new Failure(this), errorType());
    }

    private String text(int index) {
        return String.valueOf(values.get(index));
    }

    public String english() {
        return switch (kind) {
            case NOT_CONFIGURED -> "Command '" + text(0) + "' is not configured. Available commands: " + text(1);
            case BAD_DEFINITION -> "Command '" + text(0) + "' is not a usable definition: " + text(1);
            case UNKNOWN_PARAM -> "Command '" + text(0) + "' has no parameter '" + text(1) + "'. Its parameters: " + text(2);
            case MISSING_PARAM -> "Command '" + text(0) + "' needs the parameter '" + text(1) + "'";
            case PARAM_REJECTED -> "The value of parameter '" + text(1) + "' of command '" + text(0)
                    + "' does not match its pattern " + text(2);
            case NOT_FOUND -> "Command '" + text(0) + "': the executable '" + text(1) + "' was not found";
            case NO_WORKING_DIR -> "Command '" + text(0) + "': its working directory does not exist";
            case START_FAILED -> "Command '" + text(0) + "' could not be started: " + text(1);
        };
    }

    /** Carries the problem inside a failed {@link OperationResult}. No stack trace: it is not a bug. */
    static final class Failure extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient CommandProblem problem;

        Failure(CommandProblem problem) {
            super(problem.english(), null, false, false);
            this.problem = problem;
        }

        CommandProblem problem() {
            return problem;
        }
    }
}
