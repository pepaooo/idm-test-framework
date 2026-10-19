package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.SqlEvidence;
import com.idm.test.engine.spi.StepOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    @Test
    @DisplayName("db.execute records the statement, masked parameters and the update count")
    void testExecuteEvidence() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS accounts (username VARCHAR(50), pwd VARCHAR(50))");

        StepOutcome outcome = new DbExecuteStep(connector).execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO accounts (username, pwd) VALUES (?, ?)",
                "params", List.of("jdoe", "S3cret!")), false);

        SqlEvidence evidence = (SqlEvidence) outcome.evidence();
        assertThat(evidence.database()).isEqualTo(DB);
        assertThat(evidence.params()).containsExactly("jdoe", "***");
        assertThat(evidence.updateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("db.verify records at most 20 rows and the real row count, on success and on failure")
    void testVerifyEvidence() {
        for (int i = 0; i < 25; i++) {
            jdbc.update("INSERT INTO sap_users (username, status) VALUES (?, 'ACTIVE')", "u" + i);
        }

        StepOutcome outcome = new DbVerifyStep(connector).execute(Map.of(
                "database", DB,
                "query", "SELECT username FROM sap_users",
                "expect", Map.of("rowCount", 3)), false);

        assertThat(outcome.success()).isFalse();
        SqlEvidence evidence = (SqlEvidence) outcome.evidence();
        assertThat(evidence.rows()).hasSize(20);
        assertThat(evidence.totalRows()).isEqualTo(25);
    }

    @Test
    @DisplayName("A SQL error records the statement; an unknown database records nothing")
    void testEvidenceOnErrors() {
        StepOutcome sqlError = new DbVerifyStep(connector).execute(Map.of(
                "database", DB, "query", "SELECT * FROM no_such_table", "expect", Map.of("rowCount", 0)), false);
        assertThat(sqlError.success()).isFalse();
        assertThat(((SqlEvidence) sqlError.evidence()).rows()).isNull();

        StepOutcome unknown = new DbVerifyStep(connector).execute(Map.of(
                "database", "nope", "query", "SELECT 1", "expect", Map.of("rowCount", 1)), false);
        assertThat(unknown.evidence()).isNull();
    }

    @Test
    @DisplayName("db.verify renders a row count mismatch in Spanish")
    void testVerifyRowCountMismatchRendersInSpanish() {
        StepOutcome outcome = new DbVerifyStep(connector).execute(Map.of(
                "database", DB, "query", "SELECT * FROM sap_users",
                "expect", Map.of("rowCount", 3)), false);

        assertThat(Messages.render(outcome.detail(), Locale.of("es")))
                .isEqualTo("db.verify en 'test-db' esperaba 3 fila(s), pero la consulta devolvió 0");
        assertThat(outcome.message())
                .isEqualTo("db.verify on 'test-db' expected 3 row(s) but the query returned 0");
    }

    @Test
    @DisplayName("db.execute renders a failure and a success in Spanish")
    void testExecuteRendersInSpanish() {
        DbExecuteStep execute = new DbExecuteStep(connector);
        String external = connector.executeUpdate("nope", "DELETE FROM sap_users").getMessage();

        StepOutcome failed = execute.execute(Map.of("database", "nope", "statement", "DELETE FROM sap_users"), false);
        StepOutcome done = execute.execute(Map.of("database", DB, "statement", "DELETE FROM sap_users"), false);

        // Since ITF-51 the connector's own frame is keyed too; the database names stay as they are.
        String available = external.substring(external.indexOf("Available databases: ") + "Available databases: ".length());
        assertThat(Messages.render(failed.detail(), Locale.of("es"))).isEqualTo("db.execute falló en 'nope':"
                + " La base de datos 'nope' no está configurada. Bases de datos disponibles: " + available);
        assertThat(Messages.render(done.detail(), Locale.of("es")))
                .isEqualTo("se ejecutó en 'test-db'; 0 fila(s) afectada(s)");
    }

    @Test
    @DisplayName("The database connector's own frames render in Spanish, the driver text unchanged")
    void testConnectorFramesRenderInSpanish() {
        StepOutcome unknown = new DbExecuteStep(connector).execute(
                Map.of("database", "nope", "statement", "DELETE FROM sap_users"), false);
        assertThat(unknown.message()).startsWith("db.execute failed on 'nope': Database 'nope' is not configured.");
        assertThat(Messages.render(unknown.detail(), Locale.of("es")))
                .startsWith("db.execute falló en 'nope': La base de datos 'nope' no está configurada.");

        StepOutcome bad = new DbVerifyStep(connector).execute(Map.of(
                "database", DB, "query", "SELECT * FROM no_such_table", "expect", Map.of("rowCount", 0)), false);
        String prefix = "db.verify failed on '" + DB + "': Query failed: ";
        assertThat(bad.message()).startsWith(prefix);
        assertThat(Messages.render(bad.detail(), Locale.of("es")))
                .isEqualTo("db.verify falló en '" + DB + "': La consulta falló: " + bad.message().substring(prefix.length()));
    }

    @Test
    @DisplayName("Failure kinds: a mismatch or SQL error is a finding; an unknown database is a setup problem")
    void testFailureKinds() {
        jdbc.update("INSERT INTO sap_users VALUES (?,?,?)", "kind", "PENDING", 0);
        StepOutcome mismatch = new DbVerifyStep(connector).execute(Map.of(
                "database", DB,
                "query", "SELECT * FROM sap_users WHERE username = 'kind'",
                "expect", Map.of("row", Map.of("status", "ACTIVE"))), false);
        assertThat(mismatch.success()).isFalse();
        assertThat(mismatch.kind()).isEqualTo(com.idm.test.engine.spi.FailureKind.FINDING);

        StepOutcome sqlError = new DbExecuteStep(connector).execute(Map.of(
                "database", DB, "statement", "INSERT INTO no_such_table (a) VALUES (1)"), false);
        assertThat(sqlError.kind()).as(sqlError.message()).isEqualTo(com.idm.test.engine.spi.FailureKind.FINDING);

        StepOutcome unknown = new DbExecuteStep(connector).execute(Map.of(
                "database", "no-such-db", "statement", "SELECT 1"), false);
        assertThat(unknown.kind()).isEqualTo(com.idm.test.engine.spi.FailureKind.SETUP);

        StepOutcome unknownVerify = new DbVerifyStep(connector).execute(Map.of(
                "database", "no-such-db", "query", "SELECT 1", "expect", Map.of("rowCount", 1)), false);
        assertThat(unknownVerify.kind()).isEqualTo(com.idm.test.engine.spi.FailureKind.SETUP);
    }

    @Test
    @DisplayName("Failure kinds: a database on a closed port is a setup problem")
    void testUnreachableDatabaseKind() {
        MultiDatabaseConnector closed = new MultiDatabaseConnector(Map.of(DB, new JdbcTemplate(
                new DriverManagerDataSource("jdbc:h2:tcp://localhost:1/mem:nowhere", "sa", ""))));

        StepOutcome execute = new DbExecuteStep(closed).execute(Map.of(
                "database", DB, "statement", "DELETE FROM sap_users"), false);
        assertThat(execute.success()).isFalse();
        assertThat(execute.kind()).as(execute.message()).isEqualTo(com.idm.test.engine.spi.FailureKind.SETUP);

        StepOutcome verify = new DbVerifyStep(closed).execute(Map.of(
                "database", DB, "query", "SELECT 1", "expect", Map.of("rowCount", 1)), false);
        assertThat(verify.success()).isFalse();
        assertThat(verify.kind()).as(verify.message()).isEqualTo(com.idm.test.engine.spi.FailureKind.SETUP);
    }
}
