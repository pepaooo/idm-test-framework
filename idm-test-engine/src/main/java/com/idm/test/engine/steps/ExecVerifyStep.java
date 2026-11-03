package com.idm.test.engine.steps;

import com.idm.test.connector.exec.client.CommandConnector;
import com.idm.test.engine.spi.CommandApproval;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;

/**
 * Runs a registered command and asserts on its result (ADR 0008).
 * <pre>
 * - exec.verify:
 *     command: driver-state
 *     params: { driver: "cn=HR,cn=DS,o=system" }
 *     expect:
 *       exitCode: 0
 *       stdout: { contains: "2" }      # equals | contains | matches, and ignoreCase
 *     wait: { timeout: 2m, poll: 5s }
 * </pre>
 * Retryable: runs the command again on each attempt. A difference and a timeout are retried; a malformed
 * {@code expect} (checked before anything runs), an unknown or unapproved command and a bad parameter are
 * final. {@code expect} is {@link OutputExpectations}.
 */
public final class ExecVerifyStep implements StepType {

    private static final String EXPECT = "expect";

    private final CommandConnector connector;
    private final CommandApproval approval;

    public ExecVerifyStep(CommandConnector connector, CommandApproval approval) {
        this.connector = connector;
        this.approval = approval;
    }

    @Override
    public String name() {
        return "exec.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of(ExecSteps.COMMAND, EXPECT);
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of(ExecSteps.COMMAND, ExecSteps.PARAMS, EXPECT, "wait");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String command = String.valueOf(params.get(ExecSteps.COMMAND));
        Object expect = params.get(EXPECT);
        StepOutcome rejection = OutputExpectations.rejectMalformed(expect, command);
        if (rejection != null) {
            return rejection;
        }
        ExecSteps.Params values = ExecSteps.params(command, params.get(ExecSteps.PARAMS));
        if (values.rejection() != null) {
            return values.rejection();
        }
        ExecSteps.Ran ran = ExecSteps.run(connector, approval, command, values.values());
        if (ran.failure() != null) {
            return ran.failure();
        }
        OutputExpectations.Comparison comparison = OutputExpectations.compare((Map<?, ?>) expect, ran.result(), command);
        if (comparison.mismatch() != null) {
            return StepOutcome.failure(comparison.mismatch()).withEvidence(ran.evidence()).withChecks(comparison.checks());
        }
        Message message = Message.of("step.exec.verify.verified", command, comparison.checks().size());
        return (captureRequested ? StepOutcome.success(message, ExecSteps.captured(ran.result()))
                : StepOutcome.success(message)).withEvidence(ran.evidence()).withChecks(comparison.checks());
    }
}
