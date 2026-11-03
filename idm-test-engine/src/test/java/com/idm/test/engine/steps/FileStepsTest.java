package com.idm.test.engine.steps;

import com.idm.test.connector.file.client.DelimitedFileConnector;
import com.idm.test.connector.file.client.FolderAccess;
import com.idm.test.connector.file.client.FolderSettings;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.ValidationException;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.result.CleanupStatus;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.Check;
import com.idm.test.engine.spi.FailureKind;
import com.idm.test.engine.spi.FileEvidence;
import com.idm.test.engine.spi.StepContext;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the three file step types against a real {@link DelimitedFileConnector} over temporary
 * folders: one per access mode.
 */
@DisplayName("File step types")
class FileStepsTest {

    private static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");
    private static final StepContext SINCE_LONG_AGO = new StepContext(LONG_AGO);

    @TempDir
    Path root;

    private Path inbox;
    private Path outbox;
    private Path shared;
    private DelimitedFileConnector connector;
    private FileWriteStep write;
    private FileVerifyStep verify;
    private FileDeleteStep delete;

    @BeforeEach
    void setUp() throws IOException {
        inbox = Files.createDirectory(root.resolve("in"));
        outbox = Files.createDirectory(root.resolve("out"));
        shared = Files.createDirectory(root.resolve("shared"));
        connector = new DelimitedFileConnector(Map.of(
                "hr-in", FolderSettings.of(inbox.toString(), FolderAccess.WRITE),
                "hr-out", FolderSettings.of(outbox.toString(), FolderAccess.READ),
                "shared", FolderSettings.of(shared.toString(), FolderAccess.READ_WRITE),
                "gone", FolderSettings.of(root.resolve("gone").toString(), FolderAccess.READ_WRITE)));
        write = new FileWriteStep(connector);
        verify = new FileVerifyStep(connector);
        delete = new FileDeleteStep(connector);
    }

    /** A mutable parameter map, as the engine hands a step, in the order given. */
    private static Map<String, Object> params(Object... keysAndValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            params.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return params;
    }

    private static Map<String, Object> row(Object... keysAndValues) {
        return params(keysAndValues);
    }

    private StepOutcome writeShared(String file, List<?> rows) {
        return write.execute(params("folder", "shared", "file", file, "rows", rows), true);
    }

    private StepOutcome verifyShared(String file, Map<String, Object> where, Map<String, Object> expect) {
        Map<String, Object> params = params("folder", "shared", "file", file, "expect", expect);
        if (where != null) {
            params.put("where", where);
        }
        return verify.execute(params, true, SINCE_LONG_AGO);
    }

