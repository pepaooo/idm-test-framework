package com.idm.test.connector.database.client;

import com.idm.test.connector.database.config.DatabaseConfiguration;
import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MultiDatabaseConnector.
 * Tests against H2 in-memory databases.
 */
@SpringBootTest(classes = {
    DatabaseConfiguration.class,
    MultiDatabaseConnector.class
})
class MultiDatabaseConnectorTest {

    @Autowired
    private MultiDatabaseConnector connector;

    @Autowired
    private Map<String, JdbcTemplate> jdbcTemplates;

    @BeforeEach
    void setUp() {
        // Create test tables and insert test data
        JdbcTemplate testdb = jdbcTemplates.get("testdb");

        // Drop tables if they exist
        testdb.execute("DROP TABLE IF EXISTS users");
        testdb.execute("DROP TABLE IF EXISTS departments");

        // Create users table
        testdb.execute("""
            CREATE TABLE users (
                id VARCHAR(50) PRIMARY KEY,
                username VARCHAR(100) NOT NULL,
                email VARCHAR(255),
                department VARCHAR(100),
                status VARCHAR(20),
                salary DECIMAL(10,2)
            )
        """);

        // Create departments table
        testdb.execute("""
            CREATE TABLE departments (
                dept_id VARCHAR(50) PRIMARY KEY,
                dept_name VARCHAR(100) NOT NULL,
                location VARCHAR(100)
            )
        """);

        // Insert test data
        testdb.update(
            "INSERT INTO users (id, username, email, department, status, salary) VALUES (?, ?, ?, ?, ?, ?)",
            "user1", "john.doe", "john.doe@test.com", "IT", "ACTIVE", 75000.00
        );

        testdb.update(
            "INSERT INTO users (id, username, email, department, status, salary) VALUES (?, ?, ?, ?, ?, ?)",
            "user2", "jane.smith", "jane.smith@test.com", "HR", "ACTIVE", 65000.00
        );

        testdb.update(
            "INSERT INTO users (id, username, email, department, status, salary) VALUES (?, ?, ?, ?, ?, ?)",
            "user3", "bob.wilson", "bob.wilson@test.com", "IT", "INACTIVE", 80000.00
        );

        testdb.update(
            "INSERT INTO departments (dept_id, dept_name, location) VALUES (?, ?, ?)",
            "IT", "Information Technology", "Building A"
        );

        testdb.update(
            "INSERT INTO departments (dept_id, dept_name, location) VALUES (?, ?, ?)",
            "HR", "Human Resources", "Building B"
        );

        // Setup second database
        JdbcTemplate testdb2 = jdbcTemplates.get("testdb2");
        testdb2.execute("DROP TABLE IF EXISTS employees");
        testdb2.execute("""
            CREATE TABLE employees (
                emp_id VARCHAR(50) PRIMARY KEY,
                emp_name VARCHAR(100) NOT NULL,
                emp_status VARCHAR(20)
            )
        """);

        testdb2.update(
            "INSERT INTO employees (emp_id, emp_name, emp_status) VALUES (?, ?, ?)",
            "EMP001", "Alice Johnson", "ACTIVE"
        );
    }

