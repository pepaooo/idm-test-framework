package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.engine.spi.StepOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the database step types against H2.
 * <p>
 * Builds the JdbcTemplate directly rather than through Spring, because this module has no
 * Spring context by design.
 */
@DisplayName("Database step types")
class DbStepsTest {

    private static final String DB = "test-db";

    private static JdbcTemplate jdbc;
    private static MultiDatabaseConnector connector;

    @BeforeAll
    static void startDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:dbsteps;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sap_users ("
                + "username VARCHAR(50), status VARCHAR(20), attempts INT)");
        connector = new MultiDatabaseConnector(Map.of(DB, jdbc));
    }

    @BeforeEach
    void clearTable() {
        jdbc.update("DELETE FROM sap_users");
    }

    // ------------------------------------------------------------------
    // db.execute
    // ------------------------------------------------------------------

    @Test
    @DisplayName("db.execute should insert a row and report the affected count")
    void testExecuteInsert() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO sap_users (username, status) VALUES (?, ?)",
                "params", List.of("jdoe", "ACTIVE")), true);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sap_users WHERE username = 'jdoe'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("db.execute should run its declared undo on unwind")
    void testExecuteUndo() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO sap_users (username, status) VALUES (?, ?)",
                "params", List.of("jdoe", "ACTIVE"),
                "undo", Map.of(
                        "statement", "DELETE FROM sap_users WHERE username = ?",
                        "params", List.of("jdoe"))), false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.undo()).isNotNull();

        outcome.undo().run();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sap_users", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("db.execute should register no undo when none was declared")
    void testExecuteWithoutUndo() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement",
                "INSERT INTO sap_users (username, status) VALUES ('x', 'y')"), false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("db.execute should report failure rather than throw on bad SQL")
    void testExecuteBadSqlFails() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO no_such_table (a) VALUES (1)"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.undo()).isNull();
    }

    @Test
    @DisplayName("db.execute should report failure for an unknown database name")
    void testExecuteUnknownDatabaseFails() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", "no-such-db",
                "statement", "INSERT INTO sap_users (username) VALUES ('x')"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("no-such-db");
    }

    @Test
    @DisplayName("db.execute should reject an undo block with no statement")
    void testExecuteRejectsUndoWithoutStatement() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO sap_users (username) VALUES ('x')",
                "undo", Map.of("params", List.of("x"))), false);

        // Caught before the statement runs: an undo that cannot run is a cleanup hole, and
        // discovering it at unwind time is far too late.
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("undo");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sap_users", Integer.class))
                .isZero();
    }
}
