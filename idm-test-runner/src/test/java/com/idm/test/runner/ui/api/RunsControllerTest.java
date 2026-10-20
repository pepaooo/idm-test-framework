package com.idm.test.runner.ui.api;

import com.idm.test.runner.LabFixture;
import com.idm.test.runner.ui.RunEventStream;
import com.idm.test.runner.ui.RunEventStream.Sse;
import com.idm.test.runner.ui.UiCommand;
import com.idm.test.runner.ui.UiHttp;
import com.idm.test.runner.ui.UiHttp.Response;
import com.idm.test.runner.ui.UiServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.idm.test.runner.LabFixture.BAD_STEP;
import static com.idm.test.runner.LabFixture.DB_PASSWORD;
import static com.idm.test.runner.LabFixture.LDAP_PASSWORD;
import static com.idm.test.runner.LabFixture.REST_PASSWORD;
import static com.idm.test.runner.LabFixture.WRONG_SN;
import static com.idm.test.runner.LabFixture.corpusScenario;
import static com.idm.test.runner.LabFixture.scenario;
import static com.idm.test.runner.ui.RunEventStream.END;
import static com.idm.test.runner.ui.RunEventStream.names;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The runs API over HTTP, against a running workbench and the run tests' lab (embedded LDAP, file
 * H2, WireMock). One workbench for the class, so runs follow one another: each test reads its run's
 * events to the end of the stream, which comes after the run has given the slot back. Every
 * credential here is fake.
 */
