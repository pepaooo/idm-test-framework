package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.LdapProblem;
import com.idm.test.engine.spi.Message;

import java.util.ArrayList;
import java.util.List;

/** Words an LDAP matcher or change problem by key, so it reads in the run's language. */
final class LdapProblems {

    private LdapProblems() {
    }

    static Message of(LdapProblem problem) {
        List<Object> args = new ArrayList<>();
        for (Object value : problem.values()) {
            args.add(value instanceof LdapProblem nested ? of(nested) : value);
        }
        return Message.of(key(problem.kind()), args.toArray());
    }

    private static String key(LdapProblem.Kind kind) {
        return switch (kind) {
            case MATCH_SHAPE -> "step.ldap.match.shape";
            case MATCH_CONTAINS_SECRET -> "step.ldap.match.containsSecret";
            case MATCH_CONTAINS -> "step.ldap.match.contains";
            case MATCH_EXCLUDES_SECRET -> "step.ldap.match.excludesSecret";
            case MATCH_EXCLUDES -> "step.ldap.match.excludes";
            case MATCH_COUNT_ONE -> "step.ldap.match.count.one";
            case MATCH_COUNT_OTHER -> "step.ldap.match.count.other";
            case MATCH_PATTERN_SECRET -> "step.ldap.match.patternSecret";
            case MATCH_PATTERN -> "step.ldap.match.pattern";
            case MATCH_VALUES_SECRET -> "step.ldap.match.valuesSecret";
            case MATCH_VALUE -> "step.ldap.match.value";
            case MATCH_EXACT_MISSING -> "step.ldap.match.exactMissing";
            case MATCH_EXACT_UNEXPECTED -> "step.ldap.match.exactUnexpected";
            case MATCH_EXACT_BOTH -> "step.ldap.match.exactBoth";
            case SHAPE_EMPTY_MAP -> "step.ldap.match.shape.emptyMap";
            case SHAPE_UNKNOWN_KEYS -> "step.ldap.match.shape.unknownKeys";
            case SHAPE_EMPTY_KEY -> "step.ldap.match.shape.emptyKey";
            case SHAPE_PLAIN_KEY -> "step.ldap.match.shape.plainKey";
            case SHAPE_COUNT -> "step.ldap.match.shape.count";
            case SHAPE_COUNT_VALUE -> "step.ldap.match.shape.countValue";
            case SHAPE_EMPTY_MATCHES -> "step.ldap.match.shape.emptyMatches";
            case SHAPE_MATCHES_TYPE -> "step.ldap.match.shape.matchesType";
            case SHAPE_PATTERN_SECRET -> "step.ldap.match.shape.patternSecret";
            case SHAPE_PATTERN -> "step.ldap.match.shape.pattern";
            case SHAPE_IGNORE_CASE_TYPE -> "step.ldap.match.shape.ignoreCaseType";
            case SHAPE_IGNORE_CASE_ALONE -> "step.ldap.match.shape.ignoreCaseAlone";
            case SHAPE_OVERLAP_SECRET -> "step.ldap.match.shape.overlapSecret";
            case SHAPE_OVERLAP -> "step.ldap.match.shape.overlap";
            case SHAPE_EMPTY_LIST -> "step.ldap.match.shape.emptyList";
            case SHAPE_PLAIN_LIST -> "step.ldap.match.shape.plainList";
            case CHANGES_EMPTY_MAP -> "step.ldap.changes.emptyMap";
            case CHANGES_UNKNOWN_KEYS -> "step.ldap.changes.unknownKeys";
            case CHANGES_EMPTY_KEY -> "step.ldap.changes.emptyKey";
            case CHANGES_PLAIN_KEY -> "step.ldap.changes.plainKey";
            case CHANGES_OVERLAP_SECRET -> "step.ldap.changes.overlapSecret";
            case CHANGES_OVERLAP -> "step.ldap.changes.overlap";
        };
    }
}
