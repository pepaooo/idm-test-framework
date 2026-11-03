package com.idm.test.engine.steps;

import com.idm.test.connector.exec.client.CommandConnector;
import com.idm.test.connector.exec.client.CommandResult;
import com.idm.test.connector.exec.client.RegisteredCommand;
import com.idm.test.engine.spi.CommandApproval;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a registered command (ADR 0008).
 * <pre>
 * - exec.run:
 *     command: driver-start
 *     params: { driver: "cn=HR,cn=DS,o=system" }
 *     as: started            # { exitCode, stdout, stderr, durationMillis }
 * </pre>
 * Succeeds when the exit code is one of the definition's {@code exit-codes}; another exit code is a
 * finding, with the output as evidence. A timeout is a setup problem. An unknown or unapproved command, a
 * parameter that is unknown, missing or does not match its pattern, and an executable that cannot be
 * started are final setup problems, and nothing runs. Usable in {@code cleanup:}.
 */
public final class ExecRunStep implements StepType {

    private final CommandConnector connector;
    private final CommandApproval approval;

    public ExecRunStep(CommandConnector connector, CommandApproval approval) {
        this.connector = connector;
        this.approval = approval;
    }

    @Override
    public String name() {
        return "exec.run";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of(ExecSteps.COMMAND);
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of(ExecSteps.COMMAND, ExecSteps.PARAMS);
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String command = String.valueOf(params.get(ExecSteps.COMMAND));
        ExecSteps.Params values = ExecSteps.params(command, params.get(ExecSteps.PARAMS));
        if (values.rejection() != null) {
            return values.rejection();
        }
        ExecSteps.Ran ran = ExecSteps.run(connector, approval, command, values.values());
        if (ran.failure() != null) {
            return ran.failure();
        }
        CommandResult result = ran.result();
        List<Integer> allowed = connector.command(command).map(RegisteredCommand::definition)
                .map(definition -> definition.exitCodes()).orElse(List.of(0));
        if (!allowed.contains(result.exitCode())) {
            return StepOutcome.failure(Message.of("step.exec.run.exitCode", command, result.exitCode(), allowed))
                    .withEvidence(ran.evidence());
        }
        Message message = Message.of("step.exec.run.ran", command, result.exitCode(), result.durationMillis() + " ms");
        return StepOutcome.success(message, ExecSteps.captured(result)).withEvidence(ran.evidence());
    }
}
