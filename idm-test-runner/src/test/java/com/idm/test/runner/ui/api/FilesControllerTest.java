package com.idm.test.runner.ui.api;

import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.model.Scenario;
import com.idm.test.runner.InitCommand;
import com.idm.test.runner.LabFixture;
import com.idm.test.runner.ui.RunEventStream;
import com.idm.test.runner.ui.UiCommand;
import com.idm.test.runner.ui.UiHttp;
import com.idm.test.runner.ui.UiHttp.Response;
import com.idm.test.runner.ui.UiServer;
import com.idm.test.runner.ui.WorkspaceFiles;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        assertThat(json(created).get("message").asString()).contains("New scenario").contains("Add files");
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

    // --- create ---------------------------------------------------------------------------------

    /**
     * The front end's new-scenario template (ITF-70), with its name placeholder left to fill. It has
     * one active step, a {@code set} that touches no system, because a scenario with no steps
     * ({@code steps:} or {@code steps: []}) fails validation with {@code validation.noSteps}.
     */
    private static final String TEMPLATE = """
            name: <file name without extension>
            description: Describe what this scenario checks.

            fixtures:
              user:
                generate: random-user

            steps:
              - set:
                  userDn: "cn=${user.userId},ou=users,o=data"
            #  - ldap.create:
            #      dn: "${userDn}"
            #      as: createdDn
            #      attributes:
            #        cn: "${user.userId}"
            #        sn: "${user.lastName}"
            #  - ldap.verify:
            #      dn: "${createdDn}"
            #      exists: true

            #cleanup:
            #  - ldap.delete:
            #      dn: "${createdDn}"
            """;

    @Test
    @DisplayName("POST /api/files/new creates the file exactly as sent: 201 with its path and version")
    void testCreateScenario() throws Exception {
        String content = "﻿name: disable-user\r\ndescription: niño\r\n";

        Response created = create("scenarios/disable-user.yaml", content);

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        JsonNode body = json(created);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("path", "version");
        assertThat(body.get("path").asString()).isEqualTo("scenarios/disable-user.yaml");
        assertThat(body.get("version").asString())
                .isEqualTo(WorkspaceFiles.version(content.getBytes(StandardCharsets.UTF_8)));
        assertThat(Files.readAllBytes(ws.resolve("scenarios/disable-user.yaml")))
                .isEqualTo(content.getBytes(StandardCharsets.UTF_8));
        JsonNode node = find(json(UiHttp.get(server, "/api/files/tree")), "scenarios/disable-user.yaml");
        assertThat(node.get("kind").asString()).isEqualTo("scenario");
        assertThat(json(get("scenarios/disable-user.yaml")).get("version").asString())
                .isEqualTo(body.get("version").asString());
    }

    @Test
    @DisplayName("Eight creates of one name at once: one 201, seven 409 ui.file.exists, the winner's content kept")
    void testCreateNeverOverwrites() throws Exception {
        String original = Files.readString(ws.resolve("scenarios/example.yaml"));
        Response existing = create("scenarios/example.yaml", "name: mine\n");
        assertThat(existing.status()).isEqualTo(409);
        assertThat(json(existing).get("key").asString()).isEqualTo("ui.file.exists");
        assertThat(json(existing).get("message").asString())
                .isEqualTo("scenarios/example.yaml already exists; choose another name.");
        assertThat(Files.readString(ws.resolve("scenarios/example.yaml"))).isEqualTo(original);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Response>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String content = "name: twin\nwriter: " + i + "\n";
                results.add(pool.submit(() -> {
                    go.await();
                    return create("scenarios/twin.yaml", content);
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            String winner = null;
            for (int i = 0; i < 8; i++) {
                Response response = results.get(i).get();
                statuses.add(response.status());
                if (response.status() == 201) {
                    winner = "name: twin\nwriter: " + i + "\n";
                } else {
                    assertThat(json(response).get("key").asString()).isEqualTo("ui.file.exists");
                }
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(7);
            assertThat(Files.readString(ws.resolve("scenarios/twin.yaml"))).isEqualTo(winner);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Bad names and paths outside scenarios/ are 400 ui.file.badName or ui.file.badPath; nothing is written")
    void testCreateRefusals() throws Exception {
        List<String> before = listing(temp);
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("../x.yaml", "ui.file.badPath");
        refusals.put("scenarios/../config/a.yaml", "ui.file.badPath");
        refusals.put("config/a.yaml", "ui.file.badPath");
        refusals.put("scenarios/secrets.env", "ui.file.badPath");
        refusals.put("scenarios/new-scenario", "ui.file.badPath");
        refusals.put("scenarios/missing/a.yaml", "ui.file.badPath");
        refusals.put("scenarios/a:b.yaml", "ui.file.badName");
        refusals.put("scenarios/", "ui.file.badName");
        refusals.put("scenarios/a\\b.yaml", "ui.file.badName");
        refusals.put("scenarios/a..b.yaml", "ui.file.badName");
        for (String name : new String[] {"con", "CON", "prn", "aux", "Nul", "com1", "COM9", "lpt1", "lpt9",
                "con.backup", "nul .x", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a ", "a."}) {
            refusals.put("scenarios/" + name + ".yaml", "ui.file.badName");
        }
        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            Response response = create(refusal.getKey(), "name: x\n");
            assertThat(response.status()).as(refusal.getKey() + " " + response.body()).isEqualTo(400);
            assertThat(json(response).get("key").asString()).as(refusal.getKey()).isEqualTo(refusal.getValue());
        }
        Response badName = create("scenarios/a:b.yaml", "name: x\n");
        assertThat(json(badName).get("message").asString())
                .isEqualTo("'a:b.yaml' is not a file name: use letters, digits, spaces, - and _, without / \\ .. or :.");
        Response big = create("scenarios/big.yaml", "a".repeat(WorkspaceFiles.MAX_BYTES + 1));
        assertThat(big.status()).isEqualTo(413);
        assertThat(json(big).get("key").asString()).isEqualTo("ui.file.tooLarge");
        Response noContent = UiHttp.send(server, "POST", "/api/files/new",
                JSON.writeValueAsString(Map.of("path", "scenarios/b.yaml")));
        assertThat(noContent.status()).isEqualTo(400);
        assertThat(json(noContent).get("key").asString()).isEqualTo("ui.request.invalid");
        assertThat(listing(temp)).isEqualTo(before);
    }

    @Test
    @DisplayName("The refusals read in Spanish with Accept-Language: es")
    void testCreateMessagesInSpanish() throws Exception {
        Map<String, String> es = Map.of("Accept-Language", "es");
        Response exists = UiHttp.send(server, "POST", "/api/files/new",
                JSON.writeValueAsString(Map.of("path", "scenarios/example.yaml", "content", "x: 1\n")), es);
        assertThat(json(exists).get("message").asString())
                .isEqualTo("scenarios/example.yaml ya existe; elija otro nombre.");
        Response badName = UiHttp.send(server, "POST", "/api/files/new",
                JSON.writeValueAsString(Map.of("path", "scenarios/a:b.yaml", "content", "x: 1\n")), es);
        assertThat(json(badName).get("message").asString()).isEqualTo(
                "'a:b.yaml' no es un nombre de archivo: use letras, dígitos, espacios, - y _, sin / \\ .. ni :.");
    }

    @Test
    @DisplayName("The new-scenario template, named, validates with zero problems and keeps the file stem as name")
    void testTemplateValidates() throws Exception {
        // the stem, and how the workbench's scenarioTemplate.ts writes it: plain, or double-quoted when
        // YAML would read it as something else (a comment, null, a boolean, a number, a date)
        Map<String, String> names = new LinkedHashMap<>();
        names.put("disable-user", "disable-user");
        names.put("#1 [x]", "\"#1 [x]\"");
        names.put("null", "\"null\"");
        names.put("Yes", "\"Yes\"");
        names.put("1.50", "\"1.50\"");
        names.put("0x10", "\"0x10\"");
        names.put("2026-10-24", "\"2026-10-24\"");
        for (Map.Entry<String, String> name : names.entrySet()) {
            String content = TEMPLATE.replace("<file name without extension>", name.getValue());

            Response validated = UiHttp.send(server, "POST", "/api/validate", JSON.writeValueAsString(
                    Map.of("path", "scenarios/" + name.getKey() + ".yaml", "content", content)));

            assertThat(validated.status()).as(validated.body()).isEqualTo(200);
            assertThat(json(validated).get("problems").size()).as(name.getKey() + " " + validated.body()).isZero();
            Scenario scenario = ScenarioLoader.load(
                    new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), name.getKey() + ".yaml");
            assertThat(scenario.name()).isEqualTo(name.getKey());
        }
        // unquoted, null is no name at all: why the template quotes it
        Response unquoted = UiHttp.send(server, "POST", "/api/validate", JSON.writeValueAsString(Map.of(
                "path", "scenarios/null.yaml", "content", TEMPLATE.replace("<file name without extension>", "null"))));
        assertThat(unquoted.body()).contains("validation.load.nameRequired");
    }

    private static Response create(String path, String content) throws IOException {
        return UiHttp.send(server, "POST", "/api/files/new",
                JSON.writeValueAsString(Map.of("path", path, "content", content)));
    }

    // --- delete ---------------------------------------------------------------------------------

    @Test
    @DisplayName("DELETE with the version read: 204, the file is gone from disk and the tree, and the watcher says deleted")
    void testDelete() throws Exception {
        Path doomed = ws.resolve("scenarios/users/doomed.yaml");
        Files.createDirectories(doomed.getParent());
        Files.writeString(doomed, "name: doomed\n");
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<Stream<String>> stream = client.send(eventsRequest(), HttpResponse.BodyHandlers.ofLines());
        Thread reader = Thread.ofVirtual().start(() -> {
            try (Stream<String> lines = stream.body()) {
                lines.filter(line -> line.startsWith("data:")).forEach(line -> events.add(line.substring(5).trim()));
            } catch (RuntimeException e) {
                // closed
            }
        });
        try {
            String version = json(get("scenarios/users/doomed.yaml")).get("version").asString();
            // the watcher may only now register the new folder: wait until it reports a change there
            awaitEvent(events, "scenarios/users/doomed.yaml", "changed",
                    i -> Files.writeString(doomed, "name: doomed\n"));

            Response deleted = delete("scenarios/users/doomed.yaml", version);

            assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);
            assertThat(doomed).doesNotExist();
            assertThat(json(UiHttp.get(server, "/api/files/tree")).toString()).doesNotContain("doomed.yaml");
            awaitEvent(events, "scenarios/users/doomed.yaml", "deleted", i -> { });
        } finally {
            reader.interrupt();
            stream.body().close();
            client.close();
        }
    }

    @Test
    @DisplayName("A delete the disk refuses answers 500 ui.file.deleteFailed (not 'could not write'), in English and Spanish")
    void testDeleteFailed() throws Exception {
        Assumptions.assumeTrue(ws.getFileSystem().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
        Path folder = Files.createDirectories(ws.resolve("scenarios/locked"));
        Files.writeString(folder.resolve("x.yaml"), "name: x\n");
        String version = json(get("scenarios/locked/x.yaml")).get("version").asString();
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Assumptions.assumeFalse(Files.isWritable(folder), "the folder stays writable (running as root?)");
            Response failed = delete("scenarios/locked/x.yaml", version);
            assertThat(failed.status()).as(failed.body()).isEqualTo(500);
            assertThat(json(failed).get("key").asString()).isEqualTo("ui.file.deleteFailed");
            assertThat(json(failed).get("message").asString()).startsWith("could not delete scenarios/locked/x.yaml: ");
            Response es = UiHttp.send(server, "DELETE", "/api/files?path=scenarios%2Flocked%2Fx.yaml&version="
                    + URLEncoder.encode(version, StandardCharsets.UTF_8), null, Map.of("Accept-Language", "es"));
            assertThat(json(es).get("message").asString()).startsWith("no se pudo eliminar scenarios/locked/x.yaml: ");
            assertThat(folder.resolve("x.yaml")).exists();
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    @DisplayName("DELETE with an old version answers 409 ui.file.changed with the current version and keeps the file")
    void testDeleteStaleVersionRefused() throws Exception {
        String version = json(get("scenarios/example.yaml")).get("version").asString();
        Files.writeString(ws.resolve("scenarios/example.yaml"), "name: from git pull\n");

        Response conflict = delete("scenarios/example.yaml", version);

        assertThat(conflict.status()).isEqualTo(409);
        JsonNode body = json(conflict);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("key", "message", "currentVersion");
        assertThat(body.get("key").asString()).isEqualTo("ui.file.changed");
        assertThat(body.get("currentVersion").asString())
                .isEqualTo(WorkspaceFiles.version("name: from git pull\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(Files.readString(ws.resolve("scenarios/example.yaml"))).isEqualTo("name: from git pull\n");
    }

    @Test
    @DisplayName("DELETE refuses config, secrets.env, scenarios/, escapes, links out and a missing version; nothing is deleted")
    void testDeleteRefusals() throws Exception {
        Files.writeString(ws.resolve("secrets.env"), "IDM_TEST_LDAP_PASSWORD=fake-not-a-real-value\n");
        Files.writeString(temp.resolve("outside.yaml"), "x: 1\n");
        String lab = Files.readString(ws.resolve("config/application-lab.yml"));
        String example = json(get("scenarios/example.yaml")).get("version").asString();
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("config/application-lab.yml", "400 ui.file.notDeletable");
        refusals.put("scenarios", "400 ui.file.notDeletable");
        refusals.put("secrets.env", "403 ui.file.secrets");
        refusals.put("../outside.yaml", "400 ui.file.badPath");
        refusals.put("scenarios/missing.yaml", "404 ui.file.notFound");
        try {
            Files.createSymbolicLink(ws.resolve("scenarios/out.yaml"), temp.resolve("outside.yaml"));
            refusals.put("scenarios/out.yaml", "400 ui.file.badPath");
        } catch (UnsupportedOperationException | IOException e) {
            // no symbolic links on this system: the other refusals are still checked
        }
        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            Response response = delete(refusal.getKey(), WorkspaceFiles.version("x: 1\n".getBytes(StandardCharsets.UTF_8)));
            assertThat(response.status() + " " + json(response).get("key").asString()).as(refusal.getKey())
                    .isEqualTo(refusal.getValue());
            assertThat(response.body()).doesNotContain("fake-not-a-real-value");
        }
        Response notDeletable = delete("config/application-lab.yml", "0".repeat(64));
        assertThat(json(notDeletable).get("message").asString())
                .isEqualTo("only scenario files under scenarios/ can be deleted.");
        Response spanish = UiHttp.send(server, "DELETE", "/api/files?path=config/application-lab.yml&version=x", null,
                Map.of("Accept-Language", "es"));
        assertThat(json(spanish).get("message").asString())
                .isEqualTo("solo se pueden eliminar archivos de escenario de scenarios/.");

        for (String query : new String[] {"", "&version=", "&version=%20"}) {
            Response noVersion = UiHttp.send(server, "DELETE", "/api/files?path=scenarios/example.yaml" + query, null);
            assertThat(noVersion.status()).as(query).isEqualTo(400);
            assertThat(json(noVersion).get("key").asString()).isEqualTo("ui.request.invalid");
            assertThat(json(noVersion).get("message").asString()).contains("version");
        }

        assertThat(Files.readString(ws.resolve("config/application-lab.yml"))).isEqualTo(lab);
        assertThat(ws.resolve("secrets.env")).exists();
        assertThat(temp.resolve("outside.yaml")).exists();
        assertThat(json(get("scenarios/example.yaml")).get("version").asString()).isEqualTo(example);
    }

    @Test
    @DisplayName("DELETE of a scenario of the run that is going: 409 ui.file.inRun, kept; one not in that run is deleted")
    void testDeleteRefusedDuringItsRun() throws Exception {
        try (LabFixture lab = LabFixture.start();
             UiServer running = UiCommand.start(0, Locale.ENGLISH, null, Map.of())) {
            // a run that waits 60 s for a value it never sees, until it is stopped
            Path x = lab.workspace(temp.resolve("running-ws"), LabFixture.database(temp.resolve("db")), null);
            Path settings = x.resolve("config/application-lab.yml");
            Files.writeString(settings, Files.readString(settings)
                    .replace("default-timeout-seconds: 2", "default-timeout-seconds: 60"));
            LabFixture.scenario(x, "users/wrong-sn.yaml", LabFixture.WRONG_SN);
            LabFixture.scenario(x, "other.yaml", "name: other\n");
            assertThat(UiHttp.send(running, "POST", "/api/workspaces/open",
                    JSON.writeValueAsString(Map.of("path", x.toString()))).status()).isEqualTo(200);
            Response started = UiHttp.send(running, "POST", "/api/runs", JSON.writeValueAsString(
                    Map.of("profile", "lab", "scenarios", List.of("scenarios/users/wrong-sn.yaml"))));
            assertThat(started.status()).as(started.body()).isEqualTo(202);
            try (RunEventStream stream = new RunEventStream(running)) {
                stream.until("attempt");
                String version = WorkspaceFiles.version(LabFixture.WRONG_SN.getBytes(StandardCharsets.UTF_8));

                Response refused = UiHttp.send(running, "DELETE",
                        "/api/files?path=scenarios/users/wrong-sn.yaml&version=" + version, null);
                assertThat(refused.status()).as(refused.body()).isEqualTo(409);
                JsonNode body = json(refused);
                assertThat(body.propertyNames()).containsExactlyInAnyOrder("key", "message");
                assertThat(body.get("key").asString()).isEqualTo("ui.file.inRun");
                assertThat(body.get("message").asString()).isEqualTo(
                        "scenarios/users/wrong-sn.yaml is part of the run that is going; wait for it to finish or stop it.");
                Response spanish = UiHttp.send(running, "DELETE",
                        "/api/files?path=scenarios/users/wrong-sn.yaml&version=" + version, null,
                        Map.of("Accept-Language", "es"));
                assertThat(json(spanish).get("message").asString()).isEqualTo(
                        "scenarios/users/wrong-sn.yaml forma parte de la ejecución en curso; espere a que termine o deténgala.");
                assertThat(x.resolve("scenarios/users/wrong-sn.yaml")).exists();

                Response other = UiHttp.send(running, "DELETE", "/api/files?path=scenarios/other.yaml&version="
                        + WorkspaceFiles.version("name: other\n".getBytes(StandardCharsets.UTF_8)), null);
                assertThat(other.status()).as(other.body()).isEqualTo(204);
                assertThat(x.resolve("scenarios/other.yaml")).doesNotExist();

                assertThat(UiHttp.send(running, "POST", "/api/runs/current/stop", null).status()).isEqualTo(202);
                assertThat(stream.all().getLast().name()).isEqualTo("runFinished");
            }
            // the run is over: its scenario can go now
            Response after = UiHttp.send(running, "DELETE", "/api/files?path=scenarios/users/wrong-sn.yaml&version="
                    + WorkspaceFiles.version(LabFixture.WRONG_SN.getBytes(StandardCharsets.UTF_8)), null);
            assertThat(after.status()).as(after.body()).isEqualTo(204);
        }
    }

    // --- git state ------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/files/git: none without .git, then clean, changed and untracked; it changes nothing")
    void testGitState() throws Exception {
        assertThat(gitState("scenarios/example.yaml")).isEqualTo("none");
        Response secrets = UiHttp.get(server, "/api/files/git?path=secrets.env");
        assertThat(secrets.status()).isEqualTo(403);
        assertThat(UiHttp.get(server, "/api/files/git?path=../x.yaml").status()).isEqualTo(400);

        org.junit.jupiter.api.Assumptions.assumeTrue(gitAvailable(), "git is not installed");
        git("init", "--quiet");
        git("add", "-A");
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false",
                "commit", "--quiet", "--no-verify", "-m", "first");
        Files.writeString(ws.resolve("scenarios/new.yaml"), "name: new\n");
        assertThat(gitState("scenarios/example.yaml")).isEqualTo("clean");
        assertThat(gitState("scenarios/new.yaml")).isEqualTo("untracked");
        Files.writeString(ws.resolve("scenarios/example.yaml"), "name: edited\n");
        List<String> before = listing(ws);
        assertThat(gitState("scenarios/example.yaml")).isEqualTo("changed");
        assertThat(listing(ws)).as("nothing in the workspace or its .git changed").isEqualTo(before);
    }

    private static String gitState(String path) throws IOException {
        Response response = UiHttp.get(server, "/api/files/git?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.propertyNames()).containsExactly("state");
        return body.get("state").asString();
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(ws.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("git " + String.join(" ", args) + ": " + output).isZero();
    }

    private static Response delete(String path, String version) throws IOException {
        return UiHttp.send(server, "DELETE", "/api/files?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8)
                + "&version=" + URLEncoder.encode(version, StandardCharsets.UTF_8), null);
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
                    UiHttp.get(bare, "/api/files/git?path=scenarios/example.yaml"),
                    UiHttp.send(bare, "DELETE", "/api/files?path=scenarios/example.yaml&version=y", null),
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
        HttpResponse<Stream<String>> response = client.send(eventsRequest(), HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        AtomicBoolean ended = new AtomicBoolean();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (Stream<String> lines = response.body()) {
                lines.filter(line -> line.startsWith("data:")).forEach(line -> events.add(line.substring(5).trim()));
            } catch (RuntimeException e) {
                // closed
            }
            ended.set(true);
        });
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

            // opening another workspace moves the watcher there and ends the first workspace's stream
            Path second = temp.resolve("second");
            InitCommand.run(second, new PrintStream(OutputStream.nullOutputStream()));
            Files.writeString(second.resolve("config/only-here.yml"), "x: 0\n");
            assertThat(UiHttp.send(server, "POST", "/api/workspaces/open",
                    JSON.writeValueAsString(Map.of("path", second.toString()))).status()).isEqualTo(200);
            long deadline = System.nanoTime() + EVENT_WAIT.toNanos();
            while (!ended.get() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(ended).as("the first workspace's stream ended").isTrue();

            // a tab of the second workspace subscribes for it and gets its events
            HttpResponse<Stream<String>> again = client.send(eventsRequest(), HttpResponse.BodyHandlers.ofLines());
            assertThat(again.statusCode()).isEqualTo(200);
            BlockingQueue<String> secondEvents = new LinkedBlockingQueue<>();
            Thread secondReader = Thread.ofVirtual().start(() -> {
                try (Stream<String> lines = again.body()) {
                    lines.filter(line -> line.startsWith("data:"))
                            .forEach(line -> secondEvents.add(line.substring(5).trim()));
                } catch (RuntimeException e) {
                    // closed
                }
            });
            try {
                awaitEvent(secondEvents, "config/only-here.yml", "changed",
                        i -> Files.writeString(second.resolve("config/only-here.yml"), "x: " + (i + 1) + "\n"));
            } finally {
                secondReader.interrupt();
                again.body().close();
            }
        } finally {
            reader.interrupt();
            response.body().close();
            client.close();
        }
    }

    /** The files events of the session's current workspace, as the page asks for them. */
    private static HttpRequest eventsRequest() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                        + UiHttp.withWorkspace(server, "/api/files/events")))
                .header("Cookie", UiHttp.cookie(server)).header("Accept", "text/event-stream").GET().build();
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
