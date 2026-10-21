package com.idm.test.runner.ui;

import com.idm.test.runner.ui.UiHttp.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Creating a workspace when {@code git init} cannot run: a workbench whose git is a missing program. */
@DisplayName("Workbench create when git init fails")
class WorkspacesGitFailureTest {

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
        server = UiCommand.start(0, Locale.ENGLISH, null, () -> {
        }, new GitTool("idmtest-no-such-git-program"));
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
        System.setProperty("user.home", realHome);
    }

    @Test
    @DisplayName("The workspace is created, the answer is 200 with a ui.workspace.gitFailed warning")
    void testGitFailureWarns() throws Exception {
        assertThat(JSON.readTree(UiHttp.get(server, "/api/health").body()).get("git").asBoolean()).isFalse();

        Response created = UiHttp.send(server, "POST", "/api/workspaces", JSON.writeValueAsString(Map.of(
                "name", "no-git", "parent", temp.toString(), "includeExample", true, "gitInit", true)));

        assertThat(created.status()).as(created.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(created.body());
        assertThat(body.get("path").asString()).isEqualTo(temp.resolve("no-git").toString());
        assertThat(body.get("warning").get("key").asString()).isEqualTo("ui.workspace.gitFailed");
        assertThat(body.get("warning").get("message").asString()).contains("git init");
        assertThat(temp.resolve("no-git/scenarios/example.yaml")).exists();
        assertThat(temp.resolve("no-git/.git")).doesNotExist();
    }
}
