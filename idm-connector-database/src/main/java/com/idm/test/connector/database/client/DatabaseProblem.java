package com.idm.test.connector.database.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Why a query or statement failed, as a kind plus the values to quote, so a caller can word it in
 * any language. {@link #english()} is the one English wording; the failure messages of
 * {@link MultiDatabaseConnector#executeQuery} and {@link MultiDatabaseConnector#executeUpdate} are
 * built on it. The values are database names and the driver's own error text.
 */
public record DatabaseProblem(Kind kind, List<Object> values) {

    /** Every failure the query and update methods can report. */
    public enum Kind {
        QUERY_FAILED, UPDATE_FAILED, UNEXPECTED, NOT_CONFIGURED
    }

    public DatabaseProblem {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    static DatabaseProblem of(Kind kind, Object... values) {
        List<Object> list = new ArrayList<>();
        Collections.addAll(list, values);
        return new DatabaseProblem(kind, list);
    }

    private String text(int index) {
        return String.valueOf(values.get(index));
    }

    /** The English wording, character for character what the connector has always reported. */
    public String english() {
        return switch (kind) {
            case QUERY_FAILED -> "Query failed: " + text(0);
            case UPDATE_FAILED -> "Update failed: " + text(0);
            case UNEXPECTED -> "Unexpected error: " + text(0);
            case NOT_CONFIGURED -> "Database '" + text(0) + "' is not configured. Available databases: " + text(1);
        };
    }
}
