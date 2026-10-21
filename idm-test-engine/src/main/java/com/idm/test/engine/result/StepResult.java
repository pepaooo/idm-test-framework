package com.idm.test.engine.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.idm.test.engine.spi.Check;
import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.spi.FailureKind;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.WaitObservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What one step did.
 *
 * @param resolvedParams parameters after interpolation, so a report shows the values that
 *                       were actually used rather than the references that produced them
 * @param attempts       how many times a retried assertion ran, or null if it did not wait
 * @param waitedMillis   how long a retried assertion waited, or null if it did not wait
 * @param description    the step's own description, resolved, or null when it has none
 * @param evidence       what the step sent and received on its last attempt, or null
 * @param messageKey     the key of {@code message}, so it can be shown in another language, or
 *                       null when the message is plain text
 * @param messageArgs    the arguments of {@code messageKey}, or null when there is no key
 * @param failureKind    {@code FINDING} or {@code SETUP} (a {@link FailureKind} name) when the step
 *                       failed; null when it passed
 * @param waitHistory    the tries under {@code wait:} whose observation differed from the try before
 *                       (the first always counts), at most {@link #MAX_WAIT_HISTORY}; null when the
 *                       step did not wait
 * @param checks         each comparison a verify step made on its last attempt, expected against
 *                       found (see {@link Check}); empty when the step compared nothing, never null
 */
public record StepResult(
        int index,
        String type,
        Map<String, Object> resolvedParams,
        boolean success,
        long durationMillis,
        String message,
        Integer attempts,
        Long waitedMillis,
        String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) Evidence evidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) String messageKey,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Object> messageArgs,
        @JsonInclude(JsonInclude.Include.NON_NULL) String failureKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<WaitObservation> waitHistory,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Check> checks) {

    /** The most entries a waiting history keeps. */
    public static final int MAX_WAIT_HISTORY = 50;

    public StepResult {
        messageArgs = messageArgs == null ? null : Collections.unmodifiableList(new ArrayList<>(messageArgs));
        waitHistory = waitHistory == null ? null : List.copyOf(waitHistory);
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    /** A result without checks. */
    public StepResult(int index, String type, Map<String, Object> resolvedParams, boolean success,
                      long durationMillis, String message, Integer attempts, Long waitedMillis, String description,
                      Evidence evidence, String messageKey, List<Object> messageArgs, String failureKind,
                      List<WaitObservation> waitHistory) {
        this(index, type, resolvedParams, success, durationMillis, message, attempts, waitedMillis, description,
                evidence, messageKey, messageArgs, failureKind, waitHistory, null);
    }

    /** A result without a waiting history. */
    public StepResult(int index, String type, Map<String, Object> resolvedParams, boolean success,
                      long durationMillis, String message, Integer attempts, Long waitedMillis, String description,
                      Evidence evidence, String messageKey, List<Object> messageArgs, String failureKind) {
        this(index, type, resolvedParams, success, durationMillis, message, attempts, waitedMillis, description,
                evidence, messageKey, messageArgs, failureKind, null);
    }

    /** A result without a failure kind. */
    public StepResult(int index, String type, Map<String, Object> resolvedParams, boolean success,
                      long durationMillis, String message, Integer attempts, Long waitedMillis, String description,
                      Evidence evidence, String messageKey, List<Object> messageArgs) {
        this(index, type, resolvedParams, success, durationMillis, message, attempts, waitedMillis, description,
                evidence, messageKey, messageArgs, null);
    }

    /** A result whose message is plain text. */
    public StepResult(int index, String type, Map<String, Object> resolvedParams, boolean success,
                      long durationMillis, String message, Integer attempts, Long waitedMillis, String description,
                      Evidence evidence) {
        this(index, type, resolvedParams, success, durationMillis, message, attempts, waitedMillis, description,
                evidence, null, null, null);
    }

    /** A result without evidence. */
    public StepResult(int index, String type, Map<String, Object> resolvedParams, boolean success,
                      long durationMillis, String message, Integer attempts, Long waitedMillis, String description) {
        this(index, type, resolvedParams, success, durationMillis, message, attempts, waitedMillis, description, null);
    }

    /** A result for a step without a description. */
    public StepResult(int index, String type, Map<String, Object> resolvedParams, boolean success,
                      long durationMillis, String message, Integer attempts, Long waitedMillis) {
        this(index, type, resolvedParams, success, durationMillis, message, attempts, waitedMillis, null);
    }

    /** The failure's kind, or null when the step passed; a failure recorded without one is a finding. */
    @JsonIgnore
    public FailureKind kind() {
        if (success) {
            return null;
        }
        return failureKind == null ? FailureKind.FINDING : FailureKind.valueOf(failureKind);
    }

    /** The message by key, or null when it is plain text. */
    @JsonIgnore
    public Message messageDetail() {
        return messageKey == null ? null : new Message(messageKey, messageArgs);
    }
}
