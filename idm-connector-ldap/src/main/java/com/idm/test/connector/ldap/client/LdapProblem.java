package com.idm.test.connector.ldap.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What is wrong with an expected value or a change, as a kind plus the values to quote, so a
 * caller can word it in any language. {@link #english()} is the one English wording; the
 * String-returning methods of {@link LdapValueMatcher} and {@link LdapValueChanges} are built on it.
 * A value may itself be a problem (a shape problem inside a match failure).
 */
public record LdapProblem(Kind kind, List<Object> values) {

    private static final String KEY_HINT = "use equals, contains, excludes, count, matches or ignoreCase";

    /** Every problem the matcher and the change rules can report. */
    public enum Kind {
        // match failures: values start with the attribute name
        MATCH_SHAPE, MATCH_CONTAINS_SECRET, MATCH_CONTAINS, MATCH_EXCLUDES_SECRET, MATCH_EXCLUDES,
        MATCH_COUNT_ONE, MATCH_COUNT_OTHER, MATCH_PATTERN_SECRET, MATCH_PATTERN, MATCH_VALUES_SECRET,
        MATCH_VALUE, MATCH_EXACT_MISSING, MATCH_EXACT_UNEXPECTED, MATCH_EXACT_BOTH,
        // matcher shape problems: fragments that follow the attribute's name
        SHAPE_EMPTY_MAP, SHAPE_UNKNOWN_KEYS, SHAPE_EMPTY_KEY, SHAPE_PLAIN_KEY, SHAPE_COUNT,
        SHAPE_COUNT_VALUE, SHAPE_EMPTY_MATCHES, SHAPE_MATCHES_TYPE, SHAPE_PATTERN_SECRET, SHAPE_PATTERN,
        SHAPE_IGNORE_CASE_TYPE, SHAPE_IGNORE_CASE_ALONE, SHAPE_OVERLAP_SECRET, SHAPE_OVERLAP,
        SHAPE_EMPTY_LIST, SHAPE_PLAIN_LIST,
        // add/remove change shape problems
        CHANGES_EMPTY_MAP, CHANGES_UNKNOWN_KEYS, CHANGES_EMPTY_KEY, CHANGES_PLAIN_KEY,
        CHANGES_OVERLAP_SECRET, CHANGES_OVERLAP,
        // EdirectoryConnector operation failures: the directory's own text is a value
        CREATE_FAILED, ENTRY_NOT_FOUND, READ_FAILED, UPDATE_FAILED, UPDATE_REFUSED_REQUEST,
        UPDATE_REFUSED_CHANGE, DELETE_FAILED, EXISTS_FAILED,
        // why the directory refused an add/remove; REFUSED_ALL joins several, its values are problems
        REFUSED_ADD_ONE, REFUSED_ADD_OTHER, REFUSED_REMOVE_ONE, REFUSED_REMOVE_OTHER,
        REFUSED_ADD_SECRET, REFUSED_REMOVE_SECRET, REFUSED_ALL
    }

    public LdapProblem {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    static LdapProblem of(Kind kind, Object... values) {
        List<Object> list = new ArrayList<>();
        Collections.addAll(list, values);
        return new LdapProblem(kind, list);
    }

    private String text(int index) {
        Object value = values.get(index);
        return value instanceof LdapProblem nested ? nested.english() : String.valueOf(value);
    }

    /** The English wording, character for character what the connector has always reported. */
    public String english() {
        return switch (kind) {
            case MATCH_SHAPE -> "Attribute '" + text(0) + "' " + text(1);
            case MATCH_CONTAINS_SECRET -> "Attribute '" + text(0) + "': does not contain all the expected values";
            case MATCH_CONTAINS -> "Attribute '" + text(0) + "': expected to contain " + text(1)
                    + "; missing " + text(2) + "; actual=" + text(3);
            case MATCH_EXCLUDES_SECRET ->
                    "Attribute '" + text(0) + "': contains values that were expected to be absent";
            case MATCH_EXCLUDES -> "Attribute '" + text(0) + "': expected not to contain " + text(1)
                    + "; found " + text(2);
            case MATCH_COUNT_ONE -> "Attribute '" + text(0) + "': expected " + text(1) + " value, found "
                    + text(2) + text(3);
            case MATCH_COUNT_OTHER -> "Attribute '" + text(0) + "': expected " + text(1) + " values, found "
                    + text(2) + text(3);
            case MATCH_PATTERN_SECRET -> "Attribute '" + text(0) + "': no value matches the pattern";
            case MATCH_PATTERN -> "Attribute '" + text(0) + "': no value matches " + text(1)
                    + "; actual=" + text(2);
            case MATCH_VALUES_SECRET -> "Attribute '" + text(0) + "': does not have the expected values";
            case MATCH_VALUE -> "Attribute '" + text(0) + "': expected=" + text(1) + ", actual=" + text(2);
            case MATCH_EXACT_MISSING -> "Attribute '" + text(0) + "': expected exactly " + text(1)
                    + " in any order; missing " + text(2);
            case MATCH_EXACT_UNEXPECTED -> "Attribute '" + text(0) + "': expected exactly " + text(1)
                    + " in any order; unexpected " + text(2);
            case MATCH_EXACT_BOTH -> "Attribute '" + text(0) + "': expected exactly " + text(1)
                    + " in any order; missing " + text(2) + "; unexpected " + text(3);
            case SHAPE_EMPTY_MAP -> "is an empty map; " + KEY_HINT;
            case SHAPE_UNKNOWN_KEYS -> "has unknown matcher keys " + text(0) + "; " + KEY_HINT;
            case SHAPE_EMPTY_KEY, CHANGES_EMPTY_KEY -> "has an empty '" + text(0) + "'";
            case SHAPE_PLAIN_KEY, CHANGES_PLAIN_KEY ->
                    "has a null, map or list value in '" + text(0) + "'; give plain values";
            case SHAPE_COUNT -> "has a 'count' that is not a non-negative whole number";
            case SHAPE_COUNT_VALUE -> "has a 'count' that is not a non-negative whole number: " + text(0);
            case SHAPE_EMPTY_MATCHES -> "has an empty 'matches'";
            case SHAPE_MATCHES_TYPE -> "has a 'matches' that is not a regular expression or a list of them";
            case SHAPE_PATTERN_SECRET -> "has an invalid 'matches' pattern: " + text(0);
            case SHAPE_PATTERN -> "has an invalid 'matches' pattern '" + text(0) + "': " + text(1);
            case SHAPE_IGNORE_CASE_TYPE -> "has an 'ignoreCase' that is not true or false";
            case SHAPE_IGNORE_CASE_ALONE ->
                    "has an 'ignoreCase' with no equals, contains or excludes to apply to";
            case SHAPE_OVERLAP_SECRET -> "lists the same value in both 'contains' and 'excludes'";
            case SHAPE_OVERLAP -> "lists " + text(0) + " in both 'contains' and 'excludes'";
            case SHAPE_EMPTY_LIST -> "is an empty list, which a misspelled attribute name would also satisfy; "
                    + "to assert values are absent use { excludes: [...] }";
            case SHAPE_PLAIN_LIST -> "has a null, map or list value inside its list; give plain values";
            case CHANGES_EMPTY_MAP -> "is an empty map; use add and/or remove";
            case CHANGES_UNKNOWN_KEYS -> "has unknown keys " + text(0) + "; use add and/or remove";
            case CHANGES_OVERLAP_SECRET -> "lists the same value in both 'add' and 'remove'";
            case CHANGES_OVERLAP -> "lists " + text(0) + " in both 'add' and 'remove'";
            case CREATE_FAILED -> "Failed to create user: " + text(0);
            case ENTRY_NOT_FOUND -> "Entry not found: " + text(0);
            case READ_FAILED -> "Failed to read entry: " + text(0);
            case UPDATE_FAILED -> "Failed to update user: " + text(0);
            case UPDATE_REFUSED_REQUEST -> "Failed to update user: the directory refused the request";
            case UPDATE_REFUSED_CHANGE -> "Failed to update user: the directory refused the add/remove (" + text(0) + ")";
            case DELETE_FAILED -> "Failed to delete user: " + text(0);
            case EXISTS_FAILED -> "Error checking existence: " + text(0);
            case REFUSED_ADD_ONE -> "cannot add " + text(0) + " to " + text(1) + ": the entry already has it";
            case REFUSED_ADD_OTHER -> "cannot add " + text(0) + " to " + text(1) + ": the entry already has them";
            case REFUSED_REMOVE_ONE -> "cannot remove " + text(0) + " from " + text(1) + ": the entry does not have it";
            case REFUSED_REMOVE_OTHER -> "cannot remove " + text(0) + " from " + text(1)
                    + ": the entry does not have them";
            case REFUSED_ADD_SECRET ->
                    "cannot add to " + text(0) + ": the directory refused it because a value already exists";
            case REFUSED_REMOVE_SECRET ->
                    "cannot remove from " + text(0) + ": the directory refused it because a value is missing";
            case REFUSED_ALL -> String.join("; ", values.stream()
                    .map(value -> value instanceof LdapProblem nested ? nested.english() : String.valueOf(value))
                    .toList());
        };
    }
}
