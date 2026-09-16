package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs an INSERT, UPDATE or DELETE.
 * <p>
 * The undo is explicit, never inferred: there is no general way to derive the inverse of an
 * arbitrary SQL statement, and a guessed inverse would be worse than none. A scenario that
 * needs its rows cleaned up declares how.
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
        return Set.of("database", "statement", "params", "undo");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        // captureRequested is ignored: the affected-row count comes back from the statement
        // itself, so there is no extra work to skip when nobody asked to capture it.
        String database = String.valueOf(params.get("database"));
        String statement = String.valueOf(params.get("statement"));
        Object[] arguments = argumentsOf(params.get("params"));

        Map<String, Object> undoSpec = (Map<String, Object>) params.get("undo");
        String undoStatement = null;
        Object[] undoArguments = null;

        if (undoSpec != null) {
            Object rawUndoStatement = undoSpec.get("statement");
            if (rawUndoStatement == null) {
                // Validated before running anything. An undo that cannot run is a cleanup
                // hole, and unwind time — after the scenario has already finished — is far
                // too late to find out.
                return StepOutcome.failure("db.execute on '" + database
                        + "' declares an undo with no statement");
            }
            undoStatement = String.valueOf(rawUndoStatement);
            undoArguments = argumentsOf(undoSpec.get("params"));
        }

        OperationResult<Integer> result = connector.executeUpdate(database, statement, arguments);

        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "db.execute failed on '" + database + "': " + result.getMessage());
        }

        int affected = result.getData().orElse(0);
        String message = "executed on '" + database + "', " + affected + " row(s) affected";

        if (undoStatement == null) {
            return StepOutcome.success(message, null, affected);
        }

        String finalUndoStatement = undoStatement;
        Object[] finalUndoArguments = undoArguments;
        return StepOutcome.success(message, () -> {
            OperationResult<Integer> undone =
                    connector.executeUpdate(database, finalUndoStatement, finalUndoArguments);
            if (!undone.isSuccess()) {
                throw new IllegalStateException("cleanup failed on '" + database + "': "
                        + undone.getMessage());
            }
        }, affected);
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
