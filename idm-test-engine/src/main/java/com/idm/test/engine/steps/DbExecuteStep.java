package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.SqlEvidence;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs an INSERT, UPDATE or DELETE. Registers nothing. Rows a scenario inserts are removed by a
 * {@code db.execute} in its {@code cleanup:} block. 0 affected rows is still success, which is
 * what makes a cleanup {@code DELETE ... WHERE} safe when the steps already removed the rows.
 */
public final class DbExecuteStep implements StepType {

    private final MultiDatabaseConnector connector;

    public DbExecuteStep(MultiDatabaseConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "db.execute";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("database", "statement");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("database", "statement", "params");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the affected-row count comes back from the statement
        // itself, so there is no extra work to skip when nobody asked to capture it.
        String database = String.valueOf(params.get("database"));
        String statement = String.valueOf(params.get("statement"));
        Object[] arguments = argumentsOf(params.get("params"));

        OperationResult<Integer> result = connector.executeUpdate(database, statement, arguments);

        if (!result.isSuccess()) {
            StepOutcome failure = StepOutcome.failure(
                    "db.execute failed on '" + database + "': " + result.getMessage());
            // An unknown database was never contacted, so there is nothing to show.
            return result.getErrorType().filter(ErrorType.CONFIGURATION_ERROR::equals).isPresent()
                    ? failure
                    : failure.withEvidence(SqlEvidence.failed(database, statement, arguments));
        }

        int affected = result.getData().orElse(0);
        String message = "executed on '" + database + "', " + affected + " row(s) affected";

        return StepOutcome.success(message, affected)
                .withEvidence(SqlEvidence.update(database, statement, arguments, affected));
    }

    /**
     * YAML gives a list; JdbcTemplate wants a varargs array. An absent list means no
     * parameters, which is ordinary for a statement with no placeholders.
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
