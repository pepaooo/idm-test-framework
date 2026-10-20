package com.idm.test.runner.ui.api;

import com.idm.test.runner.LabFixture;
import com.idm.test.runner.ui.RunEventStream;
import com.idm.test.runner.ui.UiCommand;
import com.idm.test.runner.ui.UiHttp;
import com.idm.test.runner.ui.UiHttp.Response;
import com.idm.test.runner.ui.UiServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.idm.test.runner.LabFixture.WRONG_SN;
import static com.idm.test.runner.LabFixture.corpusScenario;
import static com.idm.test.runner.LabFixture.scenario;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's logging settings stay the run's: a profile that turns on DEBUG logging for Spring MVC and
 * Tomcat must not make the workbench log its own requests — a password typed into the lab form, the
 * session token — to the console or into a run.log, during a run or after it. Every credential here is
 * fake.
 */
@DisplayName("Workbench logging around runs")
class RunsLoggingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROBE_AFTER_FIRST = "LogProbeAfter1st-6Tq";
    private static final String PROBE_DURING_SECOND = "LogProbeDuring2nd-8Wm";
    private static final String PROBE_AFTER_SECOND = "LogProbeAfter2nd-3Zr";

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

    /** Writes to both {@code first} and {@code second}. */
    private static PrintStream tee(PrintStream first, OutputStream second) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                first.write(b);
                second.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                first.write(b, off, len);
                second.write(b, off, len);
            }
        }, true, StandardCharsets.UTF_8);
    }

    private static Response saveDbPassword(String password) throws IOException {
        Response saved = UiHttp.send(server, "PUT", "/api/lab?profile=lab", JSON.writeValueAsString(
                Map.of("systemId", "database:test-db", "values", Map.of(), "secrets", Map.of("password", password))));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        return saved;
    }

    private static String startRun(String scenarioPath) throws IOException {
        Response started = UiHttp.send(server, "POST", "/api/runs",
                JSON.writeValueAsString(Map.of("profile", "lab", "scenarios", List.of(scenarioPath))));
        assertThat(started.status()).as(started.body()).isEqualTo(202);
        return JSON.readTree(started.body()).get("runId").asString();
    }

    @Test
    @DisplayName("Should keep a profile's DEBUG logging from logging the workbench's own requests, during and after runs")
    void testRunLoggingStaysTheRuns() throws Exception {
        Path ws = lab.workspace(temp.resolve("ws"), LabFixture.database(temp.resolve("db")), null);
        Path settings = ws.resolve("config/application-lab.yml");
        Files.writeString(settings, Files.readString(settings)
                .replace("default-timeout-seconds: 2", "default-timeout-seconds: 60")
                + """
                logging:
                  level:
                    org.springframework.web: TRACE
                    org.springframework.http: TRACE
                    org.apache.coyote: DEBUG
                    org.apache.tomcat: DEBUG
                    org.apache.catalina: DEBUG
                    com.idm.test.runner.ui: TRACE
                """);
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        scenario(ws, "wrong-sn.yaml", WRONG_SN);
        Response opened = UiHttp.send(server, "POST", "/api/workspaces/open",
                JSON.writeValueAsString(Map.of("path", ws.toString())));
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);

        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        String first;
        String second;
        try {
            System.setOut(tee(realOut, console));
            System.setErr(tee(realErr, console));

            first = startRun("scenarios/provisioning-user-to-ldap.yaml");
            assertThat(RunEventStream.toEnd(server).getLast().name()).isEqualTo("runFinished");
            saveDbPassword(PROBE_AFTER_FIRST);

            second = startRun("scenarios/wrong-sn.yaml");
            try (RunEventStream stream = new RunEventStream(server)) {
                stream.until("attempt");
                saveDbPassword(PROBE_DURING_SECOND);
                assertThat(UiHttp.send(server, "POST", "/api/runs/current/stop", null).status()).isEqualTo(202);
                assertThat(stream.all().getLast().name()).isEqualTo("runFinished");
            }
            saveDbPassword(PROBE_AFTER_SECOND);
            UiHttp.get(server, "/api/runs/history");
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }

        String out = console.toString(StandardCharsets.UTF_8);
        String firstLog = Files.readString(ws.resolve("reports").resolve(first).resolve("run.log"));
        String secondLog = Files.readString(ws.resolve("reports").resolve(second).resolve("run.log"));
        // the runs did log: these are the files the run's own settings fill
        assertThat(firstLog).contains("provisioning-user-to-ldap.yaml");
        assertThat(secondLog).contains("wrong-sn.yaml");
        // The workbench's own requests are not logged at all: not the lab saves, not the run requests,
        // not the event streams — neither by Spring MVC nor by Tomcat.
        for (String request : List.of("/api/lab", "/api/runs", "DispatcherServlet", "RequestResponseBodyMethodProcessor",
                "Http11")) {
            assertThat(out).as("console").doesNotContain(request);
            assertThat(firstLog).as("first run.log").doesNotContain(request);
            assertThat(secondLog).as("second run.log").doesNotContain(request);
        }
        for (String secret : List.of(PROBE_AFTER_FIRST, PROBE_DURING_SECOND, PROBE_AFTER_SECOND, server.token())) {
            assertThat(out).as("console").doesNotContain(secret);
            assertThat(firstLog).as("first run.log").doesNotContain(secret);
            assertThat(secondLog).as("second run.log").doesNotContain(secret);
        }
    }
}
