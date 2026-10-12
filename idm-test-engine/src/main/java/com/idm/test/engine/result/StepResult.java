package com.idm.test.engine.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.spi.Message;

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
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Object> messageArgs) {

    public StepResult {
        messageArgs = messageArgs == null ? null : Collections.unmodifiableList(new ArrayList<>(messageArgs));
    }

    /** A result whose message is plain text. */
    public StepResult(int index, String type, Map<String, Object> resolvedParams, boolean success,
                      long durationMillis, String message, Integer attempts, Long waitedMillis, String description,
                      Evidence evidence) {
        this(index, type, resolvedParams, success, durationMillis, message, attempts, waitedMillis, description,
                evidence, null, null);
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

    /** The message by key, or null when it is plain text. */
    @JsonIgnore
    public Message messageDetail() {
        return messageKey == null ? null : new Message(messageKey, messageArgs);
    }
}
