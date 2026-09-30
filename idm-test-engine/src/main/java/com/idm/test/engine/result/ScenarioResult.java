package com.idm.test.engine.result;

import java.util.ArrayList;
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
 * @param description    the scenario's own top-level description, or null
 */
public record ScenarioResult(
        String name,
        String sourceName,
        boolean success,
        long durationMillis,
        List<StepResult> steps,
        List<CleanupResult> cleanup,
        String failureDetail,
        String description) {

    public ScenarioResult {
        cleanup = cleanup == null ? List.of() : cleanup;
    }

    /** A result with no cleanup outcomes. */
    public ScenarioResult(String name, String sourceName, boolean success, long durationMillis,
                          List<StepResult> steps, String failureDetail) {
        this(name, sourceName, success, durationMillis, steps, List.of(), failureDetail, null);
    }

    /** A result with cleanup outcomes but no description. */
    public ScenarioResult(String name, String sourceName, boolean success, long durationMillis,
                          List<StepResult> steps, List<CleanupResult> cleanup, String failureDetail) {
        this(name, sourceName, success, durationMillis, steps, cleanup, failureDetail, null);
    }

    /**
     * @return a message naming the file and what went wrong, or null when successful. The
     * steps' own failure leads; any FAILED cleanup entries follow it.
     */
    public String failureSummary() {
        if (success) {
            return null;
        }

        List<String> parts = new ArrayList<>();

        StepResult failed = null;
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (!steps.get(i).success()) {
                failed = steps.get(i);
                break;
            }
        }

        if (failed != null) {
            StringBuilder step = new StringBuilder()
                    .append(sourceName)
                    .append(", step ").append(failed.index() + 1)
                    .append(" (").append(failed.type()).append(quoted(failed.description())).append("): ")
                    .append(failed.message());
            if (failed.attempts() != null) {
                step.append(" — gave up after ").append(failed.attempts())
                        .append(" attempts over ").append(failed.waitedMillis()).append("ms");
            }
            parts.add(step.toString());
        } else if (failureDetail != null) {
            parts.add(sourceName + ": " + failureDetail);
        }

        for (CleanupResult entry : cleanup) {
            if (entry.status() == CleanupStatus.FAILED) {
                parts.add(sourceName + ", cleanup entry " + (entry.index() + 1)
                        + " (" + entry.type() + quoted(entry.description()) + "): " + entry.message());
            }
        }

        return parts.isEmpty()
                ? sourceName + ": scenario failed before any step ran"
                : String.join("; ", parts);
    }

    /** ': "<description>"' for a failure message, or "" when there is no description. */
    private static String quoted(String description) {
        return description == null ? "" : ": \"" + description + "\"";
    }
}
