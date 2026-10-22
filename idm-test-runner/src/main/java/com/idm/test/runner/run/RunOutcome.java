package com.idm.test.runner.run;

import java.nio.file.Path;

/**
 * How a run ended.
 * <p>
 * Every scenario that ran counts once: a scenario only a stop failed ({@link
 * com.idm.test.engine.result.ScenarioResult#stoppedOnly()}) in {@code stoppedScenarios}; otherwise
 * as passed, or by its {@link
 * com.idm.test.engine.result.ScenarioResult#failureKind() failure kind} as a finding or a setup
 * problem; a scenario that did not load is a setup problem. Scenarios a stop kept from starting
 * count in {@code total} only, so {@code passed + findings + setupProblems + stoppedScenarios} can
 * be less than {@code total} when {@code stopped}.
 *
 * @param runId    the run folder's name, or null when the run was refused before it had one
 * @param folder   {@code reports/<run-id>/}, or null when the run was refused before it had one
 * @param total    the scenarios selected; 0 when the run was refused before selecting
 * @param stoppedScenarios the scenarios only a stop failed; not in {@code setupProblems}
 * @param stopped  whether a stop ended the run early
 * @param exitCode what {@code idmtest run} exits with ({@link com.idm.test.runner.ExitCodes})
 */
public record RunOutcome(String runId, Path folder, int total, int passed, int findings, int setupProblems,
                         int stoppedScenarios, boolean stopped, int exitCode) {
}
