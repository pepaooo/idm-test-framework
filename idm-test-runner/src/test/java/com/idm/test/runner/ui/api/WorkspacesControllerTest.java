package com.idm.test.runner.ui.api;

import com.idm.test.runner.InitCommand;
import com.idm.test.runner.LabFixture;
import com.idm.test.runner.ui.RunEventStream;
import com.idm.test.runner.ui.UiCommand;
import com.idm.test.runner.ui.UiHttp;
import com.idm.test.runner.ui.UiHttp.Response;
import com.idm.test.runner.ui.UiServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The start page's API, over HTTP against a running workbench whose {@code user.home} is a temp folder. */
@DisplayName("Workbench workspaces API")
class WorkspacesControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    static Path home;
    private static String realHome;
    private static UiServer server;

    @TempDir
    Path temp;

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
    void clearRecent() throws IOException {
        Files.deleteIfExists(recentFile());
    }

    private static Path recentFile() {
        return home.resolve(".idmtest/recent.json");
    }

    // --- create -------------------------------------------------------------------------------

    @Test
    @DisplayName("Create writes the files idmtest init writes and adds the workspace to the recent list")
    void testCreateLikeInit() throws Exception {
        Response created = post("/api/workspaces", Map.of("name", "My Tests ñ", "parent", temp.toString(),
                "includeExample", true, "gitInit", false));

        assertThat(created.status()).as(created.body()).isEqualTo(200);
        Path target = temp.resolve("My Tests ñ");
        assertThat(json(created).get("path").asString()).isEqualTo(target.toString());
        Path viaInit = temp.resolve("via-init");
        InitCommand.run(viaInit, new PrintStream(OutputStream.nullOutputStream()));
        assertThat(files(target)).isEqualTo(files(viaInit)).contains("scenarios/example.yaml");
        assertThat(target.resolve(".git")).doesNotExist();

        JsonNode recent = json(UiHttp.get(server, "/api/workspaces/recent"));
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).get("path").asString()).isEqualTo(target.toString());
        assertThat(recent.get(0).get("name").asString()).isEqualTo("My Tests ñ");
        assertThat(recent.get(0).get("exists").asBoolean()).isTrue();
        assertThat(recent.get(0).get("lastRun").isNull()).isTrue();
    }

    @Test
    @DisplayName("Create without the example leaves scenarios/ empty")
    void testCreateWithoutExample() throws Exception {
        Response created = post("/api/workspaces", Map.of("name", "bare", "parent", temp.toString(),
                "includeExample", false, "gitInit", false));
        assertThat(created.status()).isEqualTo(200);
        assertThat(temp.resolve("bare/scenarios")).isEmptyDirectory();
        assertThat(temp.resolve("bare/config/application-lab.yml")).exists();
    }

    @Test
    @DisplayName("gitInit starts a Git repository in the new workspace")
    void testCreateWithGit() throws Exception {
        Assumptions.assumeTrue(json(UiHttp.get(server, "/api/health")).get("git").asBoolean(), "git not installed");
        Response created = post("/api/workspaces", Map.of("name", "versioned", "parent", temp.toString(),
                "includeExample", true, "gitInit", true));
        assertThat(created.status()).as(created.body()).isEqualTo(200);
        assertThat(temp.resolve("versioned/.git")).isDirectory();
        assertThat(json(created).get("warning").isNull()).isTrue();
    }

    @Test
    @DisplayName("Create refuses a folder that exists and is not empty, and bad names, with a key")
    void testCreateRefusals() throws Exception {
        Files.createDirectories(temp.resolve("taken"));
        Files.writeString(temp.resolve("taken/notes.txt"), "mine");
        Response taken = post("/api/workspaces", Map.of("name", "taken", "parent", temp.toString(),
                "includeExample", true, "gitInit", false));
        assertThat(taken.status()).isEqualTo(400);
        assertThat(json(taken).get("key").asString()).isEqualTo("ui.workspace.notEmpty");
        assertThat(json(taken).get("message").asString()).contains("taken");
        assertThat(temp.resolve("taken/scenarios")).doesNotExist();

        for (String name : new String[] {"", "..", "a/b", "a\\b", "C:x", "a:b"}) {
            Response bad = post("/api/workspaces", Map.of("name", name, "parent", temp.toString(),
                    "includeExample", true, "gitInit", false));
            assertThat(bad.status()).as(name).isEqualTo(400);
            assertThat(json(bad).get("key").asString()).isEqualTo("ui.workspace.badName");
        }
        Response noParent = post("/api/workspaces", Map.of("name", "x", "parent", temp.resolve("missing").toString(),
                "includeExample", true, "gitInit", false));
        assertThat(json(noParent).get("key").asString()).isEqualTo("ui.workspace.badParent");


        Files.createDirectories(temp.resolve("empty"));
        assertThat(post("/api/workspaces", Map.of("name", "empty", "parent", temp.toString(),
                "includeExample", true, "gitInit", false)).status()).as("an empty folder is fine").isEqualTo(200);
    }

    // --- client errors ------------------------------------------------------------------------

    @Test
    @DisplayName("Spring's client errors keep their status and the {key, message} body")
    void testClientErrorsKeepShape() throws Exception {
        Response malformed = UiHttp.send(server, "POST", "/api/workspaces", "{nope");
        assertThat(malformed.status()).isEqualTo(400);
        assertError(malformed, "ui.request.invalid");

        Response mistyped = UiHttp.send(server, "POST", "/api/workspaces",
                "{\"name\":\"x\",\"parent\":\"/tmp\",\"includeExample\":{\"no\":1}}");
        assertThat(mistyped.status()).isEqualTo(400);
        assertError(mistyped, "ui.request.invalid");

        Response wrongType = UiHttp.send(server, "POST", "/api/workspaces/open", "path=/tmp",
                Map.of("Content-Type", "text/plain"));
        assertThat(wrongType.status()).isEqualTo(415);
        assertError(wrongType, "ui.request.invalid");

        Response wrongMethod = UiHttp.get(server, "/api/workspaces/open");
        assertThat(wrongMethod.status()).isEqualTo(405);
        assertError(wrongMethod, "ui.request.invalid");

        Response missingParameter = UiHttp.send(server, "DELETE", "/api/workspaces/recent", null);
        assertThat(missingParameter.status()).isEqualTo(400);
        assertError(missingParameter, "ui.request.invalid");
        assertThat(json(missingParameter).get("message").asString()).contains("path");

        Response spanish = UiHttp.get(server, "/api/workspaces/open", Map.of("Accept-Language", "es"));
        assertThat(spanish.status()).isEqualTo(405);
        assertThat(json(spanish).get("message").asString()).contains("no se pudo leer la petición");
    }

    private static void assertError(Response response, String key) {
        assertThat(response.header("Content-Type")).as(response.body()).startsWith("application/json");
        JsonNode body = json(response);
        assertThat(body.get("key").asString()).isEqualTo(key);
        assertThat(body.get("message").asString()).isNotBlank();
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("key", "message");
    }

    // --- open ---------------------------------------------------------------------------------

    @Test
    @DisplayName("Open makes the workspace current, lists its profiles and adds it to the recent list")
    void testOpen() throws Exception {
        Path ws = temp.resolve("ws");
        InitCommand.run(ws, new PrintStream(OutputStream.nullOutputStream()));
        Files.writeString(ws.resolve("config/application-uat.yml"), "");

        Response opened = post("/api/workspaces/open", Map.of("path", ws.toString()));

        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        assertThat(json(opened).get("path").asString()).isEqualTo(ws.toString());
        List<String> profiles = new ArrayList<>();
        json(opened).get("profiles").forEach(p -> profiles.add(p.asString()));
        assertThat(profiles).containsExactly("lab", "uat");
        assertThat(server.session().workspace()).isEqualTo(ws);
        assertThat(json(UiHttp.get(server, "/api/workspaces/recent")).get(0).get("path").asString())
                .isEqualTo(ws.toString());
    }

    @Test
    @DisplayName("A new profile uses the commented starter and lists profiles without changing secrets")
    void testCreateProfile() throws Exception {
        Path ws = temp.resolve("profile-ws");
        InitCommand.run(ws, new PrintStream(OutputStream.nullOutputStream()));
        Files.writeString(ws.resolve("secrets.env"), "SHARED_SECRET=keep\n");
        post("/api/workspaces/open", Map.of("path", ws.toString()));

        Response created = post("/api/workspaces/profiles", Map.of("name", "uat"));

        assertThat(created.status()).as(created.body()).isEqualTo(200);
        Path file = ws.resolve("config/application-uat.yml");
        assertThat(json(created).get("name").asString()).isEqualTo("uat");
        assertThat(json(created).get("path").asString()).isEqualTo(file.toString());
        assertThat(json(created).get("profiles")).extracting(p -> p.asString()).containsExactly("lab", "uat");
        assertThat(Files.readString(file)).startsWith("# Settings for the \"uat\" profile: idmtest run --profile uat");
        assertThat(Files.readAllLines(file)).allMatch(line -> line.isBlank() || line.stripLeading().startsWith("#"));
        assertThat(Files.readString(ws.resolve("secrets.env"))).isEqualTo("SHARED_SECRET=keep\n");
    }

    @Test
    @DisplayName("Profile creation rejects collisions, unsafe names, and symlink targets")
    void testCreateProfileRefusals() throws Exception {
        try (UiServer unopenedServer = UiCommand.start(0, Locale.ENGLISH)) {
            Response unopened = UiHttp.send(unopenedServer, "POST", "/api/workspaces/profiles",
                    JSON.writeValueAsString(Map.of("name", "uat")));
            assertThat(unopened.status()).isEqualTo(400);
            assertError(unopened, "ui.workspace.none");
        }

        Path ws = temp.resolve("profile-refusals");
        InitCommand.run(ws, new PrintStream(OutputStream.nullOutputStream()));
        post("/api/workspaces/open", Map.of("path", ws.toString()));
        for (String name : new String[] {"LAB", "../evil", "a/b", "", "a b"}) {
            Response refused = post("/api/workspaces/profiles", Map.of("name", name));
            assertThat(refused.status()).as(name + " " + refused.body()).isEqualTo(400);
        }
        Path outside = temp.resolve("outside.yml");
        Files.writeString(outside, "do not change");
        Files.createSymbolicLink(ws.resolve("config/application-link.yml"), outside);
        Response linked = post("/api/workspaces/profiles", Map.of("name", "link"));
        assertThat(linked.status()).as(linked.body()).isEqualTo(400);
        assertThat(Files.readString(outside)).isEqualTo("do not change");
        assertThat(ws.resolve("config/application-uat.yml")).doesNotExist();
    }

    @Test
    @DisplayName("Open refuses a folder without scenarios/, in the request's language")
    void testOpenRefusesNonWorkspace() throws Exception {
        Path plain = Files.createDirectories(temp.resolve("plain"));
        Response refused = post("/api/workspaces/open", Map.of("path", plain.toString()));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.header("Content-Type")).startsWith("application/json");
        assertThat(json(refused).get("key").asString()).isEqualTo("ui.workspace.notWorkspace");
        assertThat(json(refused).get("message").asString()).contains(plain.toString(), "scenarios/");

        Response spanish = UiHttp.send(server, "POST", "/api/workspaces/open",
                JSON.writeValueAsString(Map.of("path", plain.toString())), Map.of("Accept-Language", "es-ES,es;q=0.9"));
        assertThat(json(spanish).get("key").asString()).isEqualTo("ui.workspace.notWorkspace");
        assertThat(json(spanish).get("message").asString()).contains("espacio de trabajo");
        assertThat(recentFile()).doesNotExist();
    }

    @Test
    @DisplayName("Open refuses another workspace while a run is going (409 naming the running one), in the request's language")
    void testOpenRefusedWhileRunning() throws Exception {
        try (LabFixture lab = LabFixture.start();
             UiServer running = UiCommand.start(0, Locale.ENGLISH, null, Map.of())) {
            // a run that waits 60 s for a value it never sees, until it is stopped
            Path x = lab.workspace(temp.resolve("running-ws"), LabFixture.database(temp.resolve("db")), null);
            Path settings = x.resolve("config/application-lab.yml");
            Files.writeString(settings, Files.readString(settings)
                    .replace("default-timeout-seconds: 2", "default-timeout-seconds: 60"));
            LabFixture.scenario(x, "wrong-sn.yaml", LabFixture.WRONG_SN);
            Path y = temp.resolve("other-ws");
            InitCommand.run(y, new PrintStream(OutputStream.nullOutputStream()));

            Response opened = UiHttp.send(running, "POST", "/api/workspaces/open",
                    JSON.writeValueAsString(Map.of("path", x.toString())));
            assertThat(opened.status()).as(opened.body()).isEqualTo(200);
            Response started = UiHttp.send(running, "POST", "/api/runs",
                    JSON.writeValueAsString(Map.of("profile", "lab")));
            assertThat(started.status()).as(started.body()).isEqualTo(202);
            try (RunEventStream stream = new RunEventStream(running)) {
                stream.until("attempt");

                Response refused = UiHttp.send(running, "POST", "/api/workspaces/open",
                        JSON.writeValueAsString(Map.of("path", y.toString())));
                assertThat(refused.status()).as(refused.body()).isEqualTo(409);
                assertThat(refused.header("Content-Type")).startsWith("application/json");
                JsonNode body = json(refused);
                assertThat(body.propertyNames()).containsExactlyInAnyOrder("key", "message", "runningWorkspace");
                assertThat(body.get("key").asString()).isEqualTo("ui.workspace.runGoing");
                assertThat(body.get("runningWorkspace").get("name").asString()).isEqualTo("running-ws");
                assertThat(body.get("runningWorkspace").get("path").asString())
                        .isEqualTo(x.toRealPath().toString());
                assertThat(body.get("message").asString())
                        .isEqualTo("A run is going in running-ws. Wait for it to finish, or stop it.");

                Response spanish = UiHttp.send(running, "POST", "/api/workspaces/open",
                        JSON.writeValueAsString(Map.of("path", y.toString())), Map.of("Accept-Language", "es"));
                assertThat(spanish.status()).isEqualTo(409);
                assertThat(json(spanish).get("message").asString()).isEqualTo(
                        "Hay una ejecución en curso en running-ws. Espere a que termine o deténgala.");

                assertThat(json(UiHttp.get(running, "/api/health")).get("workspace").asString())
                        .isEqualTo(x.toString());
                // the refused workspace is not added to the recent list
                assertThat(json(UiHttp.get(running, "/api/workspaces/recent")))
                        .noneMatch(r -> r.get("path").asString().equals(y.toString()));
                // opening the running workspace itself is still allowed
                Response same = UiHttp.send(running, "POST", "/api/workspaces/open",
                        JSON.writeValueAsString(Map.of("path", x.toString())));
                assertThat(same.status()).as(same.body()).isEqualTo(200);

                Response stop = UiHttp.send(running, "POST", "/api/runs/current/stop", null);
                assertThat(stop.status()).as(stop.body()).isEqualTo(202);
                assertThat(stream.all().getLast().name()).isEqualTo("runFinished");
            }
        }
    }

    // --- recent -------------------------------------------------------------------------------

    @Test
    @DisplayName("The recent list survives a restart and reports a missing folder as exists:false")
    void testRecentSurvivesRestart() throws Exception {
        Path ws = temp.resolve("gone");
        InitCommand.run(ws, new PrintStream(OutputStream.nullOutputStream()));
        assertThat(post("/api/workspaces/open", Map.of("path", ws.toString())).status()).isEqualTo(200);
        deleteTree(ws);

        try (UiServer again = UiCommand.start(0, Locale.ENGLISH)) {
            JsonNode recent = json(UiHttp.get(again, "/api/workspaces/recent"));
            assertThat(recent).hasSize(1);
            assertThat(recent.get(0).get("path").asString()).isEqualTo(ws.toString());
            assertThat(recent.get(0).get("exists").asBoolean()).isFalse();
            assertThat(recent.get(0).get("lastRun").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("DELETE takes a folder off the recent list")
    void testRemoveRecent() throws Exception {
        Path ws = temp.resolve("listed");
        InitCommand.run(ws, new PrintStream(OutputStream.nullOutputStream()));
        post("/api/workspaces/open", Map.of("path", ws.toString()));

        Response removed = UiHttp.send(server, "DELETE",
                "/api/workspaces/recent?path=" + URLEncoder.encode(ws.toString(), StandardCharsets.UTF_8), null);
        assertThat(removed.status()).isIn(200, 204);
        assertThat(json(UiHttp.get(server, "/api/workspaces/recent"))).isEmpty();
        assertThat(ws).isDirectory();
    }

    @Test
    @DisplayName("lastRun comes from the newest reports/*/results.json")
    void testLastRun() throws Exception {
        Path ws = temp.resolve("ran");
        InitCommand.run(ws, new PrintStream(OutputStream.nullOutputStream()));
        writeResults(ws, "20261018-091000-uat", Instant.parse("2026-10-18T07:10:00Z"),
                "[" + passed("a.yaml") + "]");
        writeResults(ws, "20261019-143200-lab", Instant.parse("2026-10-19T12:32:00Z"),
                "[" + passed("a.yaml") + "," + failed("b.yaml", "FINDING") + "," + failed("c.yaml", "FINDING")
                        + "," + failed("d.yaml", "SETUP") + "," + notLoaded("e.yaml") + "]");
        Files.createDirectories(ws.resolve("reports/20261020-000000-lab")); // a run that has written nothing yet
        post("/api/workspaces/open", Map.of("path", ws.toString()));

        JsonNode lastRun = json(UiHttp.get(server, "/api/workspaces/recent")).get(0).get("lastRun");

        assertThat(lastRun.get("runId").asString()).isEqualTo("20261019-143200-lab");
        assertThat(lastRun.get("profile").asString()).isEqualTo("lab");
        assertThat(Instant.parse(lastRun.get("at").asString())).isEqualTo(Instant.parse("2026-10-19T12:32:00Z"));
        assertThat(lastRun.get("passed").asInt()).isEqualTo(1);
        assertThat(lastRun.get("findings").asInt()).isEqualTo(2);
        assertThat(lastRun.get("setupProblems").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("GET requests change nothing on disk")
    void testGetHasNoSideEffects() throws Exception {
        assertThat(UiHttp.get(server, "/api/workspaces/recent").status()).isEqualTo(200);
        assertThat(UiHttp.get(server, "/api/folders").status()).isEqualTo(200);
        assertThat(recentFile()).doesNotExist();
    }

    // --- folders ------------------------------------------------------------------------------

    @Test
    @DisplayName("Folders: without a path the user's home; with one, its sub-folders")
    void testFolders() throws Exception {
        JsonNode homeListing = json(UiHttp.get(server, "/api/folders"));
        assertThat(homeListing.get("path").asString()).isEqualTo(home.toAbsolutePath().normalize().toString());

        Files.createDirectories(temp.resolve("ws/scenarios"));
        Files.createDirectories(temp.resolve("other"));
        JsonNode listing = json(UiHttp.get(server, "/api/folders?path="
                + URLEncoder.encode(temp.toString(), StandardCharsets.UTF_8)));
        assertThat(listing.get("path").asString()).isEqualTo(temp.toString());
        assertThat(listing.get("parent").asString()).isEqualTo(temp.getParent().toString());
        JsonNode entries = listing.get("entries");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).get("name").asString()).isEqualTo("ws");
        assertThat(entries.get(1).get("isWorkspace").asBoolean()).isTrue();
        assertThat(entries.get(0).get("isWorkspace").asBoolean()).isFalse();

        Response missing = UiHttp.get(server, "/api/folders?path="
                + URLEncoder.encode(temp.resolve("nope").toString(), StandardCharsets.UTF_8));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(json(missing).get("key").asString()).isEqualTo("ui.folder.notFolder");
    }

    @Test
    @DisplayName("Folders with spaces, non-ASCII names, a symlink loop and an unreadable folder answer 200")
    void testBrowseOddFolders() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Files.createDirectories(temp.resolve("with spaces"));
        Files.createDirectories(temp.resolve("niño 日本"));
        Files.createSymbolicLink(temp.resolve("loop"), temp.resolve("loop"));
        Path locked = Files.createDirectories(temp.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            Response listing = UiHttp.get(server, "/api/folders?path="
                    + URLEncoder.encode(temp.toString(), StandardCharsets.UTF_8));
            assertThat(listing.status()).isEqualTo(200);
            List<String> names = new ArrayList<>();
            json(listing).get("entries").forEach(e -> names.add(e.get("name").asString()));
            assertThat(names).contains("with spaces", "niño 日本").doesNotContain("loop");

            Response inside = UiHttp.get(server, "/api/folders?path="
                    + URLEncoder.encode(locked.toString(), StandardCharsets.UTF_8));
            assertThat(inside.status()).isEqualTo(200);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private static Response post(String path, Map<String, Object> body) throws IOException {
        return UiHttp.send(server, "POST", path, JSON.writeValueAsString(body));
    }

    private static JsonNode json(Response response) {
        return JSON.readTree(response.body());
    }

    private static List<String> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    private static void writeResults(Path ws, String runId, Instant at, String json) throws IOException {
        Path file = Files.createDirectories(ws.resolve("reports").resolve(runId)).resolve("results.json");
        Files.writeString(file, json);
        Files.setLastModifiedTime(file, FileTime.from(at));
    }

    private static String passed(String name) {
        return "{\"name\":\"" + name + "\",\"sourceName\":\"" + name + "\",\"success\":true,\"durationMillis\":1,"
                + "\"steps\":[{\"index\":0,\"type\":\"ldap.verify\",\"success\":true}],\"cleanup\":[]}";
    }

    private static String failed(String name, String kind) {
        return "{\"name\":\"" + name + "\",\"sourceName\":\"" + name + "\",\"success\":false,\"durationMillis\":1,"
                + "\"steps\":[{\"index\":0,\"type\":\"ldap.verify\",\"success\":false,\"failureKind\":\"" + kind
                + "\"}],\"cleanup\":[]}";
    }

    private static String notLoaded(String name) {
        return "{\"name\":\"" + name + "\",\"sourceName\":\"" + name + "\",\"success\":false,\"durationMillis\":0,"
                + "\"steps\":[],\"cleanup\":[],\"failureDetail\":\"invalid YAML\"}";
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
