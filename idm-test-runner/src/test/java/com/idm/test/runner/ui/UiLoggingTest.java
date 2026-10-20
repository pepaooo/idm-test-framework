package com.idm.test.runner.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.idm.test.runner.LabFixture;
import com.idm.test.runner.run.RunEvents;
import com.idm.test.runner.run.RunOutcome;
import com.idm.test.runner.run.RunRequest;
import com.idm.test.runner.run.RunService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static com.idm.test.runner.LabFixture.corpusScenario;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workbench's logging guard: the workbench's request loggers never log below INFO in its process,
 * whatever a run's profile sets and at whatever moment of the run — and the guard never reaches a
 * CLI run. Lines are logged from another thread, as a request thread of the web server would.
 */
@DisplayName("Workbench logging guard")
class UiLoggingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T10:15:30Z"), ZoneOffset.UTC);
    private static final String QUIET_LOGGER = "org.springframework.web.servlet.DispatcherServlet";
    private static final String TOMCAT_LOGGER = "org.apache.coyote.http11.Http11InputBuffer";
    private static final String OPEN_LOGGER = "com.idm.test.probe.Open";

    private static LabFixture lab;

    @TempDir
    Path temp;

    @BeforeAll
    static void startSystems() throws Exception {
        lab = LabFixture.start();
    }

    @AfterAll
    static void stopSystems() {
        if (lab != null) {
            lab.close();
        }
        UiLogging.uninstall();
    }

    private static LoggerContext context() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    /** Logs {@code message} at DEBUG on {@code logger} from a thread of its own, and waits for it. */
    private static void debugFromAnotherThread(String logger, String message) {
        Thread thread = Thread.ofPlatform().start(() -> LoggerFactory.getLogger(logger).debug(message));
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean installed() {
        return context().getTurboFilterList().stream().anyMatch(UiLogging.QuietFilter.class::isInstance);
    }

    /** A lab workspace whose profile turns DEBUG and TRACE logging on for the web server and a probe logger. */
    private Path workspace() throws Exception {
        Path ws = lab.workspace(temp.resolve("ws"), LabFixture.database(temp.resolve("db")), null);
        Path settings = ws.resolve("config/application-lab.yml");
        Files.writeString(settings, Files.readString(settings) + """
                logging:
                  level:
                    root: TRACE
                    org.springframework.web: TRACE
                    org.apache.coyote: DEBUG
                    com.idm.test.probe: DEBUG
                """);
        corpusScenario(ws, "provisioning-user-to-ldap.yaml");
        return ws;
    }

    @Test
    @DisplayName("Should deny DEBUG on a workbench logger whatever its level, and keep denying after a Logback reset")
    void testFilterDeniesWhateverTheLevel() {
        UiLogging.install();
        Logger quiet = context().getLogger(QUIET_LOGGER);
        Level before = quiet.getLevel();
        ListAppender<ILoggingEvent> seen = new ListAppender<>();
        seen.start();
        quiet.addAppender(seen);
        Logger open = context().getLogger(OPEN_LOGGER);
        open.addAppender(seen);
        try {
            // What a profile's logging.level does, before the workbench can raise it again.
            quiet.setLevel(Level.TRACE);
            open.setLevel(Level.DEBUG);
            debugFromAnotherThread(QUIET_LOGGER, "quiet-probe-1");
            debugFromAnotherThread(OPEN_LOGGER, "open-probe-1");

            // A Spring Boot logging initialisation resets Logback, which empties the turbo filters:
            // the reset listener puts the guard back as part of the reset.
            context().getTurboFilterList().clear();
            UiLogging.RESET_GUARD.onReset(context());
            debugFromAnotherThread(QUIET_LOGGER, "quiet-probe-2");

            assertThat(seen.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly("open-probe-1");
            assertThat(installed()).isTrue();
            assertThat(context().getCopyOfListenerList()).contains(UiLogging.RESET_GUARD);
            assertThat(UiLogging.RESET_GUARD.isResetResistant()).isTrue();
        } finally {
            quiet.detachAppender(seen);
            open.detachAppender(seen);
            quiet.setLevel(before);
            open.setLevel(null);
            UiLogging.install();
        }
    }

    @Test
    @DisplayName("Should keep workbench loggers out of run.log while the run's context starts and runs, and restore levels after")
    void testQuietWhileTheRunContextStarts() throws Exception {
        Path ws = workspace();
        UiLogging.install();
        UiLogging logging = new UiLogging();
        Level rootBefore = context().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel();
        Level webBefore = context().getLogger("org.springframework.web").getLevel();
        Consumer<Environment> hook = environment -> {
            debugFromAnotherThread(QUIET_LOGGER, "quiet-before-levels");
            logging.applyRunLevels(environment);
            debugFromAnotherThread(QUIET_LOGGER, "quiet-after-levels");
            debugFromAnotherThread(TOMCAT_LOGGER, "tomcat-after-levels");
            debugFromAnotherThread(OPEN_LOGGER, "open-after-levels");
        };
        RunEvents events = new RunEvents() {
            @Override
            public void scenarioStarted(String name) {
                debugFromAnotherThread(QUIET_LOGGER, "quiet-during-scenario");
                debugFromAnotherThread(OPEN_LOGGER, "open-during-scenario");
            }
        };

        RunOutcome outcome;
        try {
            outcome = new RunService(CLOCK, false, hook).run(
                    new RunRequest(ws, "lab", List.of(), Locale.ENGLISH, Map.of()), events, () -> false);
        } finally {
            logging.restore();
        }

        assertThat(outcome.passed()).isEqualTo(1);
        String runLog = Files.readString(outcome.folder().resolve("run.log"));
        // the run's own levels applied: the probe logger's DEBUG lines are there
        assertThat(runLog).contains("open-after-levels", "open-during-scenario");
        assertThat(runLog).doesNotContain("quiet-before-levels", "quiet-after-levels", "tomcat-after-levels",
                "quiet-during-scenario");
        // and after the run, the levels are the workbench's again
        assertThat(context().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel()).isEqualTo(rootBefore);
        assertThat(context().getLogger("org.springframework.web").getLevel()).isEqualTo(webBefore);
        assertThat(context().getLogger("com.idm.test.probe").getLevel()).isNull();
        assertThat(installed()).isTrue();
    }

    @Test
    @DisplayName("Should leave a CLI run's logging alone: no guard, Spring Boot's own logging settings")
    void testCliRunHasNoGuard() throws Exception {
        Path ws = workspace();
        UiLogging.uninstall();
        UiLogging levels = new UiLogging();
        levels.snapshot();
        try {
            RunEvents events = new RunEvents() {
                @Override
                public void scenarioStarted(String name) {
                    debugFromAnotherThread(QUIET_LOGGER, "cli-web-debug");
                }
            };

            RunOutcome outcome = new RunService(CLOCK, false).run(
                    new RunRequest(ws, "lab", List.of(), Locale.ENGLISH, Map.of()), events, () -> false);

            assertThat(installed()).isFalse();
            assertThat(Files.readString(outcome.folder().resolve("run.log"))).contains("cli-web-debug");
        } finally {
            // the CLI run's levels stay in this JVM, as they do after idmtest run: put them back for the other tests
            levels.restore();
        }
    }
}
