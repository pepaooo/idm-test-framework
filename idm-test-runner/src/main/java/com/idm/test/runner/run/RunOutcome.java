package com.idm.test.runner.run;

import java.nio.file.Path;

/**
 * How a run ended.
 * <p>
 * Every scenario that ran counts once: as passed, or by its {@link
 * com.idm.test.engine.result.ScenarioResult#failureKind() failure kind} as a finding or a setup
 * problem; a scenario that did not load is a setup problem. A stopped scenario is not passed: it
 * counts by its kind too, which the engine makes a setup problem. Scenarios a stop kept from
 * starting count in {@code total} only, so {@code passed + findings + setupProblems} can be less
 * than {@code total} when {@code stopped}.
 *
 * @param runId    the run folder's name, or null when the run was refused before it had one
 * @param folder   {@code reports/<run-id>/}, or null when the run was refused before it had one
 * @param total    the scenarios selected; 0 when the run was refused before selecting
 * @param stopped  whether a stop ended the run early
 * @param exitCode what {@code idmtest run} exits with ({@link com.idm.test.runner.ExitCodes})
 */
public record RunOutcome(String runId, Path folder, int total, int passed, int findings, int setupProblems,
                         boolean stopped, int exitCode) {
}