    private static List<String> namesIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static void assertSetupFinal(StepOutcome outcome) {
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.isFinal()).as(outcome.message()).isTrue();
        assertThat(outcome.kind()).isEqualTo(FailureKind.SETUP);
    }

    private static void assertRetryable(StepOutcome outcome, FailureKind kind) {
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.isFinal()).as(outcome.message()).isFalse();
        assertThat(outcome.kind()).isEqualTo(kind);
    }

    // ------------------------------------------------------------------
    // file.write
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("file.write")
    class Write {

        @Test
        @DisplayName("Should write the header from the first row's keys, in order, and capture the file")
        void testWritesFromFirstRow() throws IOException {
            StepOutcome outcome = writeShared("hr.csv", List.of(
                    row("employeeId", "E1", "givenName", "Ana", "surname", "Ruiz, Peña"),
                    row("employeeId", "E2", "givenName", "Bo", "surname", "Li")));

            assertThat(outcome.success()).as(outcome.message()).isTrue();
            assertThat(Files.readString(shared.resolve("hr.csv")))
                    .isEqualTo("employeeId,givenName,surname\r\nE1,Ana,\"Ruiz, Peña\"\r\nE2,Bo,Li\r\n");
            assertThat(outcome.value()).isEqualTo(Map.of("folder", "shared", "name", "hr.csv", "rows", 2,
                    "size", Files.size(shared.resolve("hr.csv"))));
            assertThat(outcome.message()).isEqualTo("wrote 2 row(s) to 'hr.csv' in folder 'shared'");
            assertThat(outcome.evidence()).isInstanceOfSatisfying(FileEvidence.class, evidence -> {
                assertThat(evidence.operation()).isEqualTo("write");
                assertThat(evidence.header()).containsExactly("employeeId", "givenName", "surname");
                assertThat(evidence.rows()).hasSize(2);
            });
        }

        @Test
        @DisplayName("Should follow columns:, writing a missing key or a null as an empty field")
        void testColumnsOrderAndEmptyFields() throws IOException {
            Map<String, Object> withNull = row("mail", null, "id", "E2");
            StepOutcome outcome = write.execute(params("folder", "shared", "file", "c.csv",
                    "columns", List.of("id", "mail", "note"),
                    "rows", List.of(row("id", "E1", "mail", "a@b.c"), withNull)), false);

            assertThat(outcome.success()).as(outcome.message()).isTrue();
            assertThat(Files.readString(shared.resolve("c.csv"))).isEqualTo("id,mail,note\r\nE1,a@b.c,\r\nE2,,\r\n");
        }

        @Test
        @DisplayName("Should write numbers and booleans as their YAML text")
        void testScalarsAsText() throws IOException {
            writeShared("s.csv", List.of(row("n", 42, "d", 1.5, "b", true)));

            assertThat(Files.readString(shared.resolve("s.csv"))).isEqualTo("n,d,b\r\n42,1.5,true\r\n");
        }

        @Test
        @DisplayName("Should write a header-only file when rows is empty and columns are given")
        void testHeaderOnly() throws IOException {
            StepOutcome outcome = write.execute(params("folder", "shared", "file", "empty.csv",
                    "columns", List.of("id", "mail"), "rows", List.of()), false);

            assertThat(outcome.success()).as(outcome.message()).isTrue();
            assertThat(Files.readString(shared.resolve("empty.csv"))).isEqualTo("id,mail\r\n");
        }

        @Test
        @DisplayName("Should honour the format overrides")
        void testOverrides() throws IOException {
            StepOutcome outcome = write.execute(params("folder", "shared", "file", "o.csv",
                    "rows", List.of(row("a", "x;y", "b", "z")),
                    "delimiter", ";", "quote", "'", "lineEnding", "lf", "header", "false"), false);

            assertThat(outcome.success()).as(outcome.message()).isTrue();
            assertThat(Files.readString(shared.resolve("o.csv"))).isEqualTo("'x;y';z\n");
        }

        @Test
        @DisplayName("Should refuse malformed rows before writing anything")
        void testMalformedRows() throws IOException {
            assertSetupFinal(writeShared("a.csv", List.of(row("id", "E1"), row("id", "E2", "mial", "x"))));
            assertThat(writeShared("a.csv", List.of(row("id", "E1"), row("id", "E2", "mial", "x"))).message())
                    .contains("row 2 has 'mial', which is not one of the columns [id]");
            assertSetupFinal(write.execute(params("folder", "shared", "file", "a.csv", "rows", "E1"), false));
            assertSetupFinal(writeShared("a.csv", List.of("E1")));
            assertSetupFinal(writeShared("a.csv", List.of()));
            assertSetupFinal(writeShared("a.csv", List.of(row("id", List.of("x")))));
            assertSetupFinal(write.execute(params("folder", "shared", "file", "a.csv",
                    "columns", List.of(), "rows", List.of()), false));
            assertSetupFinal(write.execute(params("folder", "shared", "file", "a.csv",
                    "rows", List.of(row("id", "E1")), "lineEnding", "CR"), false));
            assertSetupFinal(write.execute(params("folder", "shared", "file", "a.csv",
                    "rows", List.of(row("id", "E1")), "header", "yes"), false));
            assertThat(namesIn(shared)).isEmpty();
        }

        @Test
        @DisplayName("Should fail as a final setup problem for an unknown folder, a read folder or an existing file")
        void testFinalFailures() throws IOException {
            List<Map<String, Object>> rows = List.of(row("id", "E1"));
            assertSetupFinal(write.execute(params("folder", "nope", "file", "a.csv", "rows", rows), false));
            assertThat(write.execute(params("folder", "nope", "file", "a.csv", "rows", rows), false).message())
                    .contains("Folder 'nope' is not configured. Available folders: [gone, hr-in, hr-out, shared]");
            assertSetupFinal(write.execute(params("folder", "hr-out", "file", "a.csv", "rows", rows), false));
            assertSetupFinal(write.execute(params("folder", "gone", "file", "a.csv", "rows", rows), false));
            Files.writeString(shared.resolve("taken.csv"), "keep");
            assertSetupFinal(writeShared("taken.csv", rows));
            assertSetupFinal(writeShared("../escape.csv", rows));
            assertThat(Files.readString(shared.resolve("taken.csv"))).isEqualTo("keep");
        }

        @Test
        @DisplayName("Should word a connector problem in Spanish")
        void testSpanish() {
            StepOutcome outcome = write.execute(params("folder", "nope", "file", "a.csv",
                    "rows", List.of(row("id", "E1"))), false);

            assertThat(Messages.render(outcome.detail(), Locale.forLanguageTag("es")))
                    .isEqualTo("file.write falló en la carpeta 'nope': La carpeta 'nope' no está configurada. "
                            + "Carpetas disponibles: [gone, hr-in, hr-out, shared]");
        }

        @Test
        @DisplayName("Should mask a secret-named column in the evidence")
        void testMasksEvidence() {
            StepOutcome outcome = writeShared("p.csv", List.of(row("id", "E1", "password", "hunter2")));

            assertThat(outcome.evidence().toString()).doesNotContain("hunter2");
        }
    }

    // ------------------------------------------------------------------
    // file.verify
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("file.verify")
    class Verify {

        @BeforeEach
        void writeFile() {
            assertThat(writeShared("out.csv", List.of(
                    row("employeeId", "E1", "status", "provisioned", "attempts", "1"),
                    row("employeeId", "E2", "status", "pending", "attempts", "3"),
                    row("employeeId", "E3", "status", "pending", "attempts", "3"))).success()).isTrue();
        }

        @Test
        @DisplayName("Should select rows with where: and verify one, ignoring the case of column names")
        void testWhereAndRow() {
            StepOutcome outcome = verifyShared("out.csv", Map.of("EMPLOYEEID", "E1"),
                    Map.of("rowCount", 1, "row", Map.of("Status", "provisioned", "attempts", 1)));

            assertThat(outcome.success()).as(outcome.message()).isTrue();
            assertThat(outcome.message()).isEqualTo("verified 1 row(s) in 'out.csv' in folder 'shared'");
            assertThat(outcome.checks()).extracting(Check::name).containsExactly("row count", "Status", "attempts");
            assertThat(outcome.value()).isEqualTo(Map.of("name", "out.csv", "rows", List.of(
                    Map.of("employeeId", "E1", "status", "provisioned", "attempts", "1"))));
            assertThat(outcome.evidence()).isInstanceOfSatisfying(FileEvidence.class, evidence -> {
                assertThat(evidence.selectedRows()).isEqualTo(1);
                assertThat(evidence.rows()).filteredOn(FileEvidence.Row::selected)
                        .extracting(FileEvidence.Row::number).containsExactly(1);
            });
        }

        @Test
        @DisplayName("Should count every row without where:")
        void testNoWhere() {
            assertThat(verifyShared("out.csv", null, Map.of("rowCount", 3)).success()).isTrue();
            assertThat(verifyShared("out.csv", Map.of("status", "pending"), Map.of("rowCount", 2)).success())
                    .isTrue();
        }

        @Test
        @DisplayName("Should report a difference as a retryable finding, comparing every expectation")
        void testMismatch() {
            StepOutcome outcome = verifyShared("out.csv", Map.of("employeeId", "E2"),
                    Map.of("row", Map.of("status", "provisioned")));

            assertRetryable(outcome, FailureKind.FINDING);
            assertThat(outcome.message())
                    .isEqualTo("file.verify on 'out.csv' found a mismatch for column 'status': "
                            + "expected=provisioned, actual=pending");
            assertThat(outcome.evidence()).isInstanceOf(FileEvidence.class);

            StepOutcome count = verifyShared("out.csv", Map.of("status", "pending"), Map.of("rowCount", 1));
            assertRetryable(count, FailureKind.FINDING);
            assertThat(count.message()).isEqualTo("file.verify on 'out.csv' expected 1 row(s) but where: selected 2");
        }

        @Test
        @DisplayName("Should need exactly one selected row for row:")
        void testRowNeedsOne() {
            StepOutcome none = verifyShared("out.csv", Map.of("employeeId", "E9"), Map.of("row", Map.of("status", "x")));
            StepOutcome two = verifyShared("out.csv", Map.of("status", "pending"), Map.of("row", Map.of("status", "pending")));

            assertRetryable(none, FailureKind.FINDING);
            assertRetryable(two, FailureKind.FINDING);
            assertThat(two.message()).contains("needs exactly one row, but where: selected 2");
        }

        @Test
        @DisplayName("Should refuse an expect or where that would verify nothing, before reading")
        void testVacuous() {
            for (Object expect : new Object[]{"yes", Map.of(), Map.of("rows", 1), Map.of("row", Map.of())}) {
                Map<String, Object> params = params("folder", "nope", "file", "out.csv", "expect", expect);
                assertSetupFinal(verify.execute(params, false, SINCE_LONG_AGO));
            }
            Map<String, Object> typo = new HashMap<>();
            typo.put("staus", null);
            assertSetupFinal(verify.execute(params("folder", "nope", "file", "out.csv",
                    "expect", Map.of("row", typo)), false, SINCE_LONG_AGO));
            for (Object where : new Object[]{"E1", Map.of(), typo}) {
                Map<String, Object> params = params("folder", "nope", "file", "out.csv",
                        "where", where, "expect", Map.of("rowCount", 1));
                StepOutcome outcome = verify.execute(params, false, SINCE_LONG_AGO);
                assertSetupFinal(outcome);
                assertThat(outcome.message()).doesNotContain("not configured");
            }
        }

        @Test
        @DisplayName("Should fail finally on a where: column the file does not have")
        void testUnknownWhereColumn() {
            StepOutcome outcome = verifyShared("out.csv", Map.of("employee", "E1"), Map.of("rowCount", 1));

            assertSetupFinal(outcome);
            assertThat(outcome.message()).contains("has no column 'employee' for where:")
                    .contains("[employeeId, status, attempts]");
        }

        @Test
        @DisplayName("Should keep waiting while no file matches, and say how many older ones it ignored")
        void testNoFileYet() throws IOException {
            Path old = Files.writeString(shared.resolve("old-out.csv"), "id\r\nE1\r\n");
            Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(1))));

            StepOutcome outcome = verify.execute(params("folder", "shared", "file", "*-out.csv",
                    "expect", Map.of("rowCount", 1)), false, new StepContext(Instant.now()));

            assertRetryable(outcome, FailureKind.FINDING);
            assertThat(outcome.message()).isEqualTo("file.verify found no file matching '*-out.csv' in folder "
                    + "'shared' modified since the scenario started (1 older matching file(s) ignored)");
            assertThat(outcome.evidence()).isEqualTo(FileEvidence.notFound("shared", "*-out.csv"));
        }

        @Test
        @DisplayName("Should keep waiting on a file that does not parse yet, as a finding")
        void testUnparsable() throws IOException {
            Files.writeString(shared.resolve("half.csv"), "id,status\r\nE1,\"open");

            StepOutcome outcome = verifyShared("half.csv", null, Map.of("rowCount", 1));

            assertRetryable(outcome, FailureKind.FINDING);
            assertThat(outcome.message()).startsWith("file.verify failed on folder 'shared': File 'half.csv'");
            assertThat(outcome.evidence()).isInstanceOf(FileEvidence.class);
        }

        @Test
        @DisplayName("Should fail finally on an unknown, missing or write-only folder, and a header-less format")
        void testFinalFailures() {
            Map<String, Object> expect = Map.of("rowCount", 1);
            assertSetupFinal(verify.execute(params("folder", "nope", "file", "*.csv", "expect", expect),
                    false, SINCE_LONG_AGO));
            assertSetupFinal(verify.execute(params("folder", "gone", "file", "*.csv", "expect", expect),
                    false, SINCE_LONG_AGO));
            assertSetupFinal(verify.execute(params("folder", "hr-in", "file", "*.csv", "expect", expect),
                    false, SINCE_LONG_AGO));
            assertSetupFinal(verify.execute(params("folder", "shared", "file", "*.csv", "expect", expect,
                    "header", false), false, SINCE_LONG_AGO));
            assertSetupFinal(verify.execute(params("folder", "shared", "file", "../*.csv", "expect", expect),
                    false, SINCE_LONG_AGO));
        }

        @Test
        @DisplayName("Should read a file in a read-only folder")
        void testReadFolder() throws IOException {
            Files.writeString(outbox.resolve("driver-out.csv"), "id;status\r\nE1;ok\r\n");

            StepOutcome outcome = verify.execute(params("folder", "hr-out", "file", "driver-*.csv", "delimiter", ";",
                    "where", Map.of("id", "E1"), "expect", Map.of("row", Map.of("status", "ok"))), false,
                    SINCE_LONG_AGO);

            assertThat(outcome.success()).as(outcome.message()).isTrue();
            assertThat(outcome.value()).isNull();
        }

        @Test
        @DisplayName("Should be retryable, and accept every format parameter except write-only ones")
        void testParameters() {
            assertThat(verify.retryable()).isTrue();
            assertThat(verify.acceptedParams()).contains("delimiter", "quote", "encoding", "header", "where", "wait")
                    .doesNotContain("lineEnding", "path", "access", "tempSuffix", "clockSkew");
            assertThat(write.acceptedParams()).contains("lineEnding", "columns")
                    .doesNotContain("path", "access", "tempSuffix", "clockSkew", "wait");
            assertThat(delete.acceptedParams()).containsExactlyInAnyOrder("folder", "file");
        }
    }

    // ------------------------------------------------------------------
    // file.delete
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("file.delete")
    class Delete {

        @Test
        @DisplayName("Should delete a file, then treat it as already absent")
        void testDelete() throws IOException {
            Files.writeString(inbox.resolve("hr.csv"), "x");

            StepOutcome first = delete.execute(params("folder", "hr-in", "file", "hr.csv"), true);
            StepOutcome again = delete.execute(params("folder", "hr-in", "file", "hr.csv"), true);

            assertThat(first.success()).isTrue();
            assertThat(first.value()).isEqualTo(true);
            assertThat(first.message()).isEqualTo("deleted 'hr.csv' in folder 'hr-in'");
            assertThat(again.success()).isTrue();
            assertThat(again.value()).isEqualTo(false);
            assertThat(again.message()).isEqualTo("'hr.csv' in folder 'hr-in' is already absent");
            assertThat(again.evidence()).isEqualTo(FileEvidence.deleted("hr-in", "hr.csv", false));
            assertThat(namesIn(inbox)).isEmpty();
        }

        @Test
        @DisplayName("Should fail finally in a read folder or for a pattern")
        void testFinalFailures() throws IOException {
            Files.writeString(outbox.resolve("keep.csv"), "x");

            assertSetupFinal(delete.execute(params("folder", "hr-out", "file", "keep.csv"), false));
            assertSetupFinal(delete.execute(params("folder", "shared", "file", "*.csv"), false));
            assertThat(outbox.resolve("keep.csv")).exists();
        }
    }

    // ------------------------------------------------------------------
    // Validation of literal file names
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Validation")
    class Validation {

        private final Map<String, StepType> registry = Map.of(
                "file.write", new FileWriteStep(null),
                "file.verify", new FileVerifyStep(null),
                "file.delete", new FileDeleteStep(null));
        private final ScenarioValidator validator = new ScenarioValidator(registry, Set.of("random-user"));

        private Scenario scenario(List<StepSpec> steps, StepSpec... cleanup) {
            return new Scenario("Files", null, Map.of("user", new FixtureSpec("random-user", Map.of())),
                    steps, List.of(cleanup));
        }

        private static StepSpec writeSpec(String file) {
            return new StepSpec("file.write", Map.of("folder", "hr-in", "file", file,
                    "rows", List.of(Map.of("id", "E1"))));
        }

        @Test
        @DisplayName("Should accept plain names, patterns in file.verify, and expressions left for run time")
        void testAccepts() {
            Scenario valid = scenario(List.of(
                            writeSpec("hr-${user.userId}.csv"),
                            writeSpec("hr.csv"),
                            new StepSpec("file.verify", Map.of("folder", "hr-out", "file", "hr-out-*.csv",
                                    "expect", Map.of("rowCount", 1), "wait", true))),
                    new StepSpec("file.delete", Map.of("folder", "hr-in", "file", "hr-${user.userId}.csv")));

            assertThatCode(() -> validator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should reject a literal name that leaves its folder, on every file step")
        void testRejectsUnsafeNames() {
            for (String name : List.of("../x.csv", "/etc/passwd", "sub/x.csv", "a\\b.csv", "C:x.csv", " x.csv")) {
                assertThatThrownBy(() -> validator.validate(scenario(List.of(writeSpec(name))), "bad.yaml"))
                        .isInstanceOf(ValidationException.class).hasMessageContaining("is not a plain file name");
                assertThatThrownBy(() -> validator.validate(scenario(List.of(new StepSpec("file.verify",
                        Map.of("folder", "f", "file", name, "expect", Map.of("rowCount", 1))))), "bad.yaml"))
                        .isInstanceOf(ValidationException.class).hasMessageContaining("is not a plain file name");
                assertThatThrownBy(() -> validator.validate(scenario(List.of(writeSpec("ok.csv")),
                        new StepSpec("file.delete", Map.of("folder", "f", "file", name))), "bad.yaml"))
                        .isInstanceOf(ValidationException.class).hasMessageContaining("cleanup entry")
                        .hasMessageContaining("is not a plain file name");
            }
        }

        @Test
        @DisplayName("Should reject a pattern in file.delete and file.write")
        void testRejectsPatterns() {
            assertThatThrownBy(() -> validator.validate(scenario(List.of(writeSpec("ok.csv")),
                    new StepSpec("file.delete", Map.of("folder", "f", "file", "*.csv"))), "bad.yaml"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("'file: *.csv' is a pattern; this step needs one exact file name");
            assertThatThrownBy(() -> validator.validate(scenario(List.of(writeSpec("hr-?.csv"))), "bad.yaml"))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("is a pattern");
        }

        @Test
        @DisplayName("Should reject settings-only keys as unknown parameters")
        void testRejectsSettingsKeys() {
            for (String key : List.of("path", "access", "tempSuffix", "clockSkew")) {
                Map<String, Object> params = new LinkedHashMap<>(Map.of("folder", "f", "file", "a.csv",
                        "rows", List.of(Map.of("id", "E1"))));
                params.put(key, "x");
                assertThatThrownBy(() -> validator.validate(scenario(List.of(new StepSpec("file.write", params))),
                        "bad.yaml")).isInstanceOf(ValidationException.class).hasMessageContaining("unknown parameter");
            }
        }
    }

    // ------------------------------------------------------------------
    // Through the engine
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Should wait for a file another process writes later, then delete it in cleanup")
    void testWaitsForFileThroughEngine() throws Exception {
        Map<String, StepType> registry = new LinkedHashMap<>();
        for (StepType type : List.of(write, verify, delete)) {
            registry.put(type.name(), type);
        }
        ScenarioEngine engine = new ScenarioEngine(registry, new FixtureFactory(new TestDataGenerator()),
                new TestExecutor(), Duration.ofSeconds(10), Duration.ofMillis(100), new ExpressionEvaluator());
        // A stale file from "an earlier run" must not satisfy the wait.
        Path stale = Files.writeString(shared.resolve("driver-out-0.csv"), "id,status\r\nE1,provisioned\r\n");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(1))));

        Thread driver = new Thread(() -> {
            try {
                Thread.sleep(600);
                Files.writeString(shared.resolve("driver-out-1.csv"), "id,status\r\nE1,provisioned\r\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        driver.start();

        Scenario scenario = new Scenario("Driver round trip", null, Map.of(), List.of(
                new StepSpec("file.verify", Map.of("folder", "shared", "file", "driver-out-*.csv",
                        "where", Map.of("id", "E1"), "expect", Map.of("row", Map.of("status", "provisioned")),
                        "wait", true, "as", "found"))),
                List.of(new StepSpec("file.delete", Map.of("folder", "shared", "file", "${found.name}"))));
        ScenarioResult result = engine.run(scenario, "t.yaml");
        driver.join();

        assertThat(result.success()).as(String.valueOf(result.failureSummary())).isTrue();
        assertThat(result.steps().get(0).attempts()).isGreaterThan(1);
        assertThat(result.cleanup()).singleElement().satisfies(entry ->
                assertThat(entry.status()).isEqualTo(CleanupStatus.SUCCEEDED));
        // The file it read is gone; the stale one, never selected, is left alone.
        assertThat(namesIn(shared)).containsExactly("driver-out-0.csv");
    }
}