    @Test
    @DisplayName("Should execute SELECT query and return results")
    void testExecuteQuery() {
        String sql = "SELECT * FROM users WHERE department = ? ORDER BY username";

        OperationResult<List<Map<String, Object>>> result =
            connector.executeQuery("testdb", sql, "IT");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();

        List<Map<String, Object>> users = result.getData().get();
        assertThat(users).hasSize(2);

        // Verify first user (bob.wilson)
        Map<String, Object> user1 = users.get(0);
        assertThat(user1.get("USERNAME")).isEqualTo("bob.wilson");
        assertThat(user1.get("DEPARTMENT")).isEqualTo("IT");
        assertThat(user1.get("STATUS")).isEqualTo("INACTIVE");

        // Verify second user (john.doe)
        Map<String, Object> user2 = users.get(1);
        assertThat(user2.get("USERNAME")).isEqualTo("john.doe");
        assertThat(user2.get("DEPARTMENT")).isEqualTo("IT");
        assertThat(user2.get("STATUS")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Should execute SELECT query with no results")
    void testExecuteQueryNoResults() {
        String sql = "SELECT * FROM users WHERE department = ?";

        OperationResult<List<Map<String, Object>>> result =
            connector.executeQuery("testdb", sql, "FINANCE");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isEmpty();
        assertThat(result.getMessage()).contains("0 row(s)");
    }

    @Test
    @DisplayName("Should execute SELECT query on different database")
    void testExecuteQueryOnDifferentDatabase() {
        String sql = "SELECT * FROM employees WHERE emp_status = ?";

        OperationResult<List<Map<String, Object>>> result =
            connector.executeQuery("testdb2", sql, "ACTIVE");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();

        List<Map<String, Object>> employees = result.getData().get();
        assertThat(employees).hasSize(1);
        assertThat(employees.get(0).get("EMP_NAME")).isEqualTo("Alice Johnson");
    }

    @Test
    @DisplayName("Should fail when executing query on non-existent database")
    void testExecuteQueryOnNonExistentDatabase() {
        String sql = "SELECT * FROM users";

        OperationResult<List<Map<String, Object>>> result =
            connector.executeQuery("nonexistent", sql);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isPresent();
        assertThat(result.getErrorType().get()).isEqualTo(ErrorType.CONFIGURATION_ERROR);
        assertThat(result.getMessage()).contains("not configured");
    }

    @Test
    @DisplayName("Should fail when executing invalid SQL")
    void testExecuteQueryWithInvalidSql() {
        String sql = "SELECT * FROM nonexistent_table";

        OperationResult<List<Map<String, Object>>> result =
            connector.executeQuery("testdb", sql);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isPresent();
        assertThat(result.getErrorType().get()).isEqualTo(ErrorType.DATABASE_ERROR);
    }

    @Test
    @DisplayName("Should execute INSERT statement")
    void testExecuteInsert() {
        String sql = "INSERT INTO users (id, username, email, department, status, salary) VALUES (?, ?, ?, ?, ?, ?)";

        OperationResult<Integer> result = connector.executeUpdate(
            "testdb", sql,
            "user4", "alice.brown", "alice.brown@test.com", "SALES", "ACTIVE", 70000.00
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isEqualTo(1);
        assertThat(result.getMessage()).contains("1 row(s)");

        // Verify the insert
        OperationResult<List<Map<String, Object>>> queryResult =
            connector.executeQuery("testdb", "SELECT * FROM users WHERE id = ?", "user4");

        assertThat(queryResult.isSuccess()).isTrue();
        assertThat(queryResult.getData().get()).hasSize(1);
        assertThat(queryResult.getData().get().get(0).get("USERNAME")).isEqualTo("alice.brown");
    }

    @Test
    @DisplayName("Should execute UPDATE statement")
    void testExecuteUpdate() {
        String sql = "UPDATE users SET status = ?, salary = ? WHERE id = ?";

        OperationResult<Integer> result = connector.executeUpdate(
            "testdb", sql, "INACTIVE", 85000.00, "user1"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isEqualTo(1);

        // Verify the update
        OperationResult<List<Map<String, Object>>> queryResult =
            connector.executeQuery("testdb", "SELECT * FROM users WHERE id = ?", "user1");

        Map<String, Object> user = queryResult.getData().get().get(0);
        assertThat(user.get("STATUS")).isEqualTo("INACTIVE");

        // Handle decimal precision - H2 returns BigDecimal, compare as doubles
        Object salaryObj = user.get("SALARY");
        double actualSalary = ((Number) salaryObj).doubleValue();
        assertThat(actualSalary).isEqualTo(85000.00);
    }

    @Test
    @DisplayName("Should execute DELETE statement")
    void testExecuteDelete() {
        String sql = "DELETE FROM users WHERE id = ?";

        OperationResult<Integer> result = connector.executeUpdate("testdb", sql, "user3");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isEqualTo(1);

        // Verify the delete
        OperationResult<List<Map<String, Object>>> queryResult =
            connector.executeQuery("testdb", "SELECT * FROM users WHERE id = ?", "user3");

        assertThat(queryResult.getData().get()).isEmpty();
    }

    @Test
    @DisplayName("Should execute UPDATE affecting multiple rows")
    void testExecuteUpdateMultipleRows() {
        String sql = "UPDATE users SET status = ? WHERE department = ?";

        OperationResult<Integer> result = connector.executeUpdate(
            "testdb", sql, "ON_LEAVE", "IT"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should verify record exists with matching values")
    void testVerifyRecordSuccess() {
        Map<String, Object> expectedValues = Map.of(
            "USERNAME", "john.doe",
            "DEPARTMENT", "IT",
            "STATUS", "ACTIVE"
        );

        OperationResult<Boolean> result = connector.verifyRecord(
            "testdb", "users", "id", "user1", expectedValues
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isTrue();
        assertThat(result.getMessage()).contains("verification successful");
    }

    @Test
    @DisplayName("Should fail verification when value does not match")
    void testVerifyRecordValueMismatch() {
        Map<String, Object> expectedValues = Map.of(
            "USERNAME", "john.doe",
            "STATUS", "INACTIVE"  // Incorrect - actual is ACTIVE
        );

        OperationResult<Boolean> result = connector.verifyRecord(
            "testdb", "users", "id", "user1", expectedValues
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isFalse();
        assertThat(result.getMessage()).contains("Value mismatch");
        assertThat(result.getMessage()).contains("STATUS");
        assertThat(result.getErrorType()).isPresent();
        assertThat(result.getErrorType().get()).isEqualTo(ErrorType.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("Should fail verification when record does not exist")
    void testVerifyRecordNotFound() {
        Map<String, Object> expectedValues = Map.of("USERNAME", "nonexistent");

        OperationResult<Boolean> result = connector.verifyRecord(
            "testdb", "users", "id", "user999", expectedValues
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isFalse();
        assertThat(result.getMessage()).contains("not found");
        assertThat(result.getErrorType()).isPresent();
        assertThat(result.getErrorType().get()).isEqualTo(ErrorType.NOT_FOUND);
    }

    @Test
    @DisplayName("Should verify record with numeric values")
    void testVerifyRecordWithNumericValues() {
        Map<String, Object> expectedValues = Map.of(
            "USERNAME", "john.doe",
            "SALARY", 75000.00
        );

        OperationResult<Boolean> result = connector.verifyRecord(
            "testdb", "users", "id", "user1", expectedValues
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().get()).isTrue();
    }

    @Test
    @DisplayName("Should check if record exists")
    void testRecordExists() {
        OperationResult<Boolean> result = connector.recordExists(
            "testdb", "users", "id", "user1"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isTrue();
        assertThat(result.getMessage()).contains("exists");
    }

    @Test
    @DisplayName("Should return false when record does not exist")
    void testRecordNotExists() {
        OperationResult<Boolean> result = connector.recordExists(
            "testdb", "users", "id", "user999"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isFalse();
        assertThat(result.getMessage()).contains("not found");
    }

    @Test
    @DisplayName("Should get row count for table")
    void testGetRowCount() {
        OperationResult<Integer> result = connector.getRowCount("testdb", "users");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isEqualTo(3);
        assertThat(result.getMessage()).contains("3 row(s)");
    }

    @Test
    @DisplayName("Should get row count for empty table")
    void testGetRowCountEmptyTable() {
        // Create empty table
        jdbcTemplates.get("testdb").execute("CREATE TABLE empty_table (id INT)");

        OperationResult<Integer> result = connector.getRowCount("testdb", "empty_table");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isPresent();
        assertThat(result.getData().get()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should return configured database names")
    void testGetConfiguredDatabases() {
        Set<String> databases = connector.getConfiguredDatabases();

        assertThat(databases).containsExactlyInAnyOrder("testdb", "testdb2");
    }

    @Test
    @DisplayName("Should handle null values in verification")
    void testVerifyRecordWithNullValues() {
        // Insert record with null email
        connector.executeUpdate(
            "testdb",
            "INSERT INTO users (id, username, email, department, status) VALUES (?, ?, ?, ?, ?)",
            "user5", "test.user", null, "IT", "ACTIVE"
        );

        Map<String, Object> expectedValues = Map.of(
            "USERNAME", "test.user",
            "DEPARTMENT", "IT"
        );

        OperationResult<Boolean> result = connector.verifyRecord(
            "testdb", "users", "id", "user5", expectedValues
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().get()).isTrue();
    }

    @Test
    @DisplayName("Should handle SQL injection safely with parameterized queries")
    void testSqlInjectionProtection() {
        String maliciousInput = "user1' OR '1'='1";

        OperationResult<List<Map<String, Object>>> result =
            connector.executeQuery("testdb", "SELECT * FROM users WHERE id = ?", maliciousInput);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().get()).isEmpty();  // Should not return any results
    }

    @Test
    @DisplayName("Should execute query with multiple parameters")
    void testExecuteQueryWithMultipleParameters() {
        String sql = "SELECT * FROM users WHERE department = ? AND status = ?";

        OperationResult<List<Map<String, Object>>> result =
            connector.executeQuery("testdb", sql, "IT", "ACTIVE");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().get()).hasSize(1);
        assertThat(result.getData().get().get(0).get("USERNAME")).isEqualTo("john.doe");
    }
}
