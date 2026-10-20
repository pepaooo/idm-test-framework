package com.idm.test.runner.ui.api;

import com.idm.test.runner.InitCommand;
import com.idm.test.runner.ui.UiCommand;
import com.idm.test.runner.ui.UiHttp;
import com.idm.test.runner.ui.UiHttp.Response;
import com.idm.test.runner.ui.UiServer;
import com.idm.test.runner.ui.WorkspaceFiles;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The files API over HTTP, against a running workbench with a workspace open. */
@DisplayName("Workbench files API")
class FilesControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /**
     * How long a test waits for a change event. Linux reports at once; macOS's JDK WatchService
     * polls (every 2 s at the highest sensitivity the JDK offers), so the brief's 2 s cannot be met
     * there and the test allows 10 s.
     */
    private static final Duration EVENT_WAIT = Duration.ofSeconds(10);

    @TempDir
    static Path home;
    private static String realHome;
    private static UiServer server;

    @TempDir
    Path temp;
    private Path ws;

    @BeforeAll
    static void start() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        server = UiCommand.start(0, Locale.ENGLISH);
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
        System.setProperty("user.home", realHome);
    }

    @BeforeEach
    void openWorkspace() throws IOException {
        ws = temp.resolve("ws");
        InitCommand.run(ws, new PrintStream(OutputStream.nullOutputStream()));
        Response opened = UiHttp.send(server, "POST", "/api/workspaces/open",
                JSON.writeValueAsString(Map.of("path", ws.toString())));
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
    }

    // --- read and save -------------------------------------------------------------------------

    @Test
    @DisplayName("GET a file gives its content and version; PUT with that version saves it")
    void testReadAndSave() throws Exception {
        JsonNode read = json(get("scenarios/example.yaml"));
        assertThat(read.get("content").asString()).isEqualTo(Files.readString(ws.resolve("scenarios/example.yaml")));
        String version = read.get("version").asString();

        Response saved = put("scenarios/example.yaml", "name: saved\n", version);

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(Files.readString(ws.resolve("scenarios/example.yaml"))).isEqualTo("name: saved\n");
        assertThat(json(saved).get("version").asString())
                .isEqualTo(WorkspaceFiles.version("name: saved\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("A save with an old version answers 409 with the current version and leaves the file alone")
    void testStaleVersionRejected() throws Exception {
        String version = json(get("scenarios/example.yaml")).get("version").asString();
        Files.writeString(ws.resolve("scenarios/example.yaml"), "name: from git pull\n");

        Response conflict = put("scenarios/example.yaml", "name: mine\n", version);

        assertThat(conflict.status()).isEqualTo(409);
        JsonNode body = json(conflict);
        assertThat(body.get("key").asString()).isEqualTo("ui.file.changed");
        assertThat(body.get("message").asString()).contains("scenarios/example.yaml");
        assertThat(body.get("currentVersion").asString())
                .isEqualTo(WorkspaceFiles.version("name: from git pull\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(Files.readString(ws.resolve("scenarios/example.yaml"))).isEqualTo("name: from git pull\n");
    }

    @Test
    @DisplayName("CRLF and a BOM sent in the content are written and read back byte for byte")
    void testSaveKeepsLineEndings() throws Exception {
        String content = "﻿name: windows\r\nsteps:\r\n  - type: x\r\n";
        String version = json(get("scenarios/example.yaml")).get("version").asString();

        assertThat(put("scenarios/example.yaml", content, version).status()).isEqualTo(200);

        assertThat(Files.readAllBytes(ws.resolve("scenarios/example.yaml")))
                .isEqualTo(content.getBytes(StandardCharsets.UTF_8));
        assertThat(json(get("scenarios/example.yaml")).get("content").asString()).isEqualTo(content);
    }

    @Test
    @DisplayName("PUT needs a version, and does not create files")
    void testSaveRefusals() throws Exception {
        Response noVersion = UiHttp.send(server, "PUT", "/api/files?path=scenarios/example.yaml",
                JSON.writeValueAsString(Map.of("content", "x: 1\n")));
        assertThat(noVersion.status()).isEqualTo(400);
        assertThat(json(noVersion).get("key").asString()).isEqualTo("ui.request.invalid");
        assertThat(json(noVersion).get("message").asString()).contains("version");

        Response created = put("scenarios/brand-new.yaml", "x: 1\n", "0".repeat(64));
        assertThat(created.status()).isEqualTo(404);
        assertThat(json(created).get("key").asString()).isEqualTo("ui.file.notFound");
        assertThat(ws.resolve("scenarios/brand-new.yaml")).doesNotExist();
    }

    @Test
    @DisplayName("Large and non-UTF-8 files: 413 and 415")
    void testReadLimits() throws Exception {
        Files.write(ws.resolve("scenarios/big.yaml"), new byte[WorkspaceFiles.MAX_BYTES + 1]);
        Response big = get("scenarios/big.yaml");
        assertThat(big.status()).isEqualTo(413);
        assertThat(json(big).get("key").asString()).isEqualTo("ui.file.tooLarge");
        Files.write(ws.resolve("scenarios/latin1.yaml"), new byte[] {'a', ':', ' ', (byte) 0xF1, '\n'});
        Response latin = get("scenarios/latin1.yaml");
        assertThat(latin.status()).isEqualTo(415);
        assertThat(json(latin).get("key").asString()).isEqualTo("ui.file.notText");
    }

    // --- paths ----------------------------------------------------------------------------------

    @Test
    @DisplayName("Escapes — ../, absolute paths, a symbolic link out of the workspace — are 400")
    void testEscapesRefused() throws Exception {
        Files.writeString(temp.resolve("outside.yaml"), "x: 1\n");
        List<String> paths = new ArrayList<>(List.of("../outside.yaml", "scenarios/../../outside.yaml",
                temp.resolve("outside.yaml").toString(), "/etc/hosts"));
        try {
            Files.createSymbolicLink(ws.resolve("scenarios/out.yaml"), temp.resolve("outside.yaml"));
            paths.add("scenarios/out.yaml");
        } catch (UnsupportedOperationException | IOException e) {
            // no symbolic links on this system: the other escapes are still checked
        }
        for (String path : paths) {
            Response read = get(path);
            assertThat(read.status()).as(path).isEqualTo(400);
            assertThat(json(read).get("key").asString()).isEqualTo("ui.file.badPath");
            Response save = put(path, "x: 2\n", WorkspaceFiles.version("x: 1\n".getBytes(StandardCharsets.UTF_8)));
            assertThat(save.status()).as(path).isEqualTo(400);
        }
        assertThat(Files.readString(temp.resolve("outside.yaml"))).isEqualTo("x: 1\n");
    }

    @Test
    @DisplayName("secrets.env is 403 for reading and saving, and its content never appears in the tree")
    void testSecretsRefused() throws Exception {
        Files.writeString(ws.resolve("secrets.env"), "IDM_TEST_LDAP_PASSWORD=fake-not-a-real-value\n");
        for (String path : new String[] {"secrets.env", "SECRETS.ENV", "config/secrets.env"}) {
            Response read = get(path);
            assertThat(read.status()).as(path).isEqualTo(403);
            assertThat(json(read).get("key").asString()).isEqualTo("ui.file.secrets");
            assertThat(read.body()).doesNotContain("fake-not-a-real-value");
            assertThat(put(path, "X=1\n", "0".repeat(64)).status()).as(path).isEqualTo(403);
        }
        assertThat(Files.readString(ws.resolve("secrets.env"))).contains("fake-not-a-real-value");

        Response tree = UiHttp.get(server, "/api/files/tree");
        assertThat(tree.status()).isEqualTo(200);
        assertThat(tree.body()).doesNotContain("fake-not-a-real-value");
        JsonNode secrets = find(json(tree), "secrets.env");
        assertThat(secrets.get("kind").asString()).isEqualTo("secrets");
    }

    // --- tree -----------------------------------------------------------------------------------

    @Test
    @DisplayName("The tree marks each scenario with its result in the newest report")
    void testTreeLastResult() throws Exception {
        Path file = Files.createDirectories(ws.resolve("reports/20261019-143200-lab")).resolve("results.json");
        Files.writeString(file, "[{\"name\":\"e\",\"sourceName\":\"example.yaml\",\"success\":false,"
                + "\"durationMillis\":1,\"steps\":[{\"index\":0,\"type\":\"ldap.verify\",\"success\":false,"
                + "\"failureKind\":\"FINDING\"}],\"cleanup\":[]}]");

        JsonNode tree = json(UiHttp.get(server, "/api/files/tree"));

        JsonNode example = find(tree, "scenarios/example.yaml");
        assertThat(example.get("kind").asString()).isEqualTo("scenario");
        assertThat(example.get("lastResult").asString()).isEqualTo("finding");
        assertThat(example.get("dir").asBoolean()).isFalse();
        assertThat(example.get("name").asString()).isEqualTo("example.yaml");
        assertThat(find(tree, "config").get("dir").asBoolean()).isTrue();
        assertThat(tree.toString()).doesNotContain("reports");
    }

    @Test
    @DisplayName("GETs change nothing on disk")
    void testGetsHaveNoSideEffects() throws Exception {
        List<String> before = listing(ws);
        assertThat(UiHttp.get(server, "/api/files/tree").status()).isEqualTo(200);
        assertThat(get("scenarios/example.yaml").status()).isEqualTo(200);
        assertThat(get("scenarios/missing.yaml").status()).isEqualTo(404);
        assertThat(listing(ws)).isEqualTo(before);
    }

    // --- add ------------------------------------------------------------------------------------

    @Test
    @DisplayName("Add copies .yaml files into scenarios/ and never overwrites")
    void testAddNeverOverwrites() throws Exception {
        String original = Files.readString(ws.resolve("scenarios/example.yaml"));

        Response added = multipart(Map.of("example.yaml", "name: added\n", "other.yml", "name: other\n"));

        assertThat(added.status()).as(added.body()).isEqualTo(200);
        List<String> paths = new ArrayList<>();
        json(added).get("added").forEach(p -> paths.add(p.asString()));
        assertThat(paths).containsExactlyInAnyOrder("scenarios/example (2).yaml", "scenarios/other.yml");
        assertThat(Files.readString(ws.resolve("scenarios/example.yaml"))).isEqualTo(original);
        assertThat(Files.readString(ws.resolve("scenarios/example (2).yaml"))).isEqualTo("name: added\n");

        Response refused = multipart(Map.of("notes.txt", "x"));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(json(refused).get("key").asString()).isEqualTo("ui.file.notYaml");

        Response big = multipart(Map.of("big.yaml", "a".repeat(WorkspaceFiles.MAX_BYTES + 1)));
        assertThat(big.status()).isEqualTo(413);
        assertThat(json(big).get("key").asString()).isEqualTo("ui.file.tooLarge");
        assertThat(ws.resolve("scenarios/big.yaml")).doesNotExist();
    }

    // --- no workspace ---------------------------------------------------------------------------

    @Test
    @DisplayName("With no workspace open every files endpoint answers 400 ui.workspace.none")
    void testNoWorkspace() throws Exception {
        long watchersBefore = watcherThreads();
        try (UiServer bare = UiCommand.start(0, Locale.ENGLISH)) {
            assertThat(watcherThreads()).isEqualTo(watchersBefore + 1);
            for (Response response : new Response[] {
                    UiHttp.get(bare, "/api/files/tree"),
                    UiHttp.get(bare, "/api/files?path=scenarios/example.yaml"),
                    UiHttp.send(bare, "PUT", "/api/files?path=scenarios/example.yaml",
                            "{\"content\":\"x\",\"version\":\"y\"}"),
                    UiHttp.get(bare, "/api/files/events"),
                    UiHttp.get(bare, "/api/files/tree", Map.of("Accept-Language", "es"))}) {
                assertThat(response.status()).as(response.body()).isEqualTo(400);
                assertThat(json(response).get("key").asString()).isEqualTo("ui.workspace.none");
            }
            assertThat(json(UiHttp.get(bare, "/api/files/tree", Map.of("Accept-Language", "es")))
                    .get("message").asString()).contains("espacio de trabajo");
        }
        assertThat(watcherThreads()).as("the watcher thread stops with the server").isEqualTo(watchersBefore);
    }

    // --- events ---------------------------------------------------------------------------------

    @Test
    @DisplayName("An external write produces a 'changed' event; new and deleted files their own")
    void testChangeEvents() throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/files/events"))
                .header("Cookie", UiHttp.cookie(server)).header("Accept", "text/event-stream").GET().build();
        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        Thread reader = Thread.ofVirtual().start(() -> response.body()
                .filter(line -> line.startsWith("data:"))
                .forEach(line -> events.add(line.substring(5).trim())));
        try {
            Path example = ws.resolve("scenarios/example.yaml");
            JsonNode changed = awaitEvent(events, "scenarios/example.yaml", "changed",
                    i -> Files.writeString(example, "name: external " + i + "\n"));
            assertThat(changed.propertyNames()).containsExactlyInAnyOrder("type", "path");

            Path created = ws.resolve("scenarios/nested/new.yaml");
            awaitEvent(events, "scenarios/nested/new.yaml", "created", i -> {
                if (i == 0) {
                    Files.createDirectories(created.getParent());
                    Files.writeString(created, "name: new\n");
                }
            });
            awaitEvent(events, "scenarios/nested/new.yaml", "deleted", i -> {
                if (i == 0) {
                    Files.delete(created);
                }
            });

            // opening another workspace moves the watcher there; the same subscription gets its events
            Path second = temp.resolve("second");
            InitCommand.run(second, new PrintStream(OutputStream.nullOutputStream()));
            Files.writeString(second.resolve("config/only-here.yml"), "x: 0\n");
            assertThat(UiHttp.send(server, "POST", "/api/workspaces/open",
                    JSON.writeValueAsString(Map.of("path", second.toString()))).status()).isEqualTo(200);
            awaitEvent(events, "config/only-here.yml", "changed",
                    i -> Files.writeString(second.resolve("config/only-here.yml"), "x: " + (i + 1) + "\n"));
        } finally {
            reader.interrupt();
            response.body().close();
            client.close();
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private interface Step {
        void run(int attempt) throws IOException;
    }

    /**
     * Runs {@code step} (attempt 0, 1, …, every 300 ms) until an event for {@code path} of {@code type}
     * arrives — repeating covers a write made before the watcher had registered the folder.
     */
    private static JsonNode awaitEvent(BlockingQueue<String> events, String path, String type, Step step)
            throws Exception {
        long deadline = System.nanoTime() + EVENT_WAIT.toNanos();
        List<String> seen = new ArrayList<>();
        for (int attempt = 0; System.nanoTime() < deadline; attempt++) {
            step.run(attempt);
            String data = events.poll(300, TimeUnit.MILLISECONDS);
            while (data != null) {
                seen.add(data);
                JsonNode event = JSON.readTree(data);
                if (event.get("path").asString().equals(path) && event.get("type").asString().equals(type)) {
                    return event;
                }
                data = events.poll();
            }
        }
        throw new AssertionError("no " + type + " event for " + path + " within " + EVENT_WAIT + "; saw " + seen);
    }

    private static long watcherThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("idmtest-file-watcher") && t.isAlive()).count();
    }

    private static Response get(String path) throws IOException {
        return UiHttp.get(server, "/api/files?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8));
    }

    private static Response put(String path, String content, String version) throws IOException {
        return UiHttp.send(server, "PUT", "/api/files?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8),
                JSON.writeValueAsString(Map.of("content", content, "version", version)));
    }

    /** A multipart POST to /api/files/add with one {@code files} part per entry (file name → content). */
    private static Response multipart(Map<String, String> uploads) throws IOException {
        String boundary = "----idmtest" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> upload : uploads.entrySet()) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                    + upload.getKey() + "\"\r\nContent-Type: application/x-yaml\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(upload.getValue().getBytes(StandardCharsets.UTF_8));
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return UiHttp.send(server, "POST", "/api/files/add", body.toString(StandardCharsets.UTF_8),
                Map.of("Content-Type", "multipart/form-data; boundary=" + boundary));
    }

    private static JsonNode json(Response response) {
        return JSON.readTree(response.body());
    }

    /** The node of the tree whose path is {@code path}. */
    private static JsonNode find(JsonNode node, String path) {
        if (node.get("path").asString().equals(path)) {
            return node;
        }
        JsonNode children = node.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                JsonNode found = find(child, path);
                if (found != null) {
                    return found;
                }
            }
        }
        if (node.get("path").asString().isEmpty()) {
            throw new AssertionError("no " + path + " in the tree " + node);
        }
        return null;
    }

    private static List<String> listing(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.map(p -> root.relativize(p) + "@" + modified(p)).sorted().toList();
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }
}
