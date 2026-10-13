package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.database.client.ValueComparison;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.SqlEvidence;
import com.idm.test.engine.spi.Message;
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
        Object rawExpect = params.get("expect");

        StepOutcome rejection = rejectMalformedExpect(rawExpect, database);
        if (rejection != null) {
            return rejection;
        }
        Map<String, Object> expect = (Map<String, Object>) rawExpect;

        OperationResult<List<Map<String, Object>>> result =
                connector.executeQuery(database, query, arguments);

        if (!result.isSuccess()) {
            Message message = Message.of("step.db.verify.failed", database,
                    ConnectorProblems.database(result, e -> connector.queryProblem(database, e)));
            // An unknown database never becomes known by waiting; a SQL error might (a table or
            // row that provisioning has not created yet), so only configuration errors are final.
            return result.getErrorType().filter(ErrorType.CONFIGURATION_ERROR::equals).isPresent()
                    ? StepOutcome.finalFailure(message)
                    : StepOutcome.failure(message)
                            .withEvidence(SqlEvidence.failed(database, query, arguments));
        }

        List<Map<String, Object>> rows = result.getData().orElse(List.of());
        SqlEvidence evidence = SqlEvidence.query(database, query, arguments, rows);

        if (expect.containsKey(ROW_COUNT)) {
            Object expectedCount = expect.get(ROW_COUNT);
            if (!ValueComparison.equal(expectedCount, rows.size())) {
                return StepOutcome.failure(Message.of("step.db.verify.rowCountMismatch", database, expectedCount, rows.size()))
                        .withEvidence(evidence);
            }
        }

        if (expect.containsKey(ROW)) {
            if (rows.size() != 1) {
                // Matching "the first row" of a multi-row result would depend on an
                // unordered result set — intermittently green, which is worse than red.
                return StepOutcome.failure(Message.of("step.db.verify.rowNeedsOne", database, rows.size()))
                        .withEvidence(evidence);
            }

            // Matched against the map executeQuery returned, which is case-insensitive on
            // column names. Copying it into a plain map first would silently break
            // `row: { status: ... }` against a database that stores STATUS.
            Map<String, Object> actual = rows.get(0);
            Map<String, Object> expectedRow = (Map<String, Object>) expect.get(ROW);

            for (Map.Entry<String, Object> column : expectedRow.entrySet()) {
                Object actualValue = actual.get(column.getKey());
                if (!ValueComparison.equal(column.getValue(), actualValue)) {
                    return StepOutcome.failure(Message.of("step.db.verify.columnMismatch", database, column.getKey(),
                            column.getValue(), actualValue))
                            .withEvidence(evidence);
                }
            }
        }

        Message message = Message.of("step.db.verify.verified", rows.size(), database);
        return (captureRequested
                ? StepOutcome.success(message, rows)
                : StepOutcome.success(message)).withEvidence(evidence);
    }

    /**
     * An {@code expect} that asserts nothing, whose keys are typos, or whose {@code row} is
     * empty or not a map, would pass having compared nothing at all — the vacuous-assertion
     * trap, caught repeatedly in this project. Checked before the query runs, since none of
     * these problems depend on the result.
     * <p>
     * Takes the raw, unvalidated value from {@code params.get("expect")} rather than an
     * already-cast {@code Map}: a scenario can write {@code expect} as a scalar, and
     * {@code StepType.execute} must never throw, so the type check has to happen here, before
     * any caller casts it.
     */
    @SuppressWarnings("unchecked")
    private static StepOutcome rejectMalformedExpect(Object rawExpect, String database) {
        if (rawExpect != null && !(rawExpect instanceof Map)) {
            return StepOutcome.finalFailure(Message.of("step.db.verify.expectNotMap", database, ROW_COUNT, ROW));
        }

        Map<String, Object> expect = (Map<String, Object>) rawExpect;
        if (expect == null || expect.isEmpty()) {
            return StepOutcome.finalFailure(Message.of("step.db.verify.expectEmpty", database, ROW_COUNT, ROW));
        }

        Set<String> unknown = new TreeSet<>(expect.keySet());
        unknown.removeAll(EXPECT_KEYS);
        if (!unknown.isEmpty()) {
            return StepOutcome.finalFailure(Message.of("step.db.verify.unknownKeys", database, unknown,
                    new TreeSet<>(EXPECT_KEYS)));
        }

        // Positive rule: if ROW is present at all, it must be a Map with at least one
        // entry. This is one condition rather than a "not a map" check plus an "empty map"
        // check so that a present-but-null value (expect: { row: null }) cannot slip past
        // both — containsKey agrees with execute() about what "present" means.
        if (expect.containsKey(ROW)
                && !(expect.get(ROW) instanceof Map<?, ?> rowMap && !rowMap.isEmpty())) {
            return StepOutcome.finalFailure(Message.of("step.db.verify.rowShape", database, ROW));
        }

        // The hole one level in: a misspelled column name (`staus:` instead of `status:`)
        // parses as an entry whose value is null, indistinguishable from an explicit
        // `staus: null` after Map.get() — and ValueComparison.equal(null, null) is true, so
        // a typo'd column would report "verified" having compared nothing.
        if (expect.containsKey(ROW)) {
            for (Map.Entry<?, ?> column : ((Map<?, ?>) expect.get(ROW)).entrySet()) {
                if (column.getValue() == null) {
                    return StepOutcome.finalFailure(Message.of("step.db.verify.nullExpected", database, column.getKey()));
                }
            }
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
