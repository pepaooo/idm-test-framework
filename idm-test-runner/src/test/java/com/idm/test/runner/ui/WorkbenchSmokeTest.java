package com.idm.test.runner.ui;

import com.idm.test.runner.LabFixture;
import com.idm.test.runner.SecretsEnv;
import com.idm.test.runner.ui.RunEventStream.Sse;
import com.idm.test.runner.ui.UiHttp.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static com.idm.test.runner.LabFixture.BASE_DN;
import static com.idm.test.runner.LabFixture.BIND_DN;
import static com.idm.test.runner.LabFixture.LDAP_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first five minutes, end to end over HTTP against a started workbench, with the session token
 * only: open the token URL, create a workspace, save a scenario, fill in the lab for an embedded LDAP
 * directory, test the connection, run, follow the run's events to the end and open the report. No
 * response, header or event of the whole session may contain the directory's password. Every
 * credential here is fake.
 */
@DisplayName("Workbench smoke test")
class WorkbenchSmokeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** A wrong password typed into the form and tested; it must not come back either. */
    private static final String WRONG_PASSWORD = "fake-wrong-Smoke-81";
    private static final Pattern SCRIPT = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"");

    @TempDir
    static Path home;
    private static String realHome;
    private static UiServer server;
    private static LabFixture lab;

    @TempDir
    Path temp;

    /** Every status line, header and body the test received, and every event's data. */
    private final List<String> seen = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        lab = LabFixture.start();
        // No workspace (the start page), and no real environment variable is consulted.
        server = UiCommand.start(0, Locale.ENGLISH, null, Map.of());
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
        if (lab != null) {
            lab.close();
        }
        System.setProperty("user.home", realHome);
    }

    @Test
    @DisplayName("Create, save, connect, test, run and report through the API, with no password in anything it answers")
    void testFirstFiveMinutes() throws Exception {
        // The URL idmtest ui prints: the token becomes this port's cookie, and the app is served with it.
        Response tokenUrl = keep(UiHttp.request("GET", "/?token=" + server.token(), "127.0.0.1:" + server.port(),
                Map.of(), null, server.port()));
        assertThat(tokenUrl.status()).isEqualTo(302);
        assertThat(tokenUrl.header("Set-Cookie")).startsWith(UiHttp.cookie(server) + ";")
                .contains("HttpOnly", "SameSite=Strict");
        assertThat(keep(UiHttp.request("GET", "/api/health", "127.0.0.1:" + server.port(), Map.of(), null,
                server.port())).status()).as("no cookie, no token").isEqualTo(403);
        Response page = get("/");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("<div id=\"root\"></div>");
        Matcher script = SCRIPT.matcher(page.body());
        assertThat(script.find()).as(page.body()).isTrue();
        Response asset = get(script.group(1));
        assertThat(asset.status()).isEqualTo(200);
        assertThat(asset.header("Content-Type")).contains("javascript");
        assertThat(json(get("/api/health")).get("workspace").isNull()).isTrue();

        // Create a workspace (the code of idmtest init), then open it.
        Response created = send("POST", "/api/workspaces", Map.of("name", "smoke", "parent", temp.toString(),
                "includeExample", true, "gitInit", false));
        assertThat(created.status()).as(created.body()).isEqualTo(200);
        Path ws = Path.of(json(created).get("path").asString());
        Response opened = send("POST", "/api/workspaces/open", Map.of("path", ws.toString()));
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        assertThat(json(opened).get("profiles").toString()).isEqualTo("[\"lab\"]");
        assertThat(json(get("/api/workspaces/recent")).get(0).get("path").asString()).isEqualTo(ws.toString());

        // Save the example scenario with a shorter wait, after a live check of the new text.
        Response example = get("/api/files?path=scenarios/example.yaml");
        assertThat(example.status()).isEqualTo(200);
        String content = json(example).get("content").asString()
                .replace("wait: { timeout: 30s, poll: 2s }", "wait: { timeout: 10s, poll: 1s }");
        assertThat(content).contains("timeout: 10s");
        JsonNode problems = json(send("POST", "/api/validate", Map.of("path", "scenarios/example.yaml",
                "content", content))).get("problems");
        assertThat(problems).as(problems.toString()).isEmpty();
        Response saved = send("PUT", "/api/files?path=scenarios/example.yaml",
                Map.of("content", content, "version", json(example).get("version").asString()));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(Files.readString(ws.resolve("scenarios/example.yaml"))).isEqualTo(content);

        // The lab: the directory the example uses is missing until the form is filled in and saved.
        assertThat(system(lab("lab"), "ldap").get("status").asString()).isEqualTo("missing");
        JsonNode wrong = json(send("POST", "/api/lab/test", Map.of("systemId", "ldap", "profile", "lab",
                "values", Map.of("url", lab.ldap().getUrl(), "base-dn", BASE_DN, "user-dn", BIND_DN),
                "secrets", Map.of("password", WRONG_PASSWORD))));
        assertThat(wrong.get("ok").asBoolean()).isFalse();
        assertThat(wrong.get("message").asString()).startsWith("credentials rejected");
        Response labSaved = send("PUT", "/api/lab?profile=lab", Map.of("systemId", "ldap",
                "values", Map.of("url", lab.ldap().getUrl(), "base-dn", BASE_DN, "user-dn", BIND_DN),
                "secrets", Map.of("password", LDAP_PASSWORD)));
        assertThat(labSaved.status()).as(labSaved.body()).isEqualTo(200);
        JsonNode password = field(system(json(labSaved), "ldap"), "password");
        assertThat(password.get("value").isNull()).isTrue();
        assertThat(password.get("state").asString()).isEqualTo("set");
        assertThat(Files.readString(ws.resolve("config/application-lab.yml")))
                .contains("password: ${IDM_TEST_LDAP_PASSWORD}")
                .contains("url: " + lab.ldap().getUrl() + "   # replace with your directory");
        assertThat(SecretsEnv.read(ws.resolve("secrets.env"))).containsEntry("IDM_TEST_LDAP_PASSWORD", LDAP_PASSWORD);

        // Test connection with the saved values (the password left as dots): the directory is ready.
        JsonNode tested = json(send("POST", "/api/lab/test", Map.of("systemId", "ldap", "profile", "lab",
                "values", Map.of(), "secrets", Map.of())));
        assertThat(tested.get("ok").asBoolean()).as(tested.toString()).isTrue();
        assertThat(tested.get("message").asString()).isEqualTo("Connected · bind OK");
        assertThat(system(lab("lab"), "ldap").get("status").asString()).isEqualTo("ready");

        // Run the scenario and follow its events to the end.
        Response started = send("POST", "/api/runs", Map.of("profile", "lab",
                "scenarios", List.of("scenarios/example.yaml")));
        assertThat(started.status()).as(started.body()).isEqualTo(202);
        String runId = json(started).get("runId").asString();
        List<Sse> events = RunEventStream.toEnd(server);
        events.forEach(event -> seen.add(event.id() + " " + event.name() + " " + event.raw()));
        assertThat(RunEventStream.names(events)).startsWith("runStarted", "scenarioStarted")
                .contains("stepStarted", "stepFinished", "scenarioFinished").endsWith("runFinished");
        JsonNode finished = events.getLast().data();
        assertThat(finished.get("runId").asString()).isEqualTo(runId);
        assertThat(finished.get("passed").asInt()).as(RunEventStream.names(events).toString()).isEqualTo(1);
        assertThat(finished.get("findings").asInt()).isZero();
        assertThat(finished.get("setupProblems").asInt()).isZero();
        assertThat(finished.get("exitCode").asInt()).isZero();

        // The report, the history and the tree's badge, as the run panel and the file tree read them.
        Response report = get(finished.get("reportUrl").asString());
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.body()).contains("example.yaml");
        assertThat(report.header("Content-Security-Policy")).contains("sandbox");
        JsonNode history = json(get("/api/runs/history?scenario="
                + URLEncoder.encode("scenarios/example.yaml", StandardCharsets.UTF_8)));
        assertThat(history.get(0).get("runId").asString()).isEqualTo(runId);
        assertThat(history.get(0).get("result").asString()).isEqualTo("passed");
        assertThat(find(json(get("/api/files/tree")), "scenarios/example.yaml").get("lastResult").asString())
                .isEqualTo("passed");
        assertThat(get("/api/files?path=secrets.env").status()).isEqualTo(403);

        // No password in anything the workbench answered or streamed, nor in the run's files.
        String everything = String.join("\n", seen);
        assertThat(everything).contains("Connected · bind OK", runId);
        assertThat(everything).doesNotContain(LDAP_PASSWORD).doesNotContain(WRONG_PASSWORD);
        Path runFolder = ws.resolve("reports").resolve(runId);
        for (String file : List.of("run.log", "results.json", "report.html")) {
            assertThat(Files.readString(runFolder.resolve(file))).as(file)
                    .doesNotContain(LDAP_PASSWORD).doesNotContain(WRONG_PASSWORD);
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private Response keep(Response response) {
        seen.add(response.status() + " " + response.headers() + "\n" + response.body());
        return response;
    }

    private Response get(String path) throws IOException {
        return keep(UiHttp.get(server, path));
    }

    private Response send(String method, String path, Object body) throws IOException {
        return keep(UiHttp.send(server, method, path, JSON.writeValueAsString(body)));
    }

    private JsonNode lab(String profile) throws IOException {
        return json(get("/api/lab?profile=" + profile));
    }

    private static JsonNode json(Response response) {
        return JSON.readTree(response.body());
    }

    private static JsonNode system(JsonNode view, String id) {
        return StreamSupport.stream(view.get("systems").spliterator(), false)
                .filter(s -> s.get("id").asString().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no system " + id + " in " + view));
    }

    private static JsonNode field(JsonNode system, String key) {
        return StreamSupport.stream(system.get("fields").spliterator(), false)
                .filter(f -> f.get("key").asString().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no field " + key + " in " + system));
    }

    /** The tree node at {@code path}, searched depth first. */
    private static JsonNode find(JsonNode node, String path) {
        if (path.equals(node.get("path").asString())) {
            return node;
        }
        JsonNode children = node.get("children");
        if (children != null && !children.isNull()) {
            for (JsonNode child : children) {
                JsonNode found = find(child, path);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
