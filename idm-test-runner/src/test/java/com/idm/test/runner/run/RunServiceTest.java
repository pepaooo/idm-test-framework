package com.idm.test.runner.run;

import com.idm.test.engine.result.CleanupResult;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.Message;
import com.idm.test.runner.ExitCodes;
import com.idm.test.runner.LabFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import static com.idm.test.runner.LabFixture.BAD_STEP;
import static com.idm.test.runner.LabFixture.DB_PASSWORD;
import static com.idm.test.runner.LabFixture.LDAP_PASSWORD;
import static com.idm.test.runner.LabFixture.REST_PASSWORD;
import static com.idm.test.runner.LabFixture.WRONG_SN;
import static com.idm.test.runner.LabFixture.corpusScenario;
import static com.idm.test.runner.LabFixture.scenario;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run service end to end, against the same embedded LDAP directory, file H2 database and
 * WireMock as {@code RunCommandTest}: what a listener hears, in order, and that it hears no secret.
 */
@DisplayName("Run service")
class RunServiceTest {

    private static final String TENANT_SECRET = "Zq9xEventProbe";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T10:15:30Z"), ZoneOffset.UTC);
    private static final String RUN_ID = "20261016-101530-lab";

    private static LabFixture lab;

    @TempDir
    Path temp;

    @BeforeAll
    static void startSystems() throws Exception {
        lab = LabFixture.start();
        lab.wireMock().stubFor(get(urlEqualTo("/tenant-" + TENANT_SECRET + "/users")).willReturn(okJson("[]")));
    }

    @AfterAll
    static void stopSystems() {
        if (lab != null) {
            lab.close();
        }
    }

    // --- fixtures -----------------------------------------------------------------------------

    /** The lab's workspace, with the REST target {@code tenant} whose URL carries a secrets.env value. */
    private Path workspace() throws Exception {
        Path ws = lab.workspace(temp.resolve("ws"), LabFixture.database(temp.resolve("db")), null);
        lab.tenantTarget(ws, "IDM_TEST_TENANT_KEY");
        Files.writeString(ws.resolve("secrets.env"), "IDM_TEST_TENANT_KEY=" + TENANT_SECRET + "\n",
                StandardOpenOption.APPEND);
        return ws;
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
        Runnable onScenarioFinished = () -> { };

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
            onScenarioFinished.run();
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
        assertThat(outcome.setupProblems()).isZero();
        assertThat(outcome.stoppedScenarios()).isEqualTo(1);
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

    @Test
    @DisplayName("Should keep every scenario in results.json and report.html when the listener throws at every event")
    void testThrowingListenerKeepsEveryResult() throws Exception {
        Path ws = workspace();
        scenario(ws, "a-bad.yaml", BAD_STEP);
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        scenario(ws, "wrong-sn.yaml", WRONG_SN);
        RunEvents throwing = new RunEvents() {
            @Override
            public void runFolder(String runId) {
                throw new IllegalStateException("listener gone");
            }

            @Override
            public void runStarted(String runId, List<String> scenarios, Locale locale) {
                throw new IllegalStateException("listener gone");
            }

            @Override
            public void scenarioStarted(String name) {
                throw new IllegalStateException("listener gone");
            }

            @Override
            public void stepStarted(int index, String type, String description) {
                throw new IllegalStateException("listener gone");
            }

            @Override
            public void attempt(int index, int attempt, long elapsedMillis, String message) {
                throw new IllegalStateException("listener gone");
            }

            @Override
            public void stepFinished(StepResult result) {
                throw new IllegalStateException("listener gone");
            }

            @Override
            public void cleanupFinished(CleanupResult result) {
                throw new IllegalStateException("listener gone");
            }

            @Override
            public void scenarioFinished(ScenarioResult result, String failureLine) {
                throw new IllegalStateException("listener gone");
            }

            @Override
            public void runFinished(RunOutcome outcome) {
                throw new IllegalStateException("listener gone");
            }

            @Override
            public void refused(Message reason, String text, String stackTrace) {
                throw new IllegalStateException("listener gone");
            }

            @Override
            public void warning(Message warning, String text) {
                throw new IllegalStateException("listener gone");
            }
        };

        RunOutcome outcome = new RunService(CLOCK, false).run(request(ws), throwing, () -> false);

        assertThat(outcome.exitCode()).isEqualTo(ExitCodes.FAILED);
        assertThat(outcome.total()).isEqualTo(3);
        assertThat(outcome.passed()).isEqualTo(1);
        assertThat(outcome.findings()).isEqualTo(1);
        assertThat(outcome.setupProblems()).isEqualTo(1);
        for (String file : List.of("results.json", "report.html")) {
            assertThat(Files.readString(outcome.folder().resolve(file)))
                    .contains("a-bad.yaml", "provisioning-user-to-ldap.yaml", "wrong-sn.yaml");
        }
    }

    @Test
    @DisplayName("Should count a scenario stopped during a wait as stopped, not as a setup problem")
    void testStoppedCountsOnItsOwn() throws Exception {
        Path ws = workspace();
        scenario(ws, "a-wrong-sn.yaml", WRONG_SN);
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        Recorder recorder = new Recorder();
        AtomicBoolean stop = new AtomicBoolean();
        recorder.onAttempt = () -> stop.set(true);

        RunOutcome outcome = new RunService(CLOCK, false).run(request(ws), recorder, stop::get);

        assertThat(outcome.stopped()).isTrue();
        assertThat(outcome.exitCode()).isEqualTo(ExitCodes.FAILED);
        assertThat(outcome.stoppedScenarios()).isEqualTo(1);
        assertThat(outcome.setupProblems()).isZero();
        assertThat(outcome.passed()).isZero();
        assertThat(outcome.findings()).isZero();
    }

    @Test
    @DisplayName("Should start no more scenarios after a stop between scenarios, each result written before it is told")
    void testStopBetweenScenarios() throws Exception {
        Path ws = workspace();
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        scenario(ws, "wrong-sn.yaml", WRONG_SN);
        Recorder recorder = new Recorder();
        AtomicBoolean stop = new AtomicBoolean();
        List<String> resultsWhenTold = new ArrayList<>();
        recorder.onScenarioFinished = () -> {
            try {
                resultsWhenTold.add(Files.readString(ws.resolve("reports").resolve(RUN_ID).resolve("results.json")));
            } catch (java.io.IOException e) {
                resultsWhenTold.add("unreadable: " + e);
            }
            stop.set(true);
        };

        RunOutcome outcome = new RunService(CLOCK, false).run(request(ws), recorder, stop::get);

        assertThat(outcome.stopped()).isTrue();
        assertThat(outcome.total()).isEqualTo(2);
        assertThat(outcome.passed()).isEqualTo(1);
        assertThat(outcome.exitCode()).isEqualTo(ExitCodes.FAILED);
        assertThat(recorder.events).contains("scenarioFinished provisioning-user-to-ldap.yaml true")
                .doesNotContain("scenarioStarted wrong-sn.yaml");
        assertThat(recorder.events.getLast()).isEqualTo("runFinished");
        assertThat(resultsWhenTold).singleElement().asString().contains("provisioning-user-to-ldap.yaml");
    }

    private static void assertNoSecret(Recorder recorder) {
        String all = String.join("\n", recorder.texts.stream().map(String::valueOf).toList())
                + String.join("\n", recorder.events);
        for (String secret : List.of(LDAP_PASSWORD, REST_PASSWORD, DB_PASSWORD, TENANT_SECRET)) {
            assertThat(all).doesNotContain(secret);
        }
    }
}
