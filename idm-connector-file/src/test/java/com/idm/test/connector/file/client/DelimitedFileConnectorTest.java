package com.idm.test.connector.file.client;

import com.idm.test.connector.file.client.FileProblem.Kind;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DelimitedFileConnectorTest {

    private static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");

    @TempDir
    Path root;

    private Path inbox;
    private Path outbox;
    private Path shared;
    private DelimitedFileConnector connector;

    @BeforeEach
    void setUp() throws IOException {
        inbox = Files.createDirectory(root.resolve("in"));
        outbox = Files.createDirectory(root.resolve("out"));
        shared = Files.createDirectory(root.resolve("shared"));
        connector = new DelimitedFileConnector(Map.of(
                "in", FolderSettings.of(inbox.toString(), FolderAccess.WRITE),
                "out", FolderSettings.of(outbox.toString(), FolderAccess.READ),
                "shared", FolderSettings.of(shared.toString(), FolderAccess.READ_WRITE)));
    }

    private static FileProblem problem(OperationResult<?> result) {
        assertThat(result.isSuccess()).as("expected a failure, got: %s", result.getMessage()).isFalse();
        return FileProblem.of(result).orElseThrow();
    }

    private static List<List<String>> rows(List<String>... rows) {
        return Arrays.asList(rows);
    }

    private ReadFile readBack(String pattern) {
        OperationResult<FileLookup> result = connector.readNewest("shared", pattern, LONG_AGO, null);
        assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
        return result.getDataOrThrow().file().orElseThrow();
    }

    private static List<String> namesIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Nested
    @DisplayName("Writing and reading back")
    class RoundTrip {

        @Test
        @DisplayName("Should round-trip delimiters, quotes and newlines inside values")
        void testRoundTripsAwkwardValues() {
            List<List<String>> rows = rows(
                    List.of("E1", "Ruiz, Ana", "says \"hi\""),
                    List.of("E2", "line one\nline two", ""));

            OperationResult<WrittenFile> written = connector.write("shared", "hr.csv",
                    List.of("id", "name", "note"), rows, null);

            assertThat(written.isSuccess()).as(written.getMessage()).isTrue();
            assertThat(written.getDataOrThrow().rows()).isEqualTo(2);
            assertThat(written.getDataOrThrow().size()).isPositive();
            ReadFile read = readBack("hr.csv");
            assertThat(read.header()).containsExactly("id", "name", "note");
            assertThat(read.rows()).isEqualTo(rows);
            assertThat(read.folder()).isEqualTo("shared");
        }

        @Test
        @DisplayName("Should write a null value as an empty field")
        void testNullValueIsEmpty() {
            List<String> row = new ArrayList<>();
            row.add("E1");
            row.add(null);

            connector.write("shared", "nulls.csv", List.of("id", "mail"), rows(row), null);

            assertThat(readBack("nulls.csv").rows()).containsExactly(List.of("E1", ""));
        }

        @Test
        @DisplayName("Should write a header-only file for an empty export")
        void testHeaderOnlyFile() throws IOException {
            connector.write("shared", "empty.csv", List.of("id", "mail"), List.of(), null);

            assertThat(Files.readString(shared.resolve("empty.csv"))).isEqualTo("id,mail\r\n");
            ReadFile read = readBack("empty.csv");
            assertThat(read.header()).containsExactly("id", "mail");
            assertThat(read.rows()).isEmpty();
        }

        @Test
        @DisplayName("Should use CRLF by default and LF when asked")
        void testLineEndings() throws IOException {
            connector.write("shared", "crlf.csv", List.of("a"), rows(List.of("1")), null);
            connector.write("shared", "lf.csv", List.of("a"), rows(List.of("1")),
                    new CsvFormat(null, null, null, LineEnding.LF, null));

            assertThat(Files.readString(shared.resolve("crlf.csv"))).isEqualTo("a\r\n1\r\n");
            assertThat(Files.readString(shared.resolve("lf.csv"))).isEqualTo("a\n1\n");
            assertThat(readBack("lf.csv").rows()).containsExactly(List.of("1"));
        }

        @Test
        @DisplayName("Should honour delimiter, quote and header overrides")
        void testFormatOverrides() throws IOException {
            connector.write("shared", "semi.csv", List.of("a", "b"), rows(List.of("x;y", "z")),
                    new CsvFormat(";", "'", null, LineEnding.LF, false));

            assertThat(Files.readString(shared.resolve("semi.csv"))).isEqualTo("'x;y';z\n");
        }

        @Test
        @DisplayName("Should write and read ISO-8859-1 when the folder says so")
        void testLatin1() throws IOException {
            CsvFormat latin1 = new CsvFormat(null, null, "ISO-8859-1", null, null);

            connector.write("shared", "latin.csv", List.of("name"), rows(List.of("Peña")), latin1);

            assertThat(Files.readAllBytes(shared.resolve("latin.csv")))
                    .containsSequence((byte) 'P', (byte) 'e', (byte) 0xF1, (byte) 'a');
            OperationResult<FileLookup> read = connector.readNewest("shared", "latin.csv", LONG_AGO, latin1);
            assertThat(read.getDataOrThrow().file().orElseThrow().rows()).containsExactly(List.of("Peña"));
        }

        @Test
        @DisplayName("Should refuse a value the encoding cannot hold, leaving no file behind")
        void testUnencodableValue() throws IOException {
            OperationResult<WrittenFile> result = connector.write("shared", "ascii.csv", List.of("name"),
                    rows(List.of("Peña")), new CsvFormat(null, null, "US-ASCII", null, null));

            assertThat(problem(result).kind()).isEqualTo(Kind.UNENCODABLE);
            assertThat(namesIn(shared)).isEmpty();
        }

        @Test
        @DisplayName("Should tolerate a UTF-8 byte order mark on read and never write one")
        void testByteOrderMark() throws IOException {
            Files.writeString(shared.resolve("bom.csv"), "\uFEFFid,mail\r\nE1,a@b.c\r\n");
            ReadFile read = readBack("bom.csv");
            assertThat(read.header()).containsExactly("id", "mail");

            connector.write("shared", "nobom.csv", List.of("id"), rows(List.of("E1")), null);
            assertThat(Files.readAllBytes(shared.resolve("nobom.csv"))[0]).isEqualTo((byte) 'i');
        }

        @Test
        @DisplayName("Should find columns ignoring case and give rows as maps in header order")
        void testColumnLookup() {
            connector.write("shared", "cols.csv", List.of("EmployeeId", "Mail"), rows(List.of("E1", "a@b.c")), null);

            ReadFile read = readBack("cols.csv");
            assertThat(read.columnIndex("employeeid")).hasValue(0);
            assertThat(read.columnIndex("MAIL")).hasValue(1);
            assertThat(read.columnIndex("status")).isEmpty();
            assertThat(read.rowMaps()).containsExactly(Map.of("EmployeeId", "E1", "Mail", "a@b.c"));
        }
    }

    @Nested
    @DisplayName("Writing safely")
    class SafeWriting {

        @Test
        @DisplayName("Should never overwrite an existing file")
        void testRefusesExistingFile() throws IOException {
            Files.writeString(inbox.resolve("hr.csv"), "keep me");

            FileProblem problem = problem(connector.write("in", "hr.csv", List.of("a"), rows(List.of("1")), null));

            assertThat(problem.kind()).isEqualTo(Kind.ALREADY_EXISTS);
            assertThat(problem.errorType()).isEqualTo(ErrorType.CONFIGURATION_ERROR);
            assertThat(Files.readString(inbox.resolve("hr.csv"))).isEqualTo("keep me");
        }

        @Test
        @DisplayName("Should leave no temporary file after a successful write")
        void testNoTemporaryFileLeft() throws IOException {
            connector.write("in", "hr.csv", List.of("a"), rows(List.of("1")), null);

            assertThat(namesIn(inbox)).containsExactly("hr.csv");
        }

        @Test
        @DisplayName("Should not touch a leftover temporary file it did not create")
        void testLeftoverTemporaryFile() throws IOException {
            Files.writeString(inbox.resolve("hr.csv.idmtest-tmp"), "someone else's");

            FileProblem problem = problem(connector.write("in", "hr.csv", List.of("a"), rows(List.of("1")), null));

            assertThat(problem.kind()).isEqualTo(Kind.TEMP_EXISTS);
            assertThat(Files.readString(inbox.resolve("hr.csv.idmtest-tmp"))).isEqualTo("someone else's");
            assertThat(namesIn(inbox)).containsExactly("hr.csv.idmtest-tmp");
        }

        @Test
        @DisplayName("Should fail as a setup problem, and clean up, when the share cannot rename atomically")
        void testNoAtomicMove() throws IOException {
            DelimitedFileConnector noAtomic = new DelimitedFileConnector(
                    Map.of("in", FolderSettings.of(inbox.toString(), FolderAccess.WRITE)),
                    (from, to) -> {
                        throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "no");
                    });

            FileProblem problem = problem(noAtomic.write("in", "hr.csv", List.of("a"), rows(List.of("1")), null));

            assertThat(problem.kind()).isEqualTo(Kind.NO_ATOMIC_MOVE);
            assertThat(problem.errorType()).isEqualTo(ErrorType.CONFIGURATION_ERROR);
            assertThat(namesIn(inbox)).isEmpty();
        }

        @Test
        @DisplayName("Should clean up, and not name the path, when the rename fails")
        void testMoveFails() throws IOException {
            DelimitedFileConnector failing = new DelimitedFileConnector(
                    Map.of("in", FolderSettings.of(inbox.toString(), FolderAccess.WRITE)),
                    (from, to) -> {
                        throw new java.nio.file.AccessDeniedException(to.toString(), null, "share is read-only");
                    });

            OperationResult<WrittenFile> result = failing.write("in", "hr.csv", List.of("a"), rows(List.of("1")), null);

            assertThat(problem(result).kind()).isEqualTo(Kind.IO_FAILED);
            assertThat(result.getErrorType()).contains(ErrorType.NETWORK_ERROR);
            assertThat(result.getMessage()).contains("share is read-only").doesNotContain(root.toString());
            assertThat(namesIn(inbox)).isEmpty();
        }

        @Test
        @DisplayName("Should refuse a file name ending in the temporary suffix")
        void testReservedName() {
            assertThat(problem(connector.write("in", "x.idmtest-tmp", List.of("a"), rows(List.of("1")), null)).kind())
                    .isEqualTo(Kind.RESERVED_NAME);
        }

        @Test
        @DisplayName("Should refuse rows that do not fit the columns")
        void testBadRows() {
            assertThat(problem(connector.write("in", "a.csv", List.of(), rows(), null)).kind())
                    .isEqualTo(Kind.BAD_ROWS);
            assertThat(problem(connector.write("in", "a.csv", List.of("a", "A"), rows(), null)).kind())
                    .isEqualTo(Kind.BAD_ROWS);
            assertThat(problem(connector.write("in", "a.csv", List.of("a", "b"), rows(List.of("1")), null)).values())
                    .anySatisfy(v -> assertThat(v.toString()).contains("row 1 has 1 value(s) for 2 column(s)"));
        }

        @Test
        @DisplayName("Should refuse format settings it cannot use, naming the setting")
        void testBadFormat() {
            assertThat(problem(connector.write("in", "a.csv", List.of("a"), rows(),
                    new CsvFormat(";;", null, null, null, null))).values()).contains("delimiter");
            assertThat(problem(connector.write("in", "a.csv", List.of("a"), rows(),
                    new CsvFormat(",", ",", null, null, null))).values()).contains("quote");
            assertThat(problem(connector.write("in", "a.csv", List.of("a"), rows(),
                    new CsvFormat(null, null, "no-such-charset", null, null))).values()).contains("encoding");
        }
    }

    @Nested
    @DisplayName("Names stay inside their folder")
    class Containment {

        @ParameterizedTest
        @ValueSource(strings = {"../x.csv", "/etc/passwd", "sub/x.csv", "sub\\x.csv", "C:\\x.csv", "C:x.csv",
                "..", ".", "", " x.csv", "x.csv ", "x\u0000.csv", "x*.csv"})
        @DisplayName("Should refuse a name that is not plain, for write and delete")
        void testRefusesUnsafeNames(String name) {
            assertThat(problem(connector.write("shared", name, List.of("a"), rows(), null)).kind())
                    .isEqualTo(Kind.UNSAFE_NAME);
            assertThat(problem(connector.delete("shared", name)).kind()).isEqualTo(Kind.UNSAFE_NAME);
        }

        @ParameterizedTest
        @ValueSource(strings = {"../*.csv", "/etc/*", "sub/*.csv", "..", "", "a\\*"})
        @DisplayName("Should refuse a pattern that reaches outside the folder")
        void testRefusesUnsafePatterns(String pattern) {
            assertThat(problem(connector.readNewest("shared", pattern, LONG_AGO, null)).kind())
                    .isEqualTo(Kind.BAD_PATTERN);
        }

        @Test
        @DisplayName("Should refuse a glob it cannot parse")
        void testRefusesMalformedGlob() {
            assertThat(problem(connector.readNewest("shared", "hr-[.csv", LONG_AGO, null)).kind())
                    .isEqualTo(Kind.BAD_PATTERN);
        }

        @Test
        @DisplayName("Should never follow a symbolic link that leads outside the folder")
        void testSymlinkOutside() throws IOException {
            Path secret = Files.writeString(root.resolve("secret.csv"), "id\r\nE1\r\n");
            assumeTrue(createLink(shared.resolve("link.csv"), secret), "symbolic links not supported here");

            assertThat(problem(connector.delete("shared", "link.csv")).kind()).isEqualTo(Kind.UNSAFE_NAME);
            assertThat(problem(connector.write("shared", "link.csv", List.of("a"), rows(), null)).kind())
                    .isEqualTo(Kind.UNSAFE_NAME);
            OperationResult<FileLookup> read = connector.readNewest("shared", "*.csv", LONG_AGO, null);
            assertThat(read.getDataOrThrow().file()).isEmpty();
            assertThat(secret).exists();
        }

        @Test
        @DisplayName("Should not read through a symbolic link even inside the folder")
        void testSymlinkInsideIsNotRead() throws IOException {
            Path real = Files.writeString(shared.resolve("real.data"), "id\r\nE1\r\n");
            assumeTrue(createLink(shared.resolve("alias.csv"), real), "symbolic links not supported here");

            assertThat(connector.readNewest("shared", "*.csv", LONG_AGO, null).getDataOrThrow().file()).isEmpty();
            assertThat(connector.delete("shared", "alias.csv").getDataOrThrow()).isTrue();
            assertThat(real).exists();
        }

        private static boolean createLink(Path link, Path target) {
            try {
                Files.createSymbolicLink(link, target);
                return true;
            } catch (IOException | UnsupportedOperationException e) {
                return false;
            }
        }

        @Test
        @DisplayName("Should match a glob against names in the folder only, never subfolders")
        void testGlobStaysInFolder() throws IOException {
            Path sub = Files.createDirectory(shared.resolve("sub.csv"));
            Files.writeString(sub.resolve("deep.csv"), "id\r\nE1\r\n");

            assertThat(connector.readNewest("shared", "*", LONG_AGO, null).getDataOrThrow().file()).isEmpty();
            assertThat(problem(connector.delete("shared", "sub.csv")).kind()).isEqualTo(Kind.NOT_A_FILE);
        }
    }

    @Nested
    @DisplayName("Access and folders")
    class Access {

        @Test
        @DisplayName("Should enforce each folder's declared access")
        void testAccessEnforced() {
            FileProblem writeToRead = problem(connector.write("out", "a.csv", List.of("a"), rows(), null));
            assertThat(writeToRead.kind()).isEqualTo(Kind.ACCESS_DENIED);
            assertThat(writeToRead.values()).containsExactly("out", "read", "write");
            assertThat(problem(connector.delete("out", "a.csv")).kind()).isEqualTo(Kind.ACCESS_DENIED);
            assertThat(problem(connector.readNewest("in", "*.csv", LONG_AGO, null)).kind())
                    .isEqualTo(Kind.ACCESS_DENIED);

            assertThat(connector.write("in", "a.csv", List.of("a"), rows(), null).isSuccess()).isTrue();
            assertThat(connector.readNewest("out", "*.csv", LONG_AGO, null).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should default a folder without access to read")
        void testDefaultAccessIsRead() {
            assertThat(FolderSettings.of("/x", null).access()).isEqualTo(FolderAccess.READ);
        }

        @Test
        @DisplayName("Should report an unknown folder with the ones that exist, as a final failure")
        void testUnknownFolder() {
            FileProblem problem = problem(connector.delete("hr", "a.csv"));

            assertThat(problem.kind()).isEqualTo(Kind.NOT_CONFIGURED);
            assertThat(problem.errorType()).isEqualTo(ErrorType.CONFIGURATION_ERROR);
            assertThat(problem.english()).contains("[in, out, shared]");
        }

        @Test
        @DisplayName("Should report a missing folder or a file in place of one without naming the path")
        void testMissingFolder() throws IOException {
            Path file = Files.writeString(root.resolve("plain.txt"), "x");
            DelimitedFileConnector broken = new DelimitedFileConnector(Map.of(
                    "gone", FolderSettings.of(root.resolve("gone").toString(), FolderAccess.READ_WRITE),
                    "file", FolderSettings.of(file.toString(), FolderAccess.READ_WRITE),
                    "blank", FolderSettings.of(" ", FolderAccess.READ_WRITE)));

            OperationResult<Boolean> gone = broken.delete("gone", "a.csv");
            assertThat(problem(gone).kind()).isEqualTo(Kind.FOLDER_MISSING);
            assertThat(gone.getMessage()).doesNotContain(root.toString());
            assertThat(problem(broken.delete("file", "a.csv")).kind()).isEqualTo(Kind.NOT_A_DIRECTORY);
            assertThat(problem(broken.delete("blank", "a.csv")).kind()).isEqualTo(Kind.NO_PATH);
        }
    }

    @Nested
    @DisplayName("Choosing the file to read")
    class Candidates {

        private final Instant start = Instant.parse("2026-11-03T10:00:00Z");

        private void file(String name, Instant modified) throws IOException {
            Path path = Files.writeString(outbox.resolve(name), "id,file\r\nE1," + name + "\r\n");
            Files.setLastModifiedTime(path, FileTime.from(modified));
        }

        private OperationResult<FileLookup> read(String pattern) {
            return connector.readNewest("out", pattern, start, null);
        }

        @Test
        @DisplayName("Should read the newest matching file modified since the start")
        void testNewestWins() throws IOException {
            file("hr-out-1.csv", start.plusSeconds(1));
            file("hr-out-2.csv", start.plusSeconds(3));
            file("hr-out-3.csv", start.plusSeconds(2));
            file("other.csv", start.plusSeconds(9));

            assertThat(read("hr-out-*.csv").getDataOrThrow().file().orElseThrow().name()).isEqualTo("hr-out-2.csv");
        }

        @Test
        @DisplayName("Should break a tie in time by the later name, the same on every attempt")
        void testTieBreak() throws IOException {
            file("b.csv", start.plusSeconds(1));
            file("a.csv", start.plusSeconds(1));
            file("c.csv", start.plusSeconds(1));

            assertThat(read("*.csv").getDataOrThrow().file().orElseThrow().name()).isEqualTo("c.csv");
        }

        @Test
        @DisplayName("Should ignore files older than the start, less the clock skew, and count them")
        void testStaleFilesIgnored() throws IOException {
            file("old.csv", start.minusSeconds(60));
            file("skewed.csv", start.minusSeconds(4));

            OperationResult<FileLookup> result = read("*.csv");
            assertThat(result.getDataOrThrow().file().orElseThrow().name()).isEqualTo("skewed.csv");
            assertThat(result.getDataOrThrow().olderMatches()).isEqualTo(1);

            Files.delete(outbox.resolve("skewed.csv"));
            FileLookup none = read("*.csv").getDataOrThrow();
            assertThat(none.file()).isEmpty();
            assertThat(none.olderMatches()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should use the folder's own clock skew")
        void testCustomClockSkew() throws IOException {
            DelimitedFileConnector strict = new DelimitedFileConnector(Map.of("out",
                    new FolderSettings(outbox.toString(), FolderAccess.READ, null, null, Duration.ZERO)));
            file("skewed.csv", start.minusSeconds(1));

            assertThat(strict.readNewest("out", "*.csv", start, null).getDataOrThrow().file()).isEmpty();
        }

        @Test
        @DisplayName("Should never read a temporary file")
        void testTemporaryFilesIgnored() throws IOException {
            file("hr.csv.idmtest-tmp", start.plusSeconds(5));

            assertThat(read("hr.csv*").getDataOrThrow().file()).isEmpty();
        }

        @Test
        @DisplayName("Should accept an exact name as the pattern")
        void testExactName() throws IOException {
            file("hr.csv", start.plusSeconds(1));
            file("hr2.csv", start.plusSeconds(2));

            assertThat(read("hr.csv").getDataOrThrow().file().orElseThrow().name()).isEqualTo("hr.csv");
        }
    }

    @Nested
    @DisplayName("Reading malformed files")
    class Malformed {

        private OperationResult<FileLookup> readText(String text) throws IOException {
            Files.writeString(outbox.resolve("bad.csv"), text);
            return connector.readNewest("out", "bad.csv", LONG_AGO, null);
        }

        @Test
        @DisplayName("Should report a row with the wrong number of fields as retryable")
        void testRaggedRow() throws IOException {
            OperationResult<FileLookup> result = readText("id,mail\r\nE1\r\n");

            assertThat(problem(result).kind()).isEqualTo(Kind.PARSE_FAILED);
            assertThat(result.getErrorType()).contains(ErrorType.VALIDATION_ERROR);
            assertThat(result.getMessage()).contains("has 1 field(s); the header has 2");
        }

        @Test
        @DisplayName("Should report unbalanced quotes, an empty file and a bad header")
        void testParseFailures() throws IOException {
            assertThat(problem(readText("id,mail\r\nE1,\"open\r\n")).kind()).isEqualTo(Kind.PARSE_FAILED);
            assertThat(problem(readText("")).kind()).isEqualTo(Kind.PARSE_FAILED);
            assertThat(problem(readText("id,ID\r\n")).english()).contains("appears twice");
            assertThat(problem(readText("id,\r\n")).english()).contains("empty column name");
        }

        @Test
        @DisplayName("Should report bytes that are not valid in the encoding")
        void testWrongEncoding() throws IOException {
            Files.write(outbox.resolve("bad.csv"), new byte[]{'i', 'd', '\r', '\n', (byte) 0xF1, '\r', '\n'});

            OperationResult<FileLookup> result = connector.readNewest("out", "bad.csv", LONG_AGO, null);
            assertThat(problem(result).english()).contains("not valid UTF-8");
        }

        @Test
        @DisplayName("Should skip blank lines and refuse to read without a header row")
        void testBlankLinesAndNoHeader() throws IOException {
            assertThat(readText("id\r\n\r\nE1\r\n\r\n").getDataOrThrow().file().orElseThrow().rows())
                    .containsExactly(List.of("E1"));
            assertThat(problem(connector.readNewest("out", "bad.csv", LONG_AGO,
                    new CsvFormat(null, null, null, null, false))).kind()).isEqualTo(Kind.NO_HEADER);
        }
    }

    @Nested
    @DisplayName("Deleting")
    class Deleting {

        @Test
        @DisplayName("Should delete a present file and treat an absent one as success")
        void testDelete() throws IOException {
            Files.writeString(inbox.resolve("hr.csv"), "x");

            assertThat(connector.delete("in", "hr.csv").getDataOrThrow()).isTrue();
            assertThat(inbox.resolve("hr.csv")).doesNotExist();
            OperationResult<Boolean> again = connector.delete("in", "hr.csv");
            assertThat(again.getDataOrThrow()).isFalse();
            assertThat(again.getMessage()).contains("already absent");
        }
    }

    @Nested
    @DisplayName("Probing a folder")
    class Probing {

        @Test
        @DisplayName("Should pass a folder that allows what it declares, leaving nothing behind")
        void testProbePasses() throws IOException {
            assertThat(connector.probe("in").isSuccess()).isTrue();
            assertThat(connector.probe("out").isSuccess()).isTrue();
            assertThat(connector.probe("shared").isSuccess()).isTrue();
            assertThat(namesIn(inbox)).isEmpty();
            assertThat(namesIn(shared)).isEmpty();
        }

        @Test
        @DisplayName("Should fail a write folder the operating system will not let us write")
        void testProbeNotWritable() throws IOException {
            assumeTrue(inbox.toFile().setWritable(false), "cannot change permissions here");
            try {
                assumeTrue(!Files.isWritable(inbox), "running with permissions that ignore the mode (root)");
                assertThat(problem(connector.probe("in")).kind()).isEqualTo(Kind.NOT_WRITABLE);
            } finally {
                inbox.toFile().setWritable(true);
            }
        }

        @Test
        @DisplayName("Should fail a missing folder")
        void testProbeMissing() {
            DelimitedFileConnector missing = new DelimitedFileConnector(
                    Map.of("x", FolderSettings.of(root.resolve("nope").toString(), FolderAccess.READ)));

            assertThat(problem(missing.probe("x")).kind()).isEqualTo(Kind.FOLDER_MISSING);
        }
    }

    @Test
    @DisplayName("Should write the file content exactly as Commons CSV quotes it")
    void testWrittenBytes() throws IOException {
        connector.write("shared", "exact.csv", List.of("id", "name"), rows(List.of("E1", "Ruiz, \"Ana\"")), null);

        assertThat(new String(Files.readAllBytes(shared.resolve("exact.csv")), StandardCharsets.UTF_8))
                .isEqualTo("id,name\r\nE1,\"Ruiz, \"\"Ana\"\"\"\r\n");
    }
}
