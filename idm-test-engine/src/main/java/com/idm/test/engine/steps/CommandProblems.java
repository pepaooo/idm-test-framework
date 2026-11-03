package com.idm.test.engine.steps;

import com.idm.test.connector.exec.client.CommandProblem;
import com.idm.test.engine.spi.Message;

/**
 * Words the command connector's problems by key, so they read in the run's language: for the
 * {@code exec.*} steps, and for the guards and the workbench.
 */
public final class CommandProblems {

    private CommandProblems() {
    }

    public static Message of(CommandProblem problem) {
        return Message.of(key(problem.kind()), problem.values().toArray());
    }

    private static String key(CommandProblem.Kind kind) {
        return switch (kind) {
            case NOT_CONFIGURED -> "step.exec.connector.notConfigured";
            case BAD_DEFINITION -> "step.exec.connector.badDefinition";
            case UNKNOWN_PARAM -> "step.exec.connector.unknownParam";
            case MISSING_PARAM -> "step.exec.connector.missingParam";
            case PARAM_REJECTED -> "step.exec.connector.paramRejected";
            case PARAM_UNSAFE -> "step.exec.connector.paramUnsafe";
            case NOT_FOUND -> "step.exec.connector.notFound";
            case NO_WORKING_DIR -> "step.exec.connector.noWorkingDir";
            case START_FAILED -> "step.exec.connector.startFailed";
        };
    }
}
