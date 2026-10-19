package com.idm.test.engine.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.idm.test.engine.spi.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What happened to one {@code cleanup:} entry.
 *
 * @param index          position in the cleanup block, from 0
 * @param type           the step type, e.g. {@code ldap.delete}
 * @param resolvedParams parameters after interpolation; empty when SKIPPED or when resolving failed
 * @param status         how the entry ended
 * @param durationMillis time spent on this entry
 * @param message        the step's own message, or why it was skipped or failed
 * @param description    the entry's description: resolved when it ran and resolved cleanly, otherwise as written; null when it has none
 * @param messageKey     the key of {@code message}, so it can be shown in another language, or
 *                       null when the message is plain text
 * @param messageArgs    the arguments of {@code messageKey}, or null when there is no key
 * @param failureKind    {@code FINDING} when the entry FAILED (something may be left in the lab);
 *                       null when it succeeded or was skipped
 */
public record CleanupResult(
        int index,
        String type,
        Map<String, Object> resolvedParams,
        CleanupStatus status,
        long durationMillis,
        String message,
        String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) String messageKey,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Object> messageArgs,
        @JsonInclude(JsonInclude.Include.NON_NULL) String failureKind) {

    public CleanupResult {
        messageArgs = messageArgs == null ? null : Collections.unmodifiableList(new ArrayList<>(messageArgs));
    }

    /** A result without a failure kind. */
    public CleanupResult(int index, String type, Map<String, Object> resolvedParams, CleanupStatus status,
                         long durationMillis, String message, String description, String messageKey,
                         List<Object> messageArgs) {
        this(index, type, resolvedParams, status, durationMillis, message, description, messageKey, messageArgs,
                null);
    }

    /** A result whose message is plain text. */
    public CleanupResult(int index, String type, Map<String, Object> resolvedParams, CleanupStatus status,
                         long durationMillis, String message, String description) {
        this(index, type, resolvedParams, status, durationMillis, message, description, null, null, null);
    }

    /** A result for an entry without a description. */
    public CleanupResult(int index, String type, Map<String, Object> resolvedParams, CleanupStatus status,
                         long durationMillis, String message) {
        this(index, type, resolvedParams, status, durationMillis, message, null);
    }

    /** The message by key, or null when it is plain text. */
    @JsonIgnore
    public Message messageDetail() {
        return messageKey == null ? null : new Message(messageKey, messageArgs);
    }
}
