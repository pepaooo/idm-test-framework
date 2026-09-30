package com.idm.test.engine.result;

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
 */
public record CleanupResult(
        int index,
        String type,
        Map<String, Object> resolvedParams,
        CleanupStatus status,
        long durationMillis,
        String message,
        String description) {

    /** A result for an entry without a description. */
    public CleanupResult(int index, String type, Map<String, Object> resolvedParams, CleanupStatus status,
                         long durationMillis, String message) {
        this(index, type, resolvedParams, status, durationMillis, message, null);
    }
}
