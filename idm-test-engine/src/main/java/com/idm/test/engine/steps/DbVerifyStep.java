package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.SqlEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asserts that a query returns the expected rows.
 * <p>
 * Retryable: provisioning is asynchronous, so the engine may call this repeatedly under a
 * {@code wait} until it passes or the timeout expires.
 * <p>
 * The {@code expect} checks and comparison are {@link RowExpectations}, shared with
 * {@code file.verify}.
 */
public final class DbVerifyStep implements StepType {

    private static final RowExpectations.Keys KEYS = new RowExpectations.Keys(
            "step.db.verify.expectNotMap",
            "step.db.verify.expectEmpty",
            "step.db.verify.unknownKeys",
            "step.db.verify.rowShape",
            "step.db.verify.nullExpected",
            "step.db.verify.rowCountMismatch",
            "step.db.verify.rowNeedsOne",
            "step.db.verify.columnMismatch");

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

        StepOutcome rejection = RowExpectations.rejectMalformed(rawExpect, KEYS, database);
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
                    : StepOutcome.failure(message, result.getErrorType().orElse(ErrorType.UNKNOWN_ERROR))
                            .withEvidence(SqlEvidence.failed(database, query, arguments));
        }

        List<Map<String, Object>> rows = result.getData().orElse(List.of());
        SqlEvidence evidence = SqlEvidence.query(database, query, arguments, rows);

        // Compared against the maps executeQuery returned, which are case-insensitive on column
        // names. Copying them into plain maps first would silently break `row: { status: ... }`
        // against a database that stores STATUS.
        RowExpectations.Comparison comparison = RowExpectations.compare(rows, expect, KEYS, database);

        if (comparison.mismatch() != null) {
            return StepOutcome.failure(comparison.mismatch()).withEvidence(evidence).withChecks(comparison.checks());
        }

        Message message = Message.of("step.db.verify.verified", rows.size(), database);
        return (captureRequested
                ? StepOutcome.success(message, rows)
                : StepOutcome.success(message)).withEvidence(evidence).withChecks(comparison.checks());
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
