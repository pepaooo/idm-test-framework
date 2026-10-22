package com.idm.test.engine.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.FailureKind;
import com.idm.test.engine.spi.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The outcome of one scenario. The engine produces this and renders nothing; every output
 * format — JUnit failures, the JSON file, a future HTML report — is an adapter over it.
 *
 * @param durationMillis wall-clock time for the whole scenario, including fixture building
 *                        and cleanup as well as the steps — the step durations will not sum
 *                        to this value
 * @param failureDetail  what went wrong before any step could run (e.g. fixture setup), or that
 *                        the run was stopped after its steps passed but before its cleanup; null
 *                        otherwise
 * @param description    the scenario's own top-level description, or null
 * @param failureDetailKey  the key of {@code failureDetail}, so it can be shown in another
 *                          language, or null when it is plain text
 * @param failureDetailArgs the arguments of {@code failureDetailKey}, or null when there is no key
 * @param stopped        true when the run was stopped while this scenario ran: its cleanup did not run
 * @param skippedCleanup the cleanup entries a stop skipped, SKIPPED, with the values they would have
 *                       used; empty unless {@code stopped}
 */
public record ScenarioResult(
        String name,
        String sourceName,
        boolean success,
        long durationMillis,
        List<StepResult> steps,
        List<CleanupResult> cleanup,
        String failureDetail,
        String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) String failureDetailKey,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Object> failureDetailArgs,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean stopped,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<CleanupResult> skippedCleanup) {

    /** The message key of the step a stop interrupted, and of a scenario stopped before its cleanup. */
    public static final String STOPPED_KEY = "engine.run.stopped";

    public ScenarioResult {
        cleanup = cleanup == null ? List.of() : cleanup;
        failureDetailArgs = failureDetailArgs == null
                ? null : Collections.unmodifiableList(new ArrayList<>(failureDetailArgs));
        skippedCleanup = skippedCleanup == null ? List.of() : List.copyOf(skippedCleanup);
    }

    /** A result of a run that was not stopped. */
    public ScenarioResult(String name, String sourceName, boolean success, long durationMillis,
                          List<StepResult> steps, List<CleanupResult> cleanup, String failureDetail,
                          String description, String failureDetailKey, List<Object> failureDetailArgs) {
        this(name, sourceName, success, durationMillis, steps, cleanup, failureDetail, description,
                failureDetailKey, failureDetailArgs, false, List.of());
    }

    /** A result whose failure detail, if any, is plain text. */
    public ScenarioResult(String name, String sourceName, boolean success, long durationMillis,
                          List<StepResult> steps, List<CleanupResult> cleanup, String failureDetail,
                          String description) {
        this(name, sourceName, success, durationMillis, steps, cleanup, failureDetail, description, null, null);
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
     * @return a message naming the file and what went wrong, in English, or null when successful.
     * The steps' own failure leads; any FAILED cleanup entries follow it.
     */
    public String failureSummary() {
        return failureSummary(Locale.ENGLISH);
    }

    /**
     * @return {@link #failureSummary()} in the given language: the frame is translated, and each
     * step's, entry's or failure detail's own message is translated when it has a key and shown as
     * written when it has none. Null when successful.
     */
    public String failureSummary(Locale locale) {
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
            Object text = text(failed.message(), failed.messageDetail());
            Message step = failed.description() == null
                    ? Message.of("engine.summary.step", sourceName, failed.index() + 1, failed.type(), text)
                    : Message.of("engine.summary.stepDescribed", sourceName, failed.index() + 1, failed.type(),
                            failed.description(), text);
            if (failed.attempts() != null) {
                step = Message.of("engine.summary.gaveUp", step, failed.attempts(), failed.waitedMillis());
            }
            parts.add(Messages.render(step, locale));
        } else if (failureDetail != null) {
            parts.add(Messages.render(Message.of("engine.summary.detail", sourceName,
                    text(failureDetail, failureDetailMessage())), locale));
        }

        for (CleanupResult entry : cleanup) {
            if (entry.status() == CleanupStatus.FAILED) {
                Object text = text(entry.message(), entry.messageDetail());
                Message line = entry.description() == null
                        ? Message.of("engine.summary.cleanup", sourceName, entry.index() + 1, entry.type(), text)
                        : Message.of("engine.summary.cleanupDescribed", sourceName, entry.index() + 1, entry.type(),
                                entry.description(), text);
                parts.add(Messages.render(line, locale));
            }
        }

        return parts.isEmpty()
                ? Messages.render(Message.of("engine.summary.beforeAnyStep", sourceName), locale)
                : String.join("; ", parts);
    }

    /**
     * What kind of failure this scenario is, or null when it passed: its failed step's kind; a
     * {@link FailureKind#SETUP} problem when it failed before any step (it did not load, or its
     * fixtures failed); a {@link FailureKind#FINDING} when only its cleanup failed. The step a stop
     * interrupted ({@link #STOPPED_KEY}) is passed over: an earlier failed step's kind wins, and a
     * scenario only the stop failed is a {@link FailureKind#SETUP} problem.
     */
    @JsonIgnore
    public FailureKind failureKind() {
        if (success) {
            return null;
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (!steps.get(i).success() && !STOPPED_KEY.equals(steps.get(i).messageKey())) {
                return steps.get(i).kind();
            }
        }
        boolean cleanupFailed = cleanup.stream().anyMatch(entry -> entry.status() == CleanupStatus.FAILED);
        return failureDetail == null && cleanupFailed ? FailureKind.FINDING : FailureKind.SETUP;
    }

    /**
     * Whether the stop is all that went wrong, so the scenario counts as stopped: {@link #stopped()},
     * and either every step passed, no cleanup entry FAILED and the failure detail is none or the
     * engine's stop ({@link #STOPPED_KEY}); or exactly one step failed, the one the stop interrupted
     * (its message key is {@link #STOPPED_KEY}). Anything else — a step that had already failed when
     * the stop came, a fixture failure — counts by its {@link #failureKind() kind}.
     */
    @JsonIgnore
    public boolean stoppedOnly() {
        if (!stopped) {
            return false;
        }
        List<StepResult> failed = steps.stream().filter(step -> !step.success()).toList();
        if (failed.isEmpty()) {
            boolean cleanupFailed = cleanup.stream().anyMatch(entry -> entry.status() == CleanupStatus.FAILED);
            return !cleanupFailed && (failureDetail == null || STOPPED_KEY.equals(failureDetailKey));
        }
        return failed.size() == 1 && STOPPED_KEY.equals(failed.getFirst().messageKey());
    }

    /** The failure detail by key, or null when it is plain text. */
    @JsonIgnore
    public Message failureDetailMessage() {
        return failureDetailKey == null ? null : new Message(failureDetailKey, failureDetailArgs);
    }

    /** A message as an argument: by key when it has one, so it renders in the summary's language. */
    private static Object text(String english, Message detail) {
        return detail == null ? english : detail;
    }
}
