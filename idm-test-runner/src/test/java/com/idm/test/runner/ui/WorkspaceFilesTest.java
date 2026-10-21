package com.idm.test.runner.ui;

import com.idm.test.runner.ui.WorkspaceFiles.ChangedException;
import com.idm.test.runner.ui.WorkspaceFiles.FileException;
import com.idm.test.runner.ui.WorkspaceFiles.Node;
import com.idm.test.runner.ui.WorkspaceFiles.Problem;
import com.idm.test.runner.ui.WorkspaceFiles.Upload;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The workspace's files as the workbench reads, saves and adds them, on a temp folder. */
@DisplayName("Workbench workspace files")
class WorkspaceFilesTest {

    @TempDir
    Path temp;
    private Path ws;
    private WorkspaceFiles files;

    @BeforeEach
    void workspace() throws IOException {
        ws = Files.createDirectories(temp.resolve("ws"));
        Files.createDirectories(ws.resolve("scenarios/sub"));
        Files.createDirectories(ws.resolve("config"));
        Files.writeString(ws.resolve("scenarios/a.yaml"), "name: a\n");
        files = new WorkspaceFiles(ws);
    }

    // --- read and save -------------------------------------------------------------------------

    @Test
    @DisplayName("Read gives the content and the SHA-256 of the bytes as version")
    void testRead() throws Exception {
        WorkspaceFiles.Content content = files.read("scenarios/a.yaml");
        assertThat(content.content()).isEqualTo("name: a\n");
        assertThat(content.version()).isEqualTo(WorkspaceFiles.version("name: a\n".getBytes(StandardCharsets.UTF_8)))
                .hasSize(64);
    }

    @Test
    @DisplayName("A save with the version read writes the content and returns the new version")
    void testSave() throws Exception {
        String version = files.read("scenarios/a.yaml").version();
        String next = files.save("scenarios/a.yaml", "name: b\n", version);
        assertThat(Files.readString(ws.resolve("scenarios/a.yaml"))).isEqualTo("name: b\n");
        assertThat(next).isEqualTo(files.read("scenarios/a.yaml").version()).isNotEqualTo(version);
        try (Stream<Path> left = Files.list(ws.resolve("scenarios"))) {
            assertThat(left.map(p -> p.getFileName().toString())).as("no temp file left").containsExactlyInAnyOrder("a.yaml", "sub");
        }
    }

    @Test
    @DisplayName("A save with an old version is refused with the current version, and the file is unchanged")
    void testStaleVersionRejected() throws Exception {
        String old = files.read("scenarios/a.yaml").version();
        Files.writeString(ws.resolve("scenarios/a.yaml"), "name: changed elsewhere\n");
        String current = WorkspaceFiles.version("name: changed elsewhere\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> files.save("scenarios/a.yaml", "name: mine\n", old))
                .isInstanceOfSatisfying(ChangedException.class, e -> assertThat(e.currentVersion()).isEqualTo(current));
        assertThat(Files.readString(ws.resolve("scenarios/a.yaml"))).isEqualTo("name: changed elsewhere\n");
    }

    @Test
    @DisplayName("CRLF line endings and a BOM round-trip byte for byte")
    void testSaveKeepsLineEndings() throws Exception {
        String content = "\uFEFFname: crlf\r\nsteps: []\r\n";
        files.save("scenarios/a.yaml", content, files.read("scenarios/a.yaml").version());
        byte[] expected = content.getBytes(StandardCharsets.UTF_8);
        assertThat(Files.readAllBytes(ws.resolve("scenarios/a.yaml"))).isEqualTo(expected)
                .startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(files.read("scenarios/a.yaml").content()).isEqualTo(content);
    }

    @Test
    @DisplayName("A save keeps the file's permissions")
    void testSaveKeepsPermissions() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-r--r--");
        Files.setPosixFilePermissions(ws.resolve("scenarios/a.yaml"), perms);
        files.save("scenarios/a.yaml", "x: 1\n", files.read("scenarios/a.yaml").version());
        assertThat(Files.getPosixFilePermissions(ws.resolve("scenarios/a.yaml"))).isEqualTo(perms);
    }

