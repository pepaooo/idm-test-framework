package com.idm.test.connector.database.client;

import com.idm.test.core.exception.ConfigurationException;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Multi-database connector using raw SQL queries.
 * Similar to OpenText Validator - execute any SQL query on configured databases.
 * <p>
 * Features:
 * - Execute SELECT queries returning List&lt;Map&lt;String, Object&gt;&gt;
 * - Execute INSERT/UPDATE/DELETE operations
 * - Verify record existence and values
 * - Support for multiple databases simultaneously
 * <p>
 * Usage:
 * <pre>
 * // Execute query
 * OperationResult&lt;List&lt;Map&lt;String, Object&gt;&gt;&gt; result =
 *     connector.executeQuery("SAP", "SELECT * FROM USERS WHERE id = ?", userId);
 *
 * // Verify record
 * OperationResult&lt;Boolean&gt; verified = connector.verifyRecord(
 *     "SAP", "USERS", "id", userId,
 *     Map.of("status", "ACTIVE", "department", "IT")
 * );
 * </pre>
 */
@Component
public class MultiDatabaseConnector {

    private static final Logger log = LoggerFactory.getLogger(MultiDatabaseConnector.class);

    private final Map<String, JdbcTemplate> jdbcTemplates;

    public MultiDatabaseConnector(Map<String, JdbcTemplate> jdbcTemplates) {
        this.jdbcTemplates = jdbcTemplates;
    }

