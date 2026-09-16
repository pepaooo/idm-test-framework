package com.idm.test.connector.database.client;

/**
 * Compares an expected value from a scenario against an actual value from JDBC.
 * <p>
 * Shared deliberately. {@code MultiDatabaseConnector.verifyRecord} and the engine's
 * {@code db.verify} step both answer "does this column hold what the author expected?", and
 * two copies of that answer would drift — one of them tolerating a type difference the other
 * rejects, so the same assertion passes in one place and fails in the other.
 */
public final class ValueComparison {

    private ValueComparison() {
    }

    /**
     * A driver may return a column as Integer, Long or BigDecimal where YAML supplied a plain
     * integer, so numbers compare by value rather than by boxed type. Everything else falls
     * back to equals, then to string form, which lets a scenario write a date or enum the way
     * a person would.
     */
    public static boolean equal(Object expected, Object actual) {
        if (expected == null && actual == null) {
            return true;
        }
        if (expected == null || actual == null) {
            return false;
        }
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            return expectedNumber.doubleValue() == actualNumber.doubleValue();
        }
        return expected.equals(actual) || expected.toString().equals(actual.toString());
    }
}