@DisplayName("Workbench runs API")
class RunsControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TENANT_SECRET = "Zq9xRunsProbe";

    @TempDir
    static Path home;
    private static String realHome;
    private static UiServer server;
    private static LabFixture lab;

    @TempDir
    Path temp;

    @BeforeAll
    static void start() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        lab = LabFixture.start();
        lab.wireMock().stubFor(get(urlEqualTo("/tenant-" + TENANT_SECRET + "/users")).willReturn(okJson("[]")));
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

    // --- fixtures -----------------------------------------------------------------------------

    /** The lab's workspace, opened in the workbench. */
    private Path workspace() throws Exception {
        Path ws = lab.workspace(temp.resolve("ws"), LabFixture.database(temp.resolve("db")), null);
        Response opened = UiHttp.send(server, "POST", "/api/workspaces/open",
                JSON.writeValueAsString(Map.of("path", ws.toString())));
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        return ws;
    }

    /** Waits of 60 s, so a stop surely comes during one. */
    private static void slowWaits(Path ws) throws IOException {
        Path settings = ws.resolve("config/application-lab.yml");
        Files.writeString(settings, Files.readString(settings)
                .replace("default-timeout-seconds: 2", "default-timeout-seconds: 60"));
    }

    private static Response post(String path, Object body) throws IOException {
        return UiHttp.send(server, "POST", path, body == null ? null : JSON.writeValueAsString(body));
    }

    private static String startRun(Map<String, Object> body) throws IOException {
        Response started = post("/api/runs", body);
        assertThat(started.status()).as(started.body()).isEqualTo(202);
        JsonNode runId = JSON.readTree(started.body()).get("runId");
        return runId.isNull() ? null : runId.asString();
    }

    private static String key(Response response) {
        return JSON.readTree(response.body()).get("key").asString();
    }

    private static List<Sse> named(List<Sse> events, String name) {
        return events.stream().filter(e -> e.name().equals(name)).toList();
    }

    private static List<Sse> runToEnd() throws InterruptedException {
        return RunEventStream.toEnd(server);
    }

    private static void results(Path ws, String runId, Instant at, String json) throws IOException {
        Path file = Files.createDirectories(ws.resolve("reports").resolve(runId)).resolve("results.json");
        Files.writeString(file, json);
        Files.setLastModifiedTime(file, FileTime.from(at));
    }

    private static String passed(String name) {
        return "{\"name\":\"" + name + "\",\"sourceName\":\"" + name + "\",\"success\":true,\"steps\":[],\"cleanup\":[]}";
    }

    private static String finding(String name) {
        return "{\"name\":\"" + name + "\",\"sourceName\":\"" + name + "\",\"success\":false,\"steps\":["
                + "{\"index\":0,\"type\":\"ldap.verify\",\"success\":false,\"failureKind\":\"FINDING\"}],\"cleanup\":[]}";
    }

    private static String setup(String name) {
        return "{\"name\":\"" + name + "\",\"sourceName\":\"" + name + "\",\"success\":false,\"steps\":["
                + "{\"index\":0,\"type\":\"ldap.verify\",\"success\":false,\"failureKind\":\"SETUP\"}],\"cleanup\":[]}";
    }

    // --- tests --------------------------------------------------------------------------------

    @Test
    @DisplayName("Should stream a two-scenario run's events in order, replay them, and serve its report")
    void testEventOrderForTwoScenarios() throws Exception {
        Path ws = workspace();
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        scenario(ws, "wrong-sn.yaml", WRONG_SN);

        String runId = startRun(Map.of("profile", "lab", "scenarios", "all"));
        List<Sse> events = runToEnd();

        assertThat(runId).matches("\\d{8}-\\d{6}-lab(-\\d+)?");
        List<String> names = names(events);
        assertThat(names.getFirst()).isEqualTo("runStarted");
        assertThat(names.getLast()).isEqualTo("runFinished");
        assertThat(names).containsSubsequence("runStarted",
                "scenarioStarted", "stepStarted", "stepFinished", "scenarioFinished",
                "scenarioStarted", "stepStarted", "stepFinished", "stepStarted", "attempt", "stepFinished",
                "scenarioFinished", "runFinished");
        JsonNode started = events.getFirst().data();
        assertThat(started.get("runId").asString()).isEqualTo(runId);
        assertThat(started.get("locale").asString()).isEqualTo("en");
        assertThat(started.get("scenarios").toString()).isEqualTo("[\"provisioning-user-to-ldap.yaml\",\"wrong-sn.yaml\"]");
        assertThat(named(events, "scenarioStarted")).extracting(e -> e.data().get("name").asString())
                .containsExactly("provisioning-user-to-ldap.yaml", "wrong-sn.yaml");
        List<Sse> finished = named(events, "scenarioFinished");
        assertThat(finished.get(0).data().get("result").get("success").asBoolean()).isTrue();
        assertThat(finished.get(1).data().get("result").get("success").asBoolean()).isFalse();
        assertThat(finished.get(1).data().get("failureLine").asString()).isNotBlank();
        JsonNode step = named(events, "stepFinished").getFirst().data();
        assertThat(step.get("type").asString()).isEqualTo("ldap.create");
        assertThat(step.get("success").asBoolean()).isTrue();

        JsonNode outcome = events.getLast().data();
        assertThat(outcome.get("runId").asString()).isEqualTo(runId);
        assertThat(outcome.get("total").asInt()).isEqualTo(2);
        assertThat(outcome.get("passed").asInt()).isEqualTo(1);
        assertThat(outcome.get("findings").asInt()).isEqualTo(1);
        assertThat(outcome.get("setupProblems").asInt()).isZero();
        assertThat(outcome.get("stopped").asBoolean()).isFalse();
        assertThat(outcome.has("folder")).isFalse();
        assertThat(outcome.get("reportUrl").asString()).isEqualTo("/reports/" + runId + "/report.html");

        Response report = UiHttp.get(server, outcome.get("reportUrl").asString());
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.header("Content-Type")).startsWith("text/html");
        assertThat(report.body()).contains("wrong-sn.yaml", "provisioning-user-to-ldap.yaml");

        // A later subscriber gets the same run again, from its start.
        assertThat(names(runToEnd())).isEqualTo(names);
    }

    @Test
    @DisplayName("Should refuse a second run while one is active (409), and a stop when none is")
    void testSecondRunRefused() throws Exception {
        Path ws = workspace();
        slowWaits(ws);
        scenario(ws, "wrong-sn.yaml", WRONG_SN);

        Response noRun = post("/api/runs/current/stop", null);
        assertThat(noRun.status()).isEqualTo(409);
        assertThat(key(noRun)).isEqualTo("ui.run.none");

        String runId = startRun(Map.of("profile", "lab"));
        Response second = post("/api/runs", Map.of("profile", "lab"));
        assertThat(second.status()).isEqualTo(409);
        assertThat(key(second)).isEqualTo("ui.run.busy");
        assertThat(JSON.readTree(second.body()).get("message").asString()).isEqualTo(
                "a run is already going; wait for it to end or stop it");

        List<Sse> events;
        try (RunEventStream stream = new RunEventStream(server)) {
            stream.until("attempt");
            Response stopped = post("/api/runs/current/stop", null);
            assertThat(stopped.status()).as(stopped.body()).isEqualTo(202);
            assertThat(JSON.readTree(stopped.body()).get("runId").asString()).isEqualTo(runId);
            events = stream.all();
        }
        assertThat(events.getLast().name()).isEqualTo("runFinished");
        assertThat(events.getLast().data().get("stopped").asBoolean()).isTrue();
        assertThat(Files.readString(ws.resolve("reports").resolve(runId).resolve("results.json")))
                .contains("wrong-sn.yaml");
    }

    @Test
    @DisplayName("Should stop during a wait: the stopped step, the skipped cleanup with its values, runFinished.stopped")
    void testStopDuringWait() throws Exception {
        Path ws = workspace();
        slowWaits(ws);
        scenario(ws, "a-wrong-sn.yaml", WRONG_SN);
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");

        startRun(Map.of("profile", "lab", "scenarios", "all"));
        List<Sse> events;
        try (RunEventStream stream = new RunEventStream(server)) {
            stream.until("attempt");
            Response stop = post("/api/runs/current/stop", null);
            assertThat(stop.status()).as(stop.body()).isEqualTo(202);
            events = stream.all();
        }

        JsonNode verify = named(events, "stepFinished").stream().map(Sse::data)
                .filter(d -> d.get("type").asString().equals("ldap.verify")).findFirst().orElseThrow();
        assertThat(verify.get("success").asBoolean()).isFalse();
        assertThat(verify.get("messageKey").asString()).isEqualTo("engine.run.stopped");
        assertThat(verify.get("message").asString()).isEqualTo("Stopped — cleanup did not run");
        JsonNode result = named(events, "scenarioFinished").getFirst().data().get("result");
        assertThat(result.get("sourceName").asString()).isEqualTo("a-wrong-sn.yaml");
        assertThat(result.get("stopped").asBoolean()).isTrue();
        JsonNode skipped = result.get("skippedCleanup");
        assertThat(skipped).hasSize(1);
        assertThat(skipped.get(0).get("type").asString()).isEqualTo("ldap.delete");
        assertThat(skipped.get(0).get("resolvedParams").get("dn").asString()).startsWith("cn=").endsWith(",ou=users");
        assertThat(named(events, "scenarioStarted")).extracting(e -> e.data().get("name").asString())
                .containsExactly("a-wrong-sn.yaml");
        JsonNode outcome = events.getLast().data();
        assertThat(events.getLast().name()).isEqualTo("runFinished");
        assertThat(outcome.get("stopped").asBoolean()).isTrue();
        assertThat(outcome.get("total").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should keep a secrets.env value and a password saved in the lab form out of every event")
    void testEventsMasked() throws Exception {
        Path ws = workspace();
        Path secrets = ws.resolve("secrets.env");
        // The directory's password comes only from the lab form.
        Files.writeString(secrets, Files.readString(secrets).replaceAll("(?m)^IDM_TEST_LDAP_PASSWORD=.*\\n", "")
                + "IDM_TEST_TENANT_KEY=" + TENANT_SECRET + "\n");
        assertThat(Files.readString(secrets)).doesNotContain(LDAP_PASSWORD);
        lab.tenantTarget(ws, "IDM_TEST_TENANT_KEY");
        Response saved = UiHttp.send(server, "PUT", "/api/lab?profile=lab", JSON.writeValueAsString(
                Map.of("systemId", "ldap", "values", Map.of(), "secrets", Map.of("password", LDAP_PASSWORD))));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        scenario(ws, "tenant-missing.yaml", """
                name: Tenant misses a path
                steps:
                  - rest.verify:
                      target: tenant
                      path: /missing
                      wait: true
                      expect:
                        status: 200
                """);
        scenario(ws, "tenant.yaml", """
                name: Tenant answers
                steps:
                  - rest.verify:
                      target: tenant
                      path: /users
                      expect:
                        status: 200
                """);

        startRun(Map.of("profile", "lab", "scenarios", "all"));
        List<Sse> events = runToEnd();

        JsonNode outcome = events.getLast().data();
        // The directory scenario passed: the run used the password saved through the lab form.
        assertThat(outcome.get("passed").asInt()).as(names(events).toString()).isEqualTo(2);
        assertThat(outcome.get("findings").asInt()).isEqualTo(1);
        assertThat(names(events)).contains("attempt");
        String all = events.stream().map(e -> e.name() + " " + e.raw()).collect(Collectors.joining("\n"));
        assertThat(all).contains("tenant-****");
        for (String secret : List.of(TENANT_SECRET, LDAP_PASSWORD, REST_PASSWORD, DB_PASSWORD)) {
            assertThat(all).doesNotContain(secret);
        }
    }

    @Test
    @DisplayName("Should keep a run going and its results whole when the browser goes away mid-run")
    void testSubscriberGoneKeepsRun() throws Exception {
        Path ws = workspace();
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        scenario(ws, "wrong-sn.yaml", WRONG_SN);

        String runId = startRun(Map.of("profile", "lab"));
        try (RunEventStream stream = new RunEventStream(server)) {
            stream.until("scenarioStarted");
        }
        List<Sse> events = runToEnd();

        assertThat(events.getLast().name()).isEqualTo("runFinished");
        assertThat(Files.readString(ws.resolve("reports").resolve(runId).resolve("results.json")))
                .contains("provisioning-user-to-ldap.yaml", "wrong-sn.yaml");
    }

    @Test
    @DisplayName("Should run only the scenarios that found something, or a folder's, and refuse bad selections")
    void testSelections() throws Exception {
        Path ws = workspace();
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        scenario(ws, "wrong-sn.yaml", WRONG_SN);
        scenario(ws, "sub/bad.yaml", BAD_STEP);
        results(ws, "20261001-090000-lab", Instant.parse("2026-10-01T09:00:00Z"),
                "[" + passed("provisioning-user-to-ldap.yaml") + "," + finding("wrong-sn.yaml") + "]");

        startRun(Map.of("profile", "lab", "scenarios", "all", "onlyFindings", true));
        List<Sse> findings = runToEnd();
        assertThat(findings.getFirst().data().get("scenarios").toString()).isEqualTo("[\"wrong-sn.yaml\"]");

        startRun(Map.of("profile", "lab", "scenarios", Map.of("folder", "scenarios/sub"), "locale", "es"));
        List<Sse> folder = runToEnd();
        assertThat(folder.getFirst().data().get("scenarios").toString()).isEqualTo("[\"sub/bad.yaml\"]");
        assertThat(folder.getFirst().data().get("locale").asString()).isEqualTo("es");

        startRun(Map.of("profile", "lab", "scenarios", List.of("scenarios/wrong-sn.yaml", "scenarios/sub/bad.yaml")));
        assertThat(runToEnd().getFirst().data().get("scenarios").toString())
                .isEqualTo("[\"wrong-sn.yaml\",\"sub/bad.yaml\"]");

        Response traversal = post("/api/runs", Map.of("profile", "lab", "scenarios", List.of("scenarios/../secrets.env")));
        assertThat(traversal.status()).isEqualTo(400);
        assertThat(key(traversal)).isEqualTo("ui.file.badPath");
        Response outside = post("/api/runs", Map.of("profile", "lab", "scenarios", List.of("config/application-lab.yml")));
        assertThat(outside.status()).isEqualTo(400);
        assertThat(key(outside)).isEqualTo("ui.file.badPath");
        Response folderTraversal = post("/api/runs", Map.of("profile", "lab", "scenarios", Map.of("folder", "scenarios/..")));
        assertThat(key(folderTraversal)).isEqualTo("ui.file.badPath");
        Response missing = post("/api/runs", Map.of("profile", "lab", "scenarios", List.of("scenarios/missing.yaml")));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(key(missing)).isEqualTo("ui.run.noSuchScenario");
        Response empty = post("/api/runs", Map.of("profile", "lab", "scenarios", Map.of("folder", "scenarios/none")));
        assertThat(empty.status()).isEqualTo(400);
        assertThat(key(empty)).isEqualTo("ui.run.noScenarios");
        // its last result, in the results file written above, passed
        Response noFindings = post("/api/runs", Map.of("profile", "lab",
                "scenarios", List.of("scenarios/provisioning-user-to-ldap.yaml"), "onlyFindings", true));
        assertThat(noFindings.status()).isEqualTo(400);
        assertThat(key(noFindings)).isEqualTo("ui.run.noFindings");
        Response language = post("/api/runs", Map.of("profile", "lab", "locale", "fr"));
        assertThat(key(language)).isEqualTo("ui.run.badLocale");
        Response shape = post("/api/runs", Map.of("profile", "lab", "scenarios", 5));
        assertThat(key(shape)).isEqualTo("ui.request.invalid");
        Response profile = post("/api/runs", Map.of("profile", "../lab"));
        assertThat(key(profile)).isEqualTo("ui.lab.badProfile");
    }

    @Test
    @DisplayName("Should list the newest 10 runs first, of the workspace or of one scenario")
    void testHistoryNewestFirst() throws Exception {
        Path ws = workspace();
        Instant base = Instant.parse("2026-09-01T08:00:00Z");
        for (int i = 0; i < 10; i++) {
            results(ws, "202609%02d-080000-lab".formatted(i + 1), base.plus(Duration.ofDays(i)), "[" + passed("y.yaml") + "]");
        }
        results(ws, "20261001-090000-lab", Instant.parse("2026-10-01T09:00:00Z"), "[" + passed("x.yaml") + "]");
        results(ws, "20261002-090000-lab", Instant.parse("2026-10-02T09:00:00Z"),
                "[" + finding("x.yaml") + "," + passed("y.yaml") + "]");
        results(ws, "20261003-090000-uat", Instant.parse("2026-10-03T09:00:00Z"), "[" + setup("y.yaml") + "]");

        Response all = UiHttp.get(server, "/api/runs/history");
        assertThat(all.status()).as(all.body()).isEqualTo(200);
        JsonNode runs = JSON.readTree(all.body());
        assertThat(runs).hasSize(10);
        assertThat(runs.get(0).get("runId").asString()).isEqualTo("20261003-090000-uat");
        assertThat(runs.get(0).get("profile").asString()).isEqualTo("uat");
        assertThat(runs.get(0).get("result").asString()).isEqualTo("setup");
        assertThat(runs.get(0).get("at").asString()).isEqualTo("2026-10-03T09:00:00Z");
        assertThat(runs.get(1).get("result").asString()).isEqualTo("finding");
        assertThat(runs.get(2).get("runId").asString()).isEqualTo("20261001-090000-lab");
        assertThat(runs.get(2).get("result").asString()).isEqualTo("passed");
        assertThat(runs.get(9).get("runId").asString()).isEqualTo("20260904-080000-lab");

        JsonNode x = JSON.readTree(UiHttp.get(server, "/api/runs/history?scenario=scenarios/x.yaml").body());
        assertThat(x).hasSize(2);
        assertThat(x.get(0).get("runId").asString()).isEqualTo("20261002-090000-lab");
        assertThat(x.get(0).get("result").asString()).isEqualTo("finding");
        assertThat(x.get(1).get("result").asString()).isEqualTo("passed");

        Response bad = UiHttp.get(server, "/api/runs/history?scenario=../secrets.env");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(key(bad)).isEqualTo("ui.file.badPath");
    }

    @Test
    @DisplayName("Should serve a report only for an existing run id inside reports/, behind the token")
    void testReportOnlyForRuns() throws Exception {
        Path ws = workspace();
        Path reports = Files.createDirectories(ws.resolve("reports"));
        Files.writeString(Files.createDirectories(reports.resolve("20261019-101530-lab")).resolve("report.html"),
                "<html><body>report one</body></html>");
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("report.html"), "<html>outside</html>");
        Files.createSymbolicLink(reports.resolve("20261019-101532-lab"), outside);
        Path linked = Files.createDirectories(reports.resolve("20261019-101533-lab"));
        Files.createSymbolicLink(linked.resolve("report.html"), ws.resolve("secrets.env"));

        Response report = UiHttp.get(server, "/reports/20261019-101530-lab/report.html");
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.header("Content-Type")).startsWith("text/html");
        assertThat(report.header("Content-Security-Policy")).contains("sandbox");
        assertThat(report.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(report.body()).contains("report one");

        for (String path : List.of("/reports/20261019-101531-lab/report.html", "/reports/not-a-run/report.html",
                "/reports/20261019-101532-lab/report.html", "/reports/20261019-101533-lab/report.html",
                "/reports/20261019-101530-..lab/report.html")) {
            Response refused = UiHttp.get(server, path);
            assertThat(refused.status()).as(path).isEqualTo(404);
            assertThat(refused.body()).as(path).doesNotContain("outside", REST_PASSWORD);
        }
        for (String path : List.of("/reports/..%2F..%2Fsecrets.env/report.html", "/reports/%2e%2e/report.html",
                "/reports/20261019-101530-lab%2F..%2F20261019-101532-lab/report.html")) {
            Response refused = UiHttp.get(server, path);
            assertThat(refused.status()).as(path).isIn(400, 404);
            assertThat(refused.body()).as(path).doesNotContain("outside", REST_PASSWORD);
        }

        Response noToken = UiHttp.request("GET", "/reports/20261019-101530-lab/report.html",
                "127.0.0.1:" + server.port(), Map.of(), null, server.port());
        assertThat(noToken.status()).isEqualTo(403);
        assertThat(noToken.body()).doesNotContain("report one");
    }

    @Test
    @DisplayName("Should resume after Last-Event-ID, and answer 204 when the run ended and nothing is newer")
    void testResumeFromLastEventId() throws Exception {
        Path ws = workspace();
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");

        startRun(Map.of("profile", "lab"));
        List<Sse> events = runToEnd();
        assertThat(events).hasSizeGreaterThan(4);
        assertThat(events).extracting(Sse::id).doesNotContainNull().doesNotHaveDuplicates();

        try (RunEventStream resumed = new RunEventStream(server, events.get(2).id())) {
            assertThat(resumed.all()).extracting(Sse::id, Sse::name).containsExactlyElementsOf(
                    events.subList(3, events.size()).stream()
                            .map(e -> tuple(e.id(), e.name())).toList());
        }
        try (RunEventStream done = new RunEventStream(server, events.getLast().id())) {
            assertThat(done.status()).isEqualTo(204);
            assertThat(done.all()).isEmpty();
        }
        // an id of another run, or none we wrote: the run from its start
        // the same run and event numbers from an earlier workbench process: not this run's events
        String id = events.get(2).id();
        assertThat(id).matches("[0-9a-f]{16}\\.\\d+:2");
        String otherProcess = (id.startsWith("0") ? "1" : "0") + id.substring(1);
        for (String other : List.of(otherProcess, id.substring(id.indexOf('.') + 1), "999999:3", "garbage")) {
            try (RunEventStream fresh = new RunEventStream(server, other)) {
                assertThat(names(fresh.all())).as(other).isEqualTo(names(events));
            }
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("Should answer 404, not 500, for a report it cannot read")
    void testUnreadableReportIs404() throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")),
                "root reads any file");
        Path ws = workspace();
        Path folder = Files.createDirectories(ws.resolve("reports").resolve("20261019-101534-lab"));
        Files.writeString(folder.resolve("report.html"), "<html>locked</html>");
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("---------"));
        try {
            Response locked = UiHttp.get(server, "/reports/20261019-101534-lab/report.html");
            assertThat(locked.status()).as(locked.body()).isEqualTo(404);
            assertThat(key(locked)).isEqualTo("ui.request.notFound");
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @DisplayName("Should keep GETs free of side effects: history and events write nothing")
    void testGetsWriteNothing() throws Exception {
        Path ws = workspace();
        List<String> before;
        try (var files = Files.walk(ws)) {
            before = files.map(Path::toString).sorted().toList();
        }
        UiHttp.get(server, "/api/runs/history");
        try (RunEventStream stream = new RunEventStream(server)) {
            // the previous test's run, or 204: either way nothing starts
            stream.next(END);
        }
        List<String> after;
        try (var files = Files.walk(ws)) {
            after = files.map(Path::toString).sorted().toList();
        }
        assertThat(after).isEqualTo(before);
        // and no run was started by them
        Response stop = post("/api/runs/current/stop", null);
        assertThat(stop.status()).isEqualTo(409);
    }
}
