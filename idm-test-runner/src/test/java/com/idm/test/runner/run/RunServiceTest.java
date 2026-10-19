package com.idm.test.runner.run;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.idm.test.engine.result.CleanupResult;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.Message;
import com.idm.test.runner.ExitCodes;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run service end to end, against the same embedded LDAP directory, file H2 database and
 * WireMock as {@code RunCommandTest}: what a listener hears, in order, and that it hears no secret.
 */
@DisplayName("Run service")
class RunServiceTest {

    private static final String BASE_DN = "dc=test,dc=com";
    private static final String BIND_DN = "cn=admin,dc=test,dc=com";
    private static final String LDAP_PASSWORD = "ldap-pw-7Hq2xV";
    private static final String REST_PASSWORD = "rest-pw-9Zk4rT";
    private static final String DB_PASSWORD = "db-pw-3Lm8Qe";
    private static final String TENANT_SECRET = "Zq9xEventProbe";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T10:15:30Z"), ZoneOffset.UTC);
    private static final String RUN_ID = "20261016-101530-lab";

    private static final String WRONG_SN = """
            name: Wrong surname
            fixtures:
              user:
                generate: random-user
            steps:
              - ldap.create:
                  dn: "cn=${user.userId},ou=users"
                  as: createdDn
                  attributes:
                    cn: "${user.userId}"
                    sn: "${user.lastName}"
              - ldap.verify:
                  dn: "cn=${user.userId},ou=users"
                  wait: true
                  expect:
                    sn: "Not-${user.lastName}"
            cleanup:
              - ldap.delete:
                  dn: "${createdDn}"
            """;

    private static final String BAD_STEP = """
            name: Bad step
            steps:
              - no.such.step:
                  dn: x
            """;

    private static EmbeddedLdapServer ldap;
    private static WireMockServer wireMock;

    @TempDir
    Path temp;

    @BeforeAll
    static void startSystems() throws Exception {
        ldap = EmbeddedLdapServer.start(BASE_DN, BIND_DN, LDAP_PASSWORD, 0, "ldap-init.ldif");
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(get(urlEqualTo("/tenant-" + TENANT_SECRET + "/users")).willReturn(okJson("[]")));
    }

    @AfterAll
    static void stopSystems() {
        if (wireMock != null) {
            wireMock.stop();
        }
        if (ldap != null) {
            ldap.close();
        }
    }

    // --- fixtures -----------------------------------------------------------------------------

