package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.engine.spi.StepOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
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
    @DisplayName("db.execute should report the affected row count when it succeeds")
    void testExecuteReturnsAffectedRows() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement",
                "INSERT INTO sap_users (username, status) VALUES ('x', 'y')"), false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(1);
    }

    @Test
    @DisplayName("db.execute should report failure rather than throw on bad SQL")
    void testExecuteBadSqlFails() {
        DbExecuteStep execute = new DbExecuteStep(connector);

        StepOutcome outcome = execute.execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO no_such_table (a) VALUES (1)"), false);

        assertThat(outcome.success()).isFalse();
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
    @DisplayName("db.execute no longer accepts undo:")
    void testExecuteDoesNotAcceptUndo() {
        assertThat(new DbExecuteStep(null).acceptedParams()).doesNotContain("undo");
    }

    @Test
    @DisplayName("0 affected rows is still success, so a cleanup DELETE of absent rows passes")
    void testZeroAffectedRowsSucceeds() {
        StepOutcome outcome = new DbExecuteStep(connector).execute(Map.of(
                "database", DB,
                "statement", "DELETE FROM sap_users WHERE username = ?",
                "params", List.of("nobody")), false);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isEqualTo(0);
        assertThat(outcome.message()).contains("0 row(s) affected");
    }

    // ------------------------------------------------------------------
    // db.verify
    // ------------------------------------------------------------------

    @Test
    @DisplayName("db.verify should pass when rowCount and row both match")
    void testVerifyMatches() {
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 3);
        DbVerifyStep verify = new DbVerifyStep(connector);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users WHERE username = ?",
                "params", List.of("jdoe"),
                "expect", Map.of("rowCount", 1, "row", Map.of("status", "ACTIVE"))), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("db.verify should match a column regardless of the database's casing")
    void testVerifyIsCaseInsensitiveOnColumnNames() {
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 3);
        DbVerifyStep verify = new DbVerifyStep(connector);

        // H2 stores unquoted identifiers uppercase, so the row's key is STATUS. A scenario
        // author writes status. Spring's LinkedCaseInsensitiveMap bridges the two — this
        // test exists so a refactor that copies rows into a plain HashMap gets caught.
        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users",
                "expect", Map.of("row", Map.of("status", "ACTIVE"))), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("db.verify should match a numeric column across JDBC and YAML types")
    void testVerifyMatchesNumericAcrossTypes() {
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 3);
        DbVerifyStep verify = new DbVerifyStep(connector);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users",
                "expect", Map.of("row", Map.of("attempts", 3))), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("db.verify should fail on a value mismatch and name the column")
    void testVerifyValueMismatchFails() {
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "PENDING", 0);
        DbVerifyStep verify = new DbVerifyStep(connector);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users",
                "expect", Map.of("row", Map.of("status", "ACTIVE"))), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("status").contains("ACTIVE").contains("PENDING");
    }

    @Test
    @DisplayName("db.verify should fail when rowCount does not match")
    void testVerifyRowCountMismatchFails() {
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 1);
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "asmith", "ACTIVE", 1);
        DbVerifyStep verify = new DbVerifyStep(connector);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users",
                "expect", Map.of("rowCount", 1)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("2");
    }

    @Test
    @DisplayName("db.verify should reject a row matcher against more than one row")
    void testVerifyRowMatcherRequiresExactlyOneRow() {
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 1);
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "asmith", "ACTIVE", 1);
        DbVerifyStep verify = new DbVerifyStep(connector);

        // Matching "the first row" would depend on an unordered result set: intermittently
        // green, which is worse than red.
        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users",
                "expect", Map.of("row", Map.of("status", "ACTIVE"))), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("exactly one row");
    }

    @Test
    @DisplayName("db.verify should reject an expect block that asserts nothing")
    void testVerifyRejectsEmptyExpect() {
        DbVerifyStep verify = new DbVerifyStep(connector);

        // The ITF-6 vacuous-assertion trap: an expect with nothing in it would pass having
        // compared nothing at all.
        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users",
                "expect", Map.of()), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("rowCount").contains("row");
    }

    @Test
    @DisplayName("db.verify should reject an unknown key inside expect")
    void testVerifyRejectsUnknownExpectKey() {
        DbVerifyStep verify = new DbVerifyStep(connector);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users",
                "expect", Map.of("rowCont", 1)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("rowCont");
    }

    @Test
    @DisplayName("db.verify should reject an empty row map that would verify nothing")
    void testVerifyRejectsEmptyRow() {
        // expect: { row: {} } passes the "expect is non-empty" guard (it has a row key) and
        // the "row is a Map" guard (it is, just an empty one) — the column loop then iterates
        // zero entries and db.verify would pass against any single row whatsoever.
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "PENDING", 0);
        DbVerifyStep verify = new DbVerifyStep(connector);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users",
                "expect", Map.of("row", Map.of())), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("row");
    }

    @Test
    @DisplayName("db.verify should still allow rowCount: 0 as a legitimate assertion")
    void testVerifyRowCountZeroStillWorks() {
        // Guards against a fix for the empty-row-map hole overreaching and rejecting a
        // legitimate zero-rows assertion, which is a different (and valid) thing entirely.
        DbVerifyStep verify = new DbVerifyStep(connector);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users WHERE username = 'nobody'",
                "expect", Map.of("rowCount", 0)), false);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    @DisplayName("db.verify should reject a present-but-null row that would verify nothing")
    void testVerifyRejectsNullRow() {
        // A bare `row:` key with no value parses as { row: null } — present per containsKey,
        // but every earlier guard here started with "expect.get(ROW) != null", so a null value
        // skipped them all, and execute() then did `(Map<String, Object>) expect.get(ROW)` and
        // called .entrySet() on the resulting null, throwing an NPE — a StepType.execute must
        // never throw. Map.of cannot hold a null value, which is exactly why this case slipped
        // past every earlier round of tests — a HashMap is required to express it.
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "PENDING", 0);
        DbVerifyStep verify = new DbVerifyStep(connector);

        Map<String, Object> expect = new HashMap<>();
        expect.put("row", null);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users",
                "expect", expect), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("row");
    }

    @Test
    @DisplayName("db.verify should reject a null expected value for a row column "
            + "(a misspelled column name)")
    void testVerifyRejectsNullColumnValue() {
        // ITF-9: a misspelled column, e.g. `staus:` instead of `status:`, is a YAML bare key
        // whose value parses as null — indistinguishable from an explicit `staus: null` after
        // Map.get(). ValueComparison.equal(null, null) is true, so without this guard the step
        // would report "verified 1 row(s)" having compared nothing for that column. Map.of
        // cannot hold a null value, which is why a HashMap is required to express this case.
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 3);
        DbVerifyStep verify = new DbVerifyStep(connector);

        Map<String, Object> row = new HashMap<>();
        row.put("staus", null);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users WHERE username = 'jdoe'",
                "expect", Map.of("row", row)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("staus");
    }

    @Test
    @DisplayName("db.verify should fail rather than throw when expect itself is not a map")
    void testVerifyRejectsNonMapExpect() {
        // params.get("expect") is an unchecked cast inside execute(); a scenario writing
        // `expect: something` (a scalar) must not turn into a ClassCastException — a
        // StepType.execute implementation must never throw.
        DbVerifyStep verify = new DbVerifyStep(connector);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users",
                "expect", "something"), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("expect");
    }

    @Test
    @DisplayName("db.verify should bind the rows when a capture was requested")
    void testVerifyCaptureIsTheRows() {
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "jdoe", "ACTIVE", 3);
        DbVerifyStep verify = new DbVerifyStep(connector);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users",
                "expect", Map.of("rowCount", 1)), true);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.value()).isInstanceOf(List.class);
        assertThat((List<?>) outcome.value()).hasSize(1);
    }

    @Test
    @DisplayName("db.verify should be retryable so the engine can wait on provisioning")
    void testVerifyIsRetryable() {
        assertThat(new DbVerifyStep(connector).retryable()).isTrue();
    }

    @Test
    @DisplayName("db.verify should report failure rather than throw on bad SQL")
    void testVerifyBadSqlFails() {
        DbVerifyStep verify = new DbVerifyStep(connector);

        StepOutcome outcome = verify.execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM no_such_table",
                "expect", Map.of("rowCount", 0)), false);

        assertThat(outcome.success()).isFalse();
    }

    @Test
    @DisplayName("A malformed db.verify or unknown database is final; a missing table is not")
    void testDbVerifyFinalFailures() {
        DbVerifyStep verify = new DbVerifyStep(connector);

        assertThat(verify.execute(Map.of("database", DB, "query", "SELECT 1", "expect", Map.of()), false)
                .isFinal()).isTrue();
        assertThat(verify.execute(Map.of("database", DB, "query", "SELECT 1", "expect", Map.of("bogus", 1)), false)
                .isFinal()).isTrue();
        assertThat(verify.execute(Map.of("database", "no-such-db", "query", "SELECT 1",
                "expect", Map.of("rowCount", 1)), false).isFinal()).isTrue();

        StepOutcome missingTable = verify.execute(Map.of("database", DB,
                "query", "SELECT * FROM not_created_yet", "expect", Map.of("rowCount", 1)), false);
        assertThat(missingTable.success()).isFalse();
        assertThat(missingTable.isFinal()).isFalse();
    }
}