    @Test
    @DisplayName("Two simultaneous saves with the same version: exactly one succeeds")
    void testConcurrentSavesSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 25; round++) {
                String version = files.read("scenarios/a.yaml").version();
                CountDownLatch go = new CountDownLatch(1);
                List<Future<String>> results = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    String content = "round: " + round + "\nwriter: " + i + "\n";
                    Callable<String> save = () -> {
                        go.await();
                        try {
                            return files.save("scenarios/a.yaml", content, version);
                        } catch (ChangedException e) {
                            return null;
                        }
                    };
                    results.add(pool.submit(save));
                }
                go.countDown();
                int won = 0;
                for (Future<String> result : results) {
                    if (result.get() != null) {
                        won++;
                    }
                }
                assertThat(won).as("round " + round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Save does not create files; a missing version is refused")
    void testSaveNeedsExistingFileAndVersion() {
        assertProblem(() -> files.save("scenarios/new.yaml", "x: 1\n", "abc"), Problem.NOT_FOUND);
        assertThat(ws.resolve("scenarios/new.yaml")).doesNotExist();
        assertThatThrownBy(() -> files.save("scenarios/a.yaml", "x: 1\n", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Files over 2 MB and files that are not UTF-8 are not read")
    void testReadLimits() throws Exception {
        Files.write(ws.resolve("scenarios/big.yaml"), new byte[WorkspaceFiles.MAX_BYTES + 1]);
        assertProblem(() -> files.read("scenarios/big.yaml"), Problem.TOO_LARGE);
        Files.write(ws.resolve("scenarios/exact.yaml"), "a".repeat(WorkspaceFiles.MAX_BYTES).getBytes(StandardCharsets.UTF_8));
        assertThat(files.read("scenarios/exact.yaml").content()).hasSize(WorkspaceFiles.MAX_BYTES);
        Files.write(ws.resolve("scenarios/latin1.yaml"), new byte[] {'a', ':', ' ', (byte) 0xF1, '\n'});
        assertProblem(() -> files.read("scenarios/latin1.yaml"), Problem.NOT_TEXT);
        assertProblem(() -> files.read("scenarios/missing.yaml"), Problem.NOT_FOUND);
        assertProblem(() -> files.read("scenarios/sub"), Problem.NOT_FOUND);
    }

    // --- paths ----------------------------------------------------------------------------------

    @Test
    @DisplayName("Paths that are absolute, climb out, hold NUL or name .git are refused")
    void testBadPaths() throws Exception {
        Files.writeString(temp.resolve("outside.yaml"), "secret: no\n");
        for (String path : new String[] {"../outside.yaml", "scenarios/../../outside.yaml", "/etc/hosts",
                ws.resolve("scenarios/a.yaml").toString(), "", "scenarios//a.yaml", "./scenarios/a.yaml",
                "scenarios\\a.yaml", "scenarios/a\0.yaml", "C:/x.yaml", ".git/config", ".GIT/HEAD"}) {
            assertProblem(() -> files.read(path), Problem.BAD_PATH);
        }
        assertProblem(() -> files.read(null), Problem.BAD_PATH);
    }

    @Test
    @DisplayName("A symbolic link that leads out of the workspace is refused, for reading and saving")
    void testSymlinkOutRefused() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("x.yaml"), "x: 1\n");
        try {
            Files.createSymbolicLink(ws.resolve("scenarios/out"), outside);
            Files.createSymbolicLink(ws.resolve("scenarios/link.yaml"), outside.resolve("x.yaml"));
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("no symbolic links here");
        }
        assertProblem(() -> files.read("scenarios/out/x.yaml"), Problem.BAD_PATH);
        assertProblem(() -> files.read("scenarios/link.yaml"), Problem.BAD_PATH);
        String version = WorkspaceFiles.version("x: 1\n".getBytes(StandardCharsets.UTF_8));
        assertProblem(() -> files.save("scenarios/link.yaml", "x: 2\n", version), Problem.BAD_PATH);
        assertThat(Files.readString(outside.resolve("x.yaml"))).isEqualTo("x: 1\n");
    }

    @Test
    @DisplayName("A symbolic link inside the workspace is followed, and a save keeps the link")
    void testSymlinkInsideFollowed() throws Exception {
        try {
            Files.createSymbolicLink(ws.resolve("scenarios/alias.yaml"), ws.resolve("scenarios/a.yaml"));
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("no symbolic links here");
        }
        files.save("scenarios/alias.yaml", "name: via alias\n", files.read("scenarios/alias.yaml").version());
        assertThat(Files.isSymbolicLink(ws.resolve("scenarios/alias.yaml"))).isTrue();
        assertThat(Files.readString(ws.resolve("scenarios/a.yaml"))).isEqualTo("name: via alias\n");
    }

    @Test
    @DisplayName("secrets.env, in any folder and any case, or behind a link, is never read or saved")
    void testSecretsRefused() throws Exception {
        Files.writeString(ws.resolve("secrets.env"), "IDM_TEST_X=fake-value\n");
        Files.writeString(ws.resolve("config/Secrets.ENV"), "IDM_TEST_X=fake-value\n");
        assertProblem(() -> files.read("secrets.env"), Problem.SECRETS);
        assertProblem(() -> files.read("config/Secrets.ENV"), Problem.SECRETS);
        assertProblem(() -> files.read("scenarios/secrets.env"), Problem.SECRETS);
        assertProblem(() -> files.save("secrets.env", "", "x"), Problem.SECRETS);
        try {
            Files.createSymbolicLink(ws.resolve("scenarios/env.yaml"), ws.resolve("secrets.env"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        assertProblem(() -> files.read("scenarios/env.yaml"), Problem.SECRETS);
    }

    // --- add ------------------------------------------------------------------------------------

    @Test
    @DisplayName("Add copies into scenarios/ and never overwrites: name (2).yaml, name (3).yaml")
    void testAddNeverOverwrites() throws Exception {
        List<String> added = files.add(List.of(upload("a.yaml", "first\n")));
        assertThat(added).containsExactly("scenarios/a (2).yaml");
        added = files.add(List.of(upload("a.yaml", "second\n"), upload("new.yml", "n: 1\n")));
        assertThat(added).containsExactly("scenarios/a (3).yaml", "scenarios/new.yml");
        assertThat(Files.readString(ws.resolve("scenarios/a.yaml"))).isEqualTo("name: a\n");
        assertThat(Files.readString(ws.resolve("scenarios/a (2).yaml"))).isEqualTo("first\n");
        assertThat(Files.readString(ws.resolve("scenarios/a (3).yaml"))).isEqualTo("second\n");
    }

    @Test
    @DisplayName("Add keeps only the file's base name and refuses what is not a YAML file")
    void testAddSanitizes() throws Exception {
        assertThat(files.add(List.of(upload("../../evil.yaml", "e: 1\n")))).containsExactly("scenarios/evil.yaml");
        assertThat(files.add(List.of(upload("C:\\Users\\me\\win.YAML", "w: 1\n")))).containsExactly("scenarios/win.yaml");
        assertThat(files.add(List.of(upload("..hidden.yaml", "h: 1\n")))).containsExactly("scenarios/hidden.yaml");
        assertThat(temp.resolve("evil.yaml")).doesNotExist();
        assertProblem(() -> files.add(List.of(upload("notes.txt", "x"))), Problem.NOT_YAML);
        assertProblem(() -> files.add(List.of(upload("secrets.env", "x"))), Problem.NOT_YAML);
        assertProblem(() -> files.add(List.of(upload(".yaml", "x"))), Problem.BAD_PATH);
        assertProblem(() -> files.add(List.of(upload("ok.yaml", "x"), upload("bad.txt", "x"))), Problem.NOT_YAML);
        assertThat(ws.resolve("scenarios/ok.yaml")).as("nothing is added when one file is refused").doesNotExist();
        assertProblem(() -> files.add(List.of(new Upload("big.yaml", new byte[WorkspaceFiles.MAX_BYTES + 1]))),
                Problem.TOO_LARGE);
        assertProblem(() -> files.add(List.of(new Upload("latin.yaml", new byte[] {(byte) 0xF1}))), Problem.NOT_TEXT);
    }

    // --- tree -----------------------------------------------------------------------------------

    @Test
    @DisplayName("The tree shows scenarios/, config/ and top-level files, with kinds, and skips the rest")
    void testTree() throws Exception {
        Files.writeString(ws.resolve("scenarios/sub/b.yml"), "name: b\n");
        Files.writeString(ws.resolve("scenarios/sub/notes.txt"), "n\n");
        Files.writeString(ws.resolve("scenarios/.draft.yaml"), "d\n");
        Files.writeString(ws.resolve("config/application-lab.yml"), "x: 1\n");
        Files.writeString(ws.resolve("secrets.env"), "IDM_TEST_X=fake-value\n");
        Files.writeString(ws.resolve("README.md"), "r\n");
        Files.writeString(ws.resolve(".gitignore"), "reports/\n");
        Files.createDirectories(ws.resolve(".git/objects"));
        Files.createDirectories(ws.resolve("reports/20261019-120000-lab"));
        Files.createDirectories(ws.resolve("drivers"));

        Node root = files.tree();

        assertThat(root.dir()).isTrue();
        assertThat(root.path()).isEmpty();
        assertThat(root.children()).extracting(Node::path)
                .containsExactly("config", "scenarios", "README.md", "secrets.env");
        Node scenarios = child(root, "scenarios");
        assertThat(scenarios.children()).extracting(Node::path)
                .containsExactly("scenarios/sub", "scenarios/a.yaml");
        Node sub = child(scenarios, "scenarios/sub");
        assertThat(sub.children()).extracting(Node::path, Node::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("scenarios/sub/b.yml", "scenario"),
                        org.assertj.core.groups.Tuple.tuple("scenarios/sub/notes.txt", "other"));
        assertThat(child(child(root, "config"), "config/application-lab.yml").kind()).isEqualTo("config");
        assertThat(child(root, "secrets.env").kind()).isEqualTo("secrets");
        assertThat(child(root, "README.md").kind()).isEqualTo("other");
        assertThat(child(root, "README.md").children()).isNull();
    }

    @Test
    @DisplayName("Each scenario's last result comes from the newest report that ran it")
    void testTreeLastResult() throws Exception {
        Files.writeString(ws.resolve("scenarios/sub/b.yml"), "name: b\n");
        Files.writeString(ws.resolve("scenarios/c.yaml"), "name: c\n");
        Files.writeString(ws.resolve("scenarios/d.yaml"), "name: d\n");
        writeResults("20261018-091000-lab", Instant.parse("2026-10-18T07:10:00Z"),
                "[" + result("a.yaml", true, null) + "," + result("sub/b.yml", false, "SETUP") + ","
                        + result("c.yaml", false, "FINDING") + "]");
        writeResults("20261019-143200-lab", Instant.parse("2026-10-19T12:32:00Z"),
                "[" + result("a.yaml", false, "FINDING") + "]");

        Node root = files.tree();

        Node scenarios = child(root, "scenarios");
        assertThat(child(scenarios, "scenarios/a.yaml").lastResult()).isEqualTo("finding");
        assertThat(child(child(scenarios, "scenarios/sub"), "scenarios/sub/b.yml").lastResult()).isEqualTo("setup");
        assertThat(child(scenarios, "scenarios/c.yaml").lastResult()).isEqualTo("finding");
        assertThat(child(scenarios, "scenarios/d.yaml").lastResult()).isNull();

        writeResults("20261020-080000-lab", Instant.parse("2026-10-20T06:00:00Z"),
                "[" + result("c.yaml", true, null) + "]");
        assertThat(child(child(files.tree(), "scenarios"), "scenarios/c.yaml").lastResult()).isEqualTo("passed");
    }

    // --- helpers --------------------------------------------------------------------------------

    private static Node child(Node parent, String path) {
        return parent.children().stream().filter(n -> n.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + path + " in " + parent.path()));
    }

    private static Upload upload(String name, String content) {
        return new Upload(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Problem problem) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FileException.class,
                e -> assertThat(e.problem()).isEqualTo(problem));
    }

    private void writeResults(String runId, Instant at, String json) throws IOException {
        Path file = Files.createDirectories(ws.resolve("reports").resolve(runId)).resolve("results.json");
        Files.writeString(file, json);
        Files.setLastModifiedTime(file, FileTime.from(at));
    }

    private static String result(String sourceName, boolean success, String kind) {
        String step = "{\"index\":0,\"type\":\"ldap.verify\",\"success\":" + success
                + (kind == null ? "" : ",\"failureKind\":\"" + kind + "\"") + "}";
        return "{\"name\":\"" + sourceName + "\",\"sourceName\":\"" + sourceName + "\",\"success\":" + success
                + ",\"durationMillis\":1,\"steps\":[" + step + "],\"cleanup\":[]}";
    }
}