    /**
     * Executes a SELECT query and returns results as List of Maps.
     * Each Map represents a row, with column names as keys.
     *
     * @param databaseName name of the database to query
     * @param sql          SQL SELECT statement
     * @param params       query parameters (optional)
     * @return operation result containing list of rows
     */
    public OperationResult<List<Map<String, Object>>> executeQuery(
            String databaseName,
            String sql,
            Object... params) {

        log.debug("Executing query on database '{}': {}", databaseName, sql);

        try {
            JdbcTemplate template = getJdbcTemplate(databaseName);

            long startTime = System.currentTimeMillis();
            List<Map<String, Object>> results = template.queryForList(sql, params);
            long duration = System.currentTimeMillis() - startTime;

            log.debug("Query returned {} row(s) in {}ms", results.size(), duration);

            return OperationResult.<List<Map<String, Object>>>builder()
                    .success(true)
                    .data(results)
                    .message(String.format("Query returned %d row(s)", results.size()))
                    .build();

        } catch (ConfigurationException e) {
            log.error("Configuration error for database '{}': {}", databaseName, e.getMessage());
            return OperationResult.<List<Map<String, Object>>>builder()
                    .success(false)
                    .message(e.getMessage())
                    .errorType(ErrorType.CONFIGURATION_ERROR)
                    .throwable(e)
                    .build();
        } catch (DataAccessException e) {
            log.error("Database query failed on '{}': {}", databaseName, e.getMessage());
            return OperationResult.<List<Map<String, Object>>>builder()
                    .success(false)
                    .message("Query failed: " + e.getMessage())
                    .errorType(ErrorType.DATABASE_ERROR)
                    .throwable(e)
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error executing query on '{}': {}", databaseName, e.getMessage());
            return OperationResult.<List<Map<String, Object>>>builder()
                    .success(false)
                    .message("Unexpected error: " + e.getMessage())
                    .errorType(ErrorType.UNKNOWN_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Executes an UPDATE, INSERT, or DELETE statement.
     *
     * @param databaseName name of the database
     * @param sql          SQL statement
     * @param params       statement parameters (optional)
     * @return operation result containing number of affected rows
     */
    public OperationResult<Integer> executeUpdate(
            String databaseName,
            String sql,
            Object... params) {

        log.debug("Executing update on database '{}': {}", databaseName, sql);

        try {
            JdbcTemplate template = getJdbcTemplate(databaseName);

            long startTime = System.currentTimeMillis();
            int rowsAffected = template.update(sql, params);
            long duration = System.currentTimeMillis() - startTime;

            log.debug("Update affected {} row(s) in {}ms", rowsAffected, duration);

            return OperationResult.<Integer>builder()
                    .success(true)
                    .data(rowsAffected)
                    .message(String.format("Update affected %d row(s)", rowsAffected))
                    .build();

        } catch (ConfigurationException e) {
            log.error("Configuration error for database '{}': {}", databaseName, e.getMessage());
            return OperationResult.<Integer>builder()
                    .success(false)
                    .message(e.getMessage())
                    .errorType(ErrorType.CONFIGURATION_ERROR)
                    .throwable(e)
                    .build();
        } catch (DataAccessException e) {
            log.error("Database update failed on '{}': {}", databaseName, e.getMessage());
            return OperationResult.<Integer>builder()
                    .success(false)
                    .message("Update failed: " + e.getMessage())
                    .errorType(ErrorType.DATABASE_ERROR)
                    .throwable(e)
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error executing update on '{}': {}", databaseName, e.getMessage());
            return OperationResult.<Integer>builder()
                    .success(false)
                    .message("Unexpected error: " + e.getMessage())
                    .errorType(ErrorType.UNKNOWN_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Verifies that a record exists with expected values.
     *
     * @param databaseName   name of the database
     * @param tableName      table name
     * @param keyColumn      primary key column name
     * @param keyValue       primary key value
     * @param expectedValues map of column names to expected values
     * @return operation result indicating if verification passed
     */
    public OperationResult<Boolean> verifyRecord(
            String databaseName,
            String tableName,
            String keyColumn,
            Object keyValue,
            Map<String, Object> expectedValues) {

        log.debug("Verifying record in database '{}', table '{}'", databaseName, tableName);

        try {
            // Build SELECT query for the columns we want to verify
            String columns = String.join(", ", expectedValues.keySet());
            String sql = String.format("SELECT %s FROM %s WHERE %s = ?",
                    columns, tableName, keyColumn);

            OperationResult<List<Map<String, Object>>> queryResult =
                    executeQuery(databaseName, sql, keyValue);

            if (!queryResult.isSuccess()) {
                return OperationResult.<Boolean>builder()
                        .success(false)
                        .message("Failed to query record: " + queryResult.getMessage())
                        .errorType(queryResult.getErrorType().orElse(ErrorType.DATABASE_ERROR))
                        .throwable(queryResult.getThrowable().orElse(null))
                        .build();
            }

            List<Map<String, Object>> results = queryResult.getData().orElseThrow();

            if (results.isEmpty()) {
                log.warn("Record not found: {}={}", keyColumn, keyValue);
                return OperationResult.<Boolean>builder()
                        .success(false)
                        .data(false)
                        .message(String.format("Record not found with %s=%s", keyColumn, keyValue))
                        .errorType(ErrorType.NOT_FOUND)
                        .build();
            }

            Map<String, Object> actualValues = results.get(0);

            // Compare expected vs actual values
            for (Map.Entry<String, Object> entry : expectedValues.entrySet()) {
                String column = entry.getKey();
                Object expectedValue = entry.getValue();
                Object actualValue = actualValues.get(column);

                if (!valuesEqual(expectedValue, actualValue)) {
                    String message = String.format(
                            "Value mismatch for column '%s': expected=%s, actual=%s",
                            column, expectedValue, actualValue);
                    log.warn(message);

                    return OperationResult.<Boolean>builder()
                            .success(false)
                            .data(false)
                            .message(message)
                            .errorType(ErrorType.VALIDATION_ERROR)
                            .build();
                }
            }

            log.debug("Record verification successful");
            return OperationResult.<Boolean>builder()
                    .success(true)
                    .data(true)
                    .message("Record verification successful")
                    .build();

        } catch (Exception e) {
            log.error("Error verifying record: {}", e.getMessage());
            return OperationResult.<Boolean>builder()
                    .success(false)
                    .message("Verification error: " + e.getMessage())
                    .errorType(ErrorType.UNKNOWN_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Checks if a record exists in the database.
     *
     * @param databaseName name of the database
     * @param tableName    table name
     * @param keyColumn    key column name
     * @param keyValue     key value
     * @return operation result indicating if record exists
     */
    public OperationResult<Boolean> recordExists(
            String databaseName,
            String tableName,
            String keyColumn,
            Object keyValue) {

        log.debug("Checking if record exists in database '{}', table '{}'", databaseName, tableName);

        try {
            String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s = ?", tableName, keyColumn);
            JdbcTemplate template = getJdbcTemplate(databaseName);

            Integer count = template.queryForObject(sql, Integer.class, keyValue);
            boolean exists = count != null && count > 0;

            log.debug("Record exists: {}", exists);

            return OperationResult.<Boolean>builder()
                    .success(true)
                    .data(exists)
                    .message(exists ? "Record exists" : "Record not found")
                    .build();

        } catch (DataAccessException e) {
            log.error("Error checking record existence: {}", e.getMessage());
            return OperationResult.<Boolean>builder()
                    .success(false)
                    .message("Error checking existence: " + e.getMessage())
                    .errorType(ErrorType.DATABASE_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Gets the count of rows in a table.
     *
     * @param databaseName name of the database
     * @param tableName    table name
     * @return operation result containing row count
     */
    public OperationResult<Integer> getRowCount(String databaseName, String tableName) {
        log.debug("Getting row count for table '{}' in database '{}'", tableName, databaseName);

        try {
            String sql = String.format("SELECT COUNT(*) FROM %s", tableName);
            JdbcTemplate template = getJdbcTemplate(databaseName);

            Integer count = template.queryForObject(sql, Integer.class);

            return OperationResult.<Integer>builder()
                    .success(true)
                    .data(count)
                    .message(String.format("Table contains %d row(s)", count))
                    .build();

        } catch (DataAccessException e) {
            log.error("Error getting row count: {}", e.getMessage());
            return OperationResult.<Integer>builder()
                    .success(false)
                    .message("Error getting row count: " + e.getMessage())
                    .errorType(ErrorType.DATABASE_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Retrieves the JdbcTemplate for a specific database.
     *
     * @param databaseName name of the database
     * @return JdbcTemplate for the database
     * @throws ConfigurationException if database is not configured
     */
    private JdbcTemplate getJdbcTemplate(String databaseName) {
        JdbcTemplate template = jdbcTemplates.get(databaseName);

        if (template == null) {
            throw new ConfigurationException(
                    String.format("Database '%s' is not configured. Available databases: %s",
                            databaseName, jdbcTemplates.keySet()),
                    databaseName
            );
        }

        return template;
    }

    /**
     * Compares two values for equality, handling nulls.
     *
     * @param expected expected value
     * @param actual   actual value
     * @return true if values are equal
     */
    private boolean valuesEqual(Object expected, Object actual) {
        return ValueComparison.equal(expected, actual);
    }

    /**
     * Returns the set of configured database names.
     *
     * @return set of database names
     */
    public java.util.Set<String> getConfiguredDatabases() {
        return jdbcTemplates.keySet();
    }
}
