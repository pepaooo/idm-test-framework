package com.idm.test.engine.steps;

import com.idm.test.connector.exec.client.CommandConnector;
import com.idm.test.connector.exec.client.CommandDefinition;
import com.idm.test.connector.exec.client.CommandProblem;
import com.idm.test.connector.exec.client.CommandResult;
import com.idm.test.connector.exec.client.RegisteredCommand;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.result.Secrets;
import com.idm.test.engine.spi.CommandApproval;
import com.idm.test.engine.spi.CommandEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** What {@code exec.run} and {@code exec.verify} share: parameters, the approval, running, evidence. */
final class ExecSteps {

    static final String COMMAND = "command";
    static final String PARAMS = "params";

    private ExecSteps() {
    }

    /** A command that ran (or timed out), with its evidence; or the final failure that it could not. */
    record Ran(CommandResult result, CommandEvidence evidence, StepOutcome failure) {
    }

    /** The scenario's {@code params} as text, or the final failure that a malformed one is. */
    record Params(Map<String, String> values, StepOutcome rejection) {
    }

    static Params params(String command, Object raw) {
        if (raw == null) {
            return new Params(Map.of(), null);
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return new Params(null, StepOutcome.finalFailure(Message.of("step.exec.paramsNotMap", command)));
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
                return new Params(null, StepOutcome.finalFailure(
                        Message.of("step.exec.paramNotScalar", command, entry.getKey())));
            }
            values.put(String.valueOf(entry.getKey()), String.valueOf(value));
        }
        return new Params(values, null);
    }

    /**
     * Runs {@code command} if its definition is approved here. Not approved, unknown, or unable to start:
     * a final setup failure, and nothing runs.
     */
    static Ran run(CommandConnector connector, CommandApproval approval, String command, Map<String, String> params) {
        Optional<RegisteredCommand> registered = connector.command(command);
        if (registered.isPresent() && !approval.approved(command, registered.get().hash())) {
            return new Ran(null, null, StepOutcome.finalFailure(Message.of("step.exec.notApproved", command))
                    .withEvidence(CommandEvidence.notStarted(command)));
        }
        OperationResult<CommandResult> result = connector.run(command, params);
        if (!result.isSuccess()) {
            Message message = CommandProblem.of(result).map(CommandProblems::of)
                    .orElseGet(() -> Message.of("step.exec.couldNotRun", command, result.getMessage()));
            return new Ran(null, null, StepOutcome.finalFailure(message)
                    .withEvidence(CommandEvidence.notStarted(command)));
        }
        CommandResult ran = result.getDataOrThrow();
        CommandEvidence evidence = CommandEvidence.ran(command, ran.argv(),
                secretPositions(registered.map(RegisteredCommand::definition).orElse(null)), ran.exitCode(),
                ran.durationMillis(), ran.timedOut(), ran.stdout(), ran.stderr(), ran.stdoutBytes(), ran.stderrBytes());
        if (ran.timedOut()) {
            return new Ran(ran, evidence, StepOutcome.failure(Message.of("step.exec.timedOut", command,
                    registered.map(r -> r.definition().timeout().toSeconds() + " s").orElse("?")), ErrorType.TIMEOUT)
                    .withEvidence(evidence));
        }
        return new Ran(ran, evidence, null);
    }

    /** Positions in the started command line (executable at 0) filled by a secret-named parameter. */
    private static Set<Integer> secretPositions(CommandDefinition definition) {
        Set<Integer> positions = new HashSet<>();
        if (definition == null) {
            return positions;
        }
        List<String> args = definition.args();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.length() > 2 && arg.startsWith("{") && arg.endsWith("}")
                    && Secrets.isSecret(arg.substring(1, arg.length() - 1))) {
                positions.add(i + 1);
            }
        }
        return positions;
    }

    /** The captured value of a command that ran. */
    static Map<String, Object> captured(CommandResult result) {
        Map<String, Object> captured = new LinkedHashMap<>();
        captured.put("exitCode", result.exitCode());
        captured.put("stdout", result.stdout());
        captured.put("stderr", result.stderr());
        captured.put("durationMillis", result.durationMillis());
        return captured;
    }
}
