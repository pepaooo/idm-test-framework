package com.idm.test.engine.result;

import java.util.List;

/**
 * The outcome of one scenario. The engine produces this and renders nothing; every output
 * format — JUnit failures, the JSON file, a future HTML report — is an adapter over it.
 *
 * @param durationMillis wall-clock time for the whole scenario, including fixture building
 *                        and cleanup as well as the steps — the step durations will not sum
 *                        to this value
 * @param failureDetail  what went wrong before any step could run (e.g. fixture setup),
 *                        or null when the scenario got at least as far as its first step
 */
public record ScenarioResult(
        String name,
        String sourceName,
        boolean success,
        long durationMillis,
        List<StepResult> steps,
        String failureDetail) {

    /**
     * @return a message naming the file, the step and what went wrong, or null when successful
     */
    public String failureSummary() {
        if (success) {
            return null;
        }

        StepResult failed = null;
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (!steps.get(i).success()) {
                failed = steps.get(i);
                break;
            }
        }
        if (failed == null) {
            return failureDetail != null
                    ? sourceName + ": " + failureDetail
                    : sourceName + ": scenario failed before any step ran";
        }

        StringBuilder summary = new StringBuilder()
                .append(sourceName)
                .append(", step ").append(failed.index() + 1)
                .append(" (").append(failed.type()).append("): ")
                .append(failed.message());

        if (failed.attempts() != null) {
            summary.append(" — gave up after ").append(failed.attempts())
                    .append(" attempts over ").append(failed.waitedMillis()).append("ms");
        }

        return summary.toString();
    }
}
