package com.idm.test.runner.ui.api;

import com.idm.test.runner.InitCommand;
import com.idm.test.runner.ui.UiCommand;
import com.idm.test.runner.ui.UiHttp;
import com.idm.test.runner.ui.UiHttp.Response;
import com.idm.test.runner.ui.UiServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two tabs, one session: tab A shows workspace X, then tab B opens workspace Y. Every workspace-scoped
 * request of A says it is for X and is refused (409 {@code ui.workspace.switched}) instead of acting on
 * Y; a request for Y, or one that needs no workspace, goes through. The starter files of X and Y are the
 * same, so a save with X's version would otherwise succeed in Y.
 */
@DisplayName("Workbench workspace guard")
class WorkspaceGuardTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    static Path home;
    private static String realHome;
    private static UiServer server;

    @TempDir
    Path temp;
    private Path x;
    private Path y;

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

    /** Tab A opened X, then tab B opened Y. */
    @BeforeEach
    void twoTabs() throws IOException {
        x = temp.resolve("x");
        y = temp.resolve("y");
        InitCommand.run(x, new PrintStream(OutputStream.nullOutputStream()));
        InitCommand.run(y, new PrintStream(OutputStream.nullOutputStream()));
        open(x);
        open(y);
    }

    private static void open(Path workspace) throws IOException {
        Response opened = UiHttp.send(server, "POST", "/api/workspaces/open",
                JSON.writeValueAsString(Map.of("path", workspace.toString())));
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
    }

    /** A request of a tab showing {@code workspace}. */
    private static Response as(Path workspace, String method, String path, String json) throws IOException {
        return as(workspace, method, path, json, Map.of());
    }

    private static Response as(Path workspace, String method, String path, String json, Map<String, String> more)
            throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(more);
        headers.put(WorkspaceGuard.HEADER, UiHttp.encode(workspace));
        return UiHttp.send(server, method, path, json, headers);
    }

    /** Only the session cookie: no workspace named. */
    private static Response bare(String method, String path, String json) throws IOException {
        return UiHttp.request(method, path, "127.0.0.1:" + server.port(),
                Map.of("Cookie", UiHttp.cookie(server)), json, server.port());
    }

    private static void assertSwitched(Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(409);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("key").asString()).isEqualTo("ui.workspace.switched");
        assertThat(body.get("message").asString())
                .isEqualTo("This workspace is no longer the one open in the workbench (another tab opened y).");
    }

    /** Every file of {@code workspace} with its content (secrets.env and the profiles included). */
    private static String contents(Path workspace) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(workspace)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                all.append(workspace.relativize(file)).append('\n').append(Files.readString(file)).append('\n');
            }
        }
        return all.toString();
    }

    private static String save(String content, String version) throws IOException {
        return JSON.writeValueAsString(Map.of("content", content, "version", version));
    }

    @Test
    @DisplayName("Should refuse tab A's save into Y although the starter file and its version are the same")
    void testSaveRefused() throws Exception {
        String before = Files.readString(y.resolve("scenarios/example.yaml"));
        Response read = as(y, "GET", "/api/files?path=scenarios/example.yaml", null);
        String version = JSON.readTree(read.body()).get("version").asString();

        Response saved = as(x, "PUT", "/api/files?path=scenarios/example.yaml", save("name: from tab A\n", version));

        assertSwitched(saved);
        assertThat(Files.readString(y.resolve("scenarios/example.yaml"))).isEqualTo(before);
        assertThat(Files.readString(x.resolve("scenarios/example.yaml"))).isEqualTo(before);
    }

    @Test
    @DisplayName("Should refuse tab A's lab save, run, stop, history, tree, report and events for X")
    void testOtherEndpointsRefused() throws Exception {
        List<String> before = List.of(contents(x), contents(y));

        assertSwitched(as(x, "PUT", "/api/lab?profile=lab", JSON.writeValueAsString(Map.of("systemId", "ldap",
                "values", Map.of(), "secrets", Map.of("password", "fake-tab-a-password")))));
        assertSwitched(as(x, "PUT", "/api/lab/default-directory?profile=lab",
                JSON.writeValueAsString(Map.of("name", "edirectory"))));
        assertSwitched(as(x, "GET", "/api/lab?profile=lab", null));
        assertSwitched(as(x, "POST", "/api/lab/test", "{}"));
        assertSwitched(as(x, "POST", "/api/runs", JSON.writeValueAsString(Map.of("profile", "lab"))));
        assertSwitched(as(x, "POST", "/api/runs/current/stop", "{}"));
        assertSwitched(as(x, "GET", "/api/runs/history", null));
        assertSwitched(as(x, "GET", "/api/files/tree", null));
        assertSwitched(as(x, "GET", "/reports/20261019-101530-lab/report.html", null));
        // EventSource cannot set headers: the workspace is a query parameter
        assertSwitched(bare("GET", "/api/files/events?workspace=" + UiHttp.encode(x), null));
        assertSwitched(bare("GET", "/api/runs/current/events?workspace=" + UiHttp.encode(x), null));
        // as a browser's EventSource asks: the refusal is still the JSON error
        assertSwitched(UiHttp.request("GET", "/api/files/events?workspace=" + UiHttp.encode(x),
                "127.0.0.1:" + server.port(), Map.of("Cookie", UiHttp.cookie(server), "Accept", "text/event-stream"),
                null, server.port()));

        assertThat(List.of(contents(x), contents(y))).isEqualTo(before);
    }

    @Test
    @DisplayName("Should refuse a workspace-scoped request that names no workspace")
    void testMissingRefused() throws Exception {
        assertSwitched(bare("GET", "/api/files/tree", null));
        assertSwitched(bare("GET", "/api/files?path=scenarios/example.yaml", null));
        assertSwitched(bare("GET", "/api/lab?profile=lab", null));
        assertSwitched(bare("GET", "/api/files/events", null));
    }

    @Test
    @DisplayName("Should let through requests for the open workspace, also by another spelling of its path")
    void testMatchingAllowed() throws Exception {
        assertThat(as(y, "GET", "/api/files/tree", null).status()).isEqualTo(200);
        assertThat(as(y.resolve("scenarios/.."), "GET", "/api/lab?profile=lab", null).status()).isEqualTo(200);
        assertThat(as(y, "GET", "/api/runs/history", null).status()).isEqualTo(200);
        assertThat(bare("GET", "/api/files/tree?workspace=" + UiHttp.encode(y), null).status()).isEqualTo(200);
        assertThat(as(y, "GET", "/api/runs/current/events", null).status()).isEqualTo(204);
    }

    @Test
    @DisplayName("Should not ask the start page, health, schema or validation for a workspace")
    void testAgnosticEndpoints() throws Exception {
        assertThat(bare("GET", "/api/health", null).status()).isEqualTo(200);
        assertThat(bare("GET", "/api/workspaces/recent", null).status()).isEqualTo(200);
        assertThat(bare("GET", "/api/folders?path=" + UiHttp.encode(temp), null).status()).isEqualTo(200);
        assertThat(bare("GET", "/api/schema/scenario", null).status()).isEqualTo(200);
        assertThat(bare("POST", "/api/validate", JSON.writeValueAsString(
                Map.of("path", "scenarios/a.yaml", "content", "name: a\nsteps: []\n"))).status()).isEqualTo(200);
        assertThat(as(x, "POST", "/api/workspaces/open", JSON.writeValueAsString(Map.of("path", y.toString())))
                .status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Should say it in the request's language")
    void testSpanish() throws Exception {
        Response response = as(x, "GET", "/api/files/tree", null, Map.of("Accept-Language", "es"));

        assertThat(response.status()).isEqualTo(409);
        assertThat(JSON.readTree(response.body()).get("message").asString()).isEqualTo(
                "Este espacio de trabajo ya no es el que está abierto en el banco de trabajo (otra pestaña abrió y).");
    }
}
