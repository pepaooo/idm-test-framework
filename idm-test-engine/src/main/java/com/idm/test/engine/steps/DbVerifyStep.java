package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.database.client.ValueComparison;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Asserts that a query returns the expected rows.
 * <p>
 * Retryable: provisioning is asynchronous, so the engine may call this repeatedly under a
 * {@code wait} until it passes or the timeout expires.
 * <p>
 * {@code rowCount} and {@code row} are separate keys rather than one merged matcher, so a
 * table with a column named {@code rows} or {@code rowCount} cannot change how a scenario
 * parses (spec §7.3).
 */
public final class DbVerifyStep implements StepType {

    private static final String ROW_COUNT = "rowCount";
    private static final String ROW = "row";
    private static final Set<String> EXPECT_KEYS = Set.of(ROW_COUNT, ROW);

    private final MultiDatabaseConnector connector;

    public DbVerifyStep(MultiDatabaseConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "db.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("database", "query", "expect");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("database", "query", "params", "expect", "wait");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String database = String.valueOf(params.get("database"));
        String query = String.valueOf(params.get("query"));
        Object[] arguments = argumentsOf(params.get("params"));
        Map<String, Object> expect = (Map<String, Object>) params.get("expect");

        StepOutcome rejection = rejectMalformedExpect(expect, database);
        if (rejection != null) {
            return rejection;
        }

        OperationResult<List<Map<String, Object>>> result =
                connector.executeQuery(database, query, arguments);

        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "db.verify failed on '" + database + "': " + result.getMessage());
        }

        List<Map<String, Object>> rows = result.getData().orElse(List.of());

        if (expect.containsKey(ROW_COUNT)) {
            Object expectedCount = expect.get(ROW_COUNT);
            if (!ValueComparison.equal(expectedCount, rows.size())) {
                return StepOutcome.failure("db.verify on '" + database + "' expected "
                        + expectedCount + " row(s) but the query returned " + rows.size());
            }
        }

        if (expect.containsKey(ROW)) {
            if (rows.size() != 1) {
                // Matching "the first row" of a multi-row result would depend on an
                // unordered result set — intermittently green, which is worse than red.
                return StepOutcome.failure("db.verify on '" + database
                        + "' uses a row matcher, which needs exactly one row, but the query "
                        + "returned " + rows.size()
                        + ". Narrow the query, or assert rowCount instead.");
            }

            // Matched against the map executeQuery returned, which is case-insensitive on
            // column names. Copying it into a plain map first would silently break
            // `row: { status: ... }` against a database that stores STATUS.
            Map<String, Object> actual = rows.get(0);
            Map<String, Object> expectedRow = (Map<String, Object>) expect.get(ROW);

            for (Map.Entry<String, Object> column : expectedRow.entrySet()) {
                Object actualValue = actual.get(column.getKey());
                if (!ValueComparison.equal(column.getValue(), actualValue)) {
                    return StepOutcome.failure("db.verify on '" + database
                            + "' found a mismatch for column '" + column.getKey()
                            + "': expected=" + column.getValue() + ", actual=" + actualValue);
                }
            }
        }

        String message = "verified " + rows.size() + " row(s) on '" + database + "'";
        return captureRequested
                ? StepOutcome.success(message, null, rows)
                : StepOutcome.success(message);
    }

    /**
     * An {@code expect} that asserts nothing, or one whose keys are typos, would pass having
     * compared nothing at all — the vacuous-assertion trap, caught once already on
     * {@code ldap.verify}. Checked before the query runs, since neither problem depends on
     * the result.
     */
    private static StepOutcome rejectMalformedExpect(Map<String, Object> expect, String database) {
        if (expect == null || expect.isEmpty()) {
            return StepOutcome.failure("db.verify on '" + database
                    + "' has an empty expect: block. Assert " + ROW_COUNT + ", " + ROW
                    + ", or both — an empty expect would verify nothing.");
        }

        Set<String> unknown = new TreeSet<>(expect.keySet());
        unknown.removeAll(EXPECT_KEYS);
        if (!unknown.isEmpty()) {
            return StepOutcome.failure("db.verify on '" + database
                    + "' has unknown key(s) in expect: " + unknown + ". expect accepts: "
                    + new TreeSet<>(EXPECT_KEYS));
        }

        if (expect.get(ROW) != null && !(expect.get(ROW) instanceof Map)) {
            return StepOutcome.failure("db.verify on '" + database + "' has a " + ROW
                    + " that is not a map of column names to expected values");
        }

        return null;
    }

    /**
     * YAML gives a list; JdbcTemplate wants a varargs array. An absent list means no
     * parameters, which is ordinary for a query with no placeholders.
     */
    private static Object[] argumentsOf(Object raw) {
        if (raw == null) {
            return new Object[0];
        }
        if (raw instanceof List<?> list) {
            return list.toArray();
        }
        return new Object[]{raw};
    }
}
