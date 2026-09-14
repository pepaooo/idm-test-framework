package com.idm.test.engine.result;

import java.util.List;

/**
 * The outcome of one scenario. The engine produces this and renders nothing; every output
 * format — JUnit failures, the JSON file, a future HTML report — is an adapter over it.
 */
public record ScenarioResult(
        String name,
        String sourceName,
        boolean success,
        long durationMillis,
        List<StepResult> steps) {

    /**
     * @return a message naming the file, the step and what went wrong, or null when successful
     */
    public String failureSummary() {
        if (success) {
            return null;
        }

        StepResult failed = steps.isEmpty() ? null : steps.get(steps.size() - 1);
        if (failed == null) {
            return sourceName + ": scenario failed before any step ran";
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