    private Path workspace() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("ws"));
        Files.createDirectories(ws.resolve("scenarios"));
        Files.createDirectories(ws.resolve("config"));
        Files.writeString(ws.resolve("config/application-lab.yml"), """
                idm:
                  test:
                    ldap:
                      url: %s
                      base-dn: %s
                      user-dn: %s
                      password: ${IDM_TEST_LDAP_PASSWORD}
                    databases:
                      test-db:
                        jdbc-url: %s
                        username: sa
                        password: ${IDM_TEST_DB_PASSWORD}
                    rest:
                      idm:
                        base-url: %s
                        username: runner
                        password: ${IDM_TEST_REST_PASSWORD}
                      tenant:
                        base-url: %s/tenant-${IDM_TEST_TENANT_KEY}
                    executor:
                      default-timeout-seconds: 2
                      default-poll-interval-seconds: 1
                """.formatted(ldap.getUrl(), BASE_DN, BIND_DN, database(), wireMock.baseUrl(), wireMock.baseUrl()));
        Files.writeString(ws.resolve("secrets.env"), "IDM_TEST_LDAP_PASSWORD=" + LDAP_PASSWORD
                + "\nIDM_TEST_REST_PASSWORD=" + REST_PASSWORD + "\nIDM_TEST_DB_PASSWORD=" + DB_PASSWORD
                + "\nIDM_TEST_TENANT_KEY=" + TENANT_SECRET + "\n");
        return ws;
    }

    private String database() throws Exception {
        Path dir = Files.createDirectories(temp.resolve("db"));
        String url = "jdbc:h2:file:" + dir.resolve("lab").toAbsolutePath().toString().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(url, "sa", DB_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS users (id INT PRIMARY KEY)");
        }
        return url;
    }

    private static void scenario(Path ws, String name, String content) throws Exception {
        Files.writeString(ws.resolve("scenarios").resolve(name), content);
    }

    private static void corpusScenario(Path ws, String name) throws Exception {
        try (InputStream in = RunServiceTest.class.getResourceAsStream("/scenarios/" + name)) {
            assertThat(in).isNotNull();
            Files.copy(in, ws.resolve("scenarios").resolve(name));
        }
    }

    private static RunRequest request(Path ws) {
        return new RunRequest(ws, "lab", List.of(), Locale.ENGLISH, Map.of());
    }

    /** Every event as one line, and every text it carried, for the secret check. */
    private static final class Recorder implements RunEvents {

        final List<String> events = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<ScenarioResult> finished = new ArrayList<>();
        RunOutcome outcome;
        Runnable onAttempt = () -> { };

        @Override
        public void runStarted(String runId, List<String> scenarios) {
            events.add("runStarted " + runId + " " + scenarios);
        }

        @Override
        public void scenarioStarted(String name) {
            events.add("scenarioStarted " + name);
        }

        @Override
        public void stepStarted(int index, String type, String description) {
            events.add("stepStarted " + index + " " + type);
            texts.add(description);
        }

        @Override
        public void attempt(int index, int attempt, long elapsedMillis, String message) {
            events.add("attempt " + index + " " + attempt);
            texts.add(message);
            onAttempt.run();
        }

        @Override
        public void stepFinished(StepResult result) {
            events.add("stepFinished " + result.index() + " " + result.type());
            texts.add(result.toString());
        }

        @Override
        public void cleanupFinished(CleanupResult result) {
            events.add("cleanupFinished " + result.index() + " " + result.type());
            texts.add(result.toString());
        }

        @Override
        public void scenarioFinished(ScenarioResult result) {
            events.add("scenarioFinished " + result.sourceName() + " " + result.success());
            texts.add(result.toString());
            finished.add(result);
        }

        @Override
        public void runFinished(RunOutcome outcome) {
            events.add("runFinished");
            this.outcome = outcome;
        }

        @Override
        public void refused(Message reason) {
            events.add("refused " + reason.key());
            texts.add(reason.toString());
        }
    }

    // --- tests --------------------------------------------------------------------------------

    @Test
    @DisplayName("Should tell a listener the run, each scenario and step in order, and the counts at the end")
    void testEventsInOrder() throws Exception {
        Path ws = workspace();
        scenario(ws, "a-bad.yaml", BAD_STEP);
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        scenario(ws, "wrong-sn.yaml", WRONG_SN);
        Recorder recorder = new Recorder();

        RunOutcome outcome = new RunService(CLOCK, false).run(request(ws), recorder, () -> false);

        assertThat(outcome.exitCode()).isEqualTo(ExitCodes.FAILED);
        assertThat(outcome.runId()).isEqualTo(RUN_ID);
        assertThat(outcome.folder()).isEqualTo(ws.toAbsolutePath().normalize().resolve("reports").resolve(RUN_ID));
        assertThat(outcome.total()).isEqualTo(3);
        assertThat(outcome.passed()).isEqualTo(1);
        assertThat(outcome.findings()).isEqualTo(1);
        assertThat(outcome.setupProblems()).isEqualTo(1);
        assertThat(outcome.stopped()).isFalse();
        assertThat(recorder.outcome).isEqualTo(outcome);
        assertThat(outcome.folder().resolve("results.json")).isRegularFile();

        List<String> events = recorder.events;
        assertThat(events.getFirst())
                .isEqualTo("runStarted " + RUN_ID + " [a-bad.yaml, provisioning-user-to-ldap.yaml, wrong-sn.yaml]");
        assertThat(events.getLast()).isEqualTo("runFinished");
        // A scenario that does not load never starts: it only finishes.
        assertThat(events.get(1)).isEqualTo("scenarioFinished a-bad.yaml false");
        assertThat(events).doesNotContain("scenarioStarted a-bad.yaml");
        assertThat(events).containsSubsequence(
                "scenarioStarted provisioning-user-to-ldap.yaml", "stepStarted 0 ldap.create",
                "stepFinished 0 ldap.create", "scenarioFinished provisioning-user-to-ldap.yaml true",
                "scenarioStarted wrong-sn.yaml", "stepStarted 0 ldap.create", "stepFinished 0 ldap.create",
                "stepStarted 1 ldap.verify", "attempt 1 1", "stepFinished 1 ldap.verify",
                "cleanupFinished 0 ldap.delete", "scenarioFinished wrong-sn.yaml false", "runFinished");
        assertThat(events).noneMatch(event -> event.startsWith("refused"));
        assertNoSecret(recorder);
    }

    @Test
    @DisplayName("Should deliver a guard refusal as refused, with no scenario events")
    void testGuardRefusal() throws Exception {
        Path ws = workspace();
        Files.writeString(ws.resolve("config/application-lab.yml"), """
                idm:
                  test:
                    ldap:
                      base-dn: dc=test,dc=com
                """);
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        Recorder recorder = new Recorder();

        RunOutcome outcome = new RunService(CLOCK, false).run(request(ws), recorder, () -> false);

        assertThat(outcome.exitCode()).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(outcome.total()).isZero();
        assertThat(recorder.events).hasSize(1);
        assertThat(recorder.events.getFirst()).startsWith("refused ");
        assertThat(recorder.outcome).isNull();
        assertNoSecret(recorder);
    }

    @Test
    @DisplayName("Should stop during a wait: the scenario is stopped and its cleanup is reported as skipped")
    void testStopDuringWait() throws Exception {
        Path ws = workspace();
        // First in order, so the stop comes during its wait.
        scenario(ws, "a-wrong-sn.yaml", WRONG_SN);
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        Recorder recorder = new Recorder();
        AtomicBoolean stop = new AtomicBoolean();
        recorder.onAttempt = () -> stop.set(true);

        RunOutcome outcome = new RunService(CLOCK, false).run(request(ws), recorder, stop::get);

        assertThat(outcome.stopped()).isTrue();
        assertThat(outcome.exitCode()).isEqualTo(ExitCodes.FAILED);
        assertThat(outcome.total()).isEqualTo(2);
        assertThat(outcome.passed()).isZero();
        assertThat(outcome.findings()).isZero();
        assertThat(outcome.setupProblems()).isEqualTo(1);
        assertThat(recorder.finished).hasSize(1);
        ScenarioResult stopped = recorder.finished.getFirst();
        assertThat(stopped.sourceName()).isEqualTo("a-wrong-sn.yaml");
        assertThat(stopped.stopped()).isTrue();
        assertThat(stopped.skippedCleanup()).extracting(CleanupResult::type).containsExactly("ldap.delete");
        assertThat(recorder.events).doesNotContain("cleanupFinished 0 ldap.delete")
                .doesNotContain("scenarioStarted provisioning-user-to-ldap.yaml");
        assertThat(recorder.events.getLast()).isEqualTo("runFinished");
    }

    @Test
    @DisplayName("Should mask every secrets.env value in what a listener hears, waiting history included")
    void testEventsAreMasked() throws Exception {
        Path ws = workspace();
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
        Recorder recorder = new Recorder();

        RunOutcome outcome = new RunService(CLOCK, false).run(request(ws), recorder, () -> false);

        assertThat(outcome.passed()).as(recorder.events.toString()).isEqualTo(1);
        assertThat(outcome.findings()).isEqualTo(1);
        assertThat(recorder.events).contains("attempt 0 1");
        assertThat(String.join("\n", recorder.texts.stream().map(String::valueOf).toList())).contains("tenant-****");
        assertNoSecret(recorder);
    }

    private static void assertNoSecret(Recorder recorder) {
        String all = String.join("\n", recorder.texts.stream().map(String::valueOf).toList())
                + String.join("\n", recorder.events);
        for (String secret : List.of(LDAP_PASSWORD, REST_PASSWORD, DB_PASSWORD, TENANT_SECRET)) {
            assertThat(all).doesNotContain(secret);
        }
    }
}
