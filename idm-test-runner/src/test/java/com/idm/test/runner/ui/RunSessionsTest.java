package com.idm.test.runner.ui;

import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.runner.run.RunOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The run slot and the kept events, with stand-in runners: what the real run service cannot be made
 * to do on purpose (block on a latch, or die).
 */
@DisplayName("Workbench run sessions")
class RunSessionsTest {

    @TempDir
    Path ws;

    @Test
    @DisplayName("Should hold one run at a time and give the slot back when it ends")
    void testOneAtATime() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicBoolean sawStop = new AtomicBoolean();
        RunSessions sessions = new RunSessions((request, events, stop) -> {
            events.runFolder("20261019-101530-lab");
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sawStop.set(stop.getAsBoolean());
            ended.countDown();
            return new RunOutcome("20261019-101530-lab", null, 0, 0, 0, 0, 0, true, 1);
        }, Map.of());

        assertThat(sessions.start(ws, "lab", List.of(), Locale.ENGLISH, () -> ws)).isEqualTo("20261019-101530-lab");
        assertThat(sessions.active()).isTrue();
        assertThatThrownBy(() -> sessions.start(ws, "lab", List.of(), Locale.ENGLISH, () -> ws))
                .isInstanceOf(RunSessions.BusyException.class);
        assertThat(sessions.stop()).isTrue();
        release.countDown();
        assertThat(ended.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sawStop).isTrue();
        waitIdle(sessions);
        assertThat(sessions.stop()).isFalse();
    }

    @Test
    @DisplayName("Should give the slot back and end the events with refused when the run thread dies")
    void testRunThreadDies() throws Exception {
        RunSessions sessions = new RunSessions((request, events, stop) -> {
            throw new IllegalStateException("the run broke");
        }, Map.of());

        assertThat(sessions.start(ws, "lab", List.of(), Locale.ENGLISH, () -> ws)).isNull();
        waitIdle(sessions);

        List<RunSessions.Event> events = sessions.latestEvents();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.name()).isEqualTo("refused");
            assertThat(event.json()).contains("runner.ui.run.crashed", "IllegalStateException")
                    .doesNotContain("the run broke");
        });
        // the slot is free again: a new run starts instead of being refused as busy
        assertThat(sessions.start(ws, "lab", List.of(), Locale.ENGLISH, () -> ws)).isNull();
        waitIdle(sessions);
    }

    @Test
    @DisplayName("Should give the slot back and end the run for its subscribers when the after-run step throws an Error")
    void testAfterRunError() throws Exception {
        RunSessions sessions = new RunSessions((request, events, stop) -> {
            events.runFolder("20261019-101530-lab");
            return new RunOutcome("20261019-101530-lab", null, 0, 0, 0, 0, 0, false, 0);
        }, Map.of());
        sessions.afterEachRun(() -> {
            throw new Error("after-run broke");
        });

        sessions.start(ws, "lab", List.of(), Locale.ENGLISH, () -> ws);
        waitIdle(sessions);

        assertThat(sessions.latestFinished()).isTrue();
        // the slot is free: the next run is not refused as busy
        sessions.start(ws, "lab", List.of(), Locale.ENGLISH, () -> ws);
        waitIdle(sessions);
        assertThat(sessions.latestFinished()).isTrue();
    }

    @Test
    @DisplayName("Should keep at most 10 000 events, past that only the scenarios' and the run's")
    void testEventsBounded() throws Exception {
        RunSessions sessions = new RunSessions((request, events, stop) -> {
            for (int i = 0; i < RunSessions.KEPT + 50; i++) {
                events.attempt(0, i + 1, i, "try " + i);
            }
            events.scenarioFinished(new ScenarioResult("a", "a.yaml", true, 0,
                    List.of(), List.of(), null), null);
            return new RunOutcome(null, null, 1, 1, 0, 0, 0, false, 0);
        }, Map.of());

        sessions.start(ws, "lab", List.of(), Locale.ENGLISH, () -> ws);
        waitIdle(sessions);

        List<RunSessions.Event> events = sessions.latestEvents();
        assertThat(events).hasSize(RunSessions.KEPT + 1);
        assertThat(events.getLast().name()).isEqualTo("scenarioFinished");
    }

    // --- the workspace while a run is going ---------------------------------------------------------

    /** Sessions whose runs get their folder at once, then block until {@code release} opens. */
    private static RunSessions blocking(CountDownLatch release) {
        return new RunSessions((request, events, stop) -> {
            events.runFolder("20261021-090000-lab");
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new RunOutcome("20261021-090000-lab", null, 0, 0, 0, 0, 0, false, 0);
        }, Map.of());
    }

    @Test
    @DisplayName("Should refuse to switch to another workspace while a run of this one is going")
    void testSwitchRefusedWhileRunning() throws Exception {
        Path x = Files.createDirectories(ws.resolve("x"));
        Path y = Files.createDirectories(ws.resolve("y"));
        UiSession session = new UiSession("fake-token", Locale.ENGLISH, x);
        CountDownLatch release = new CountDownLatch(1);
        RunSessions sessions = blocking(release);
        try {
            sessions.start(x, "lab", List.of(), Locale.ENGLISH, session::workspace);
            Path realX = x.toRealPath();
            assertThat(sessions.activeWorkspace()).isEqualTo(realX);

            assertThatThrownBy(() -> sessions.switchWorkspace(session, y))
                    .isInstanceOfSatisfying(RunSessions.RunningElsewhereException.class,
                            e -> assertThat(e.running()).isEqualTo(realX));
            assertThat(session.workspace()).isEqualTo(x);
        } finally {
            release.countDown();
            waitIdle(sessions);
        }
    }

    @Test
    @DisplayName("Should open the workspace that is being run")
    void testSameWorkspaceOpens() throws Exception {
        Path x = Files.createDirectories(ws.resolve("x"));
        UiSession session = new UiSession("fake-token", Locale.ENGLISH, x);
        CountDownLatch release = new CountDownLatch(1);
        RunSessions sessions = blocking(release);
        try {
            sessions.start(x, "lab", List.of(), Locale.ENGLISH, session::workspace);
            // the same folder by another spelling is the same workspace
            sessions.switchWorkspace(session, ws.resolve("x/../x"));
            assertThat(session.workspace()).isEqualTo(ws.resolve("x/../x"));
        } finally {
            release.countDown();
            waitIdle(sessions);
        }
    }

    @Test
    @DisplayName("Should switch once the run has ended")
    void testSwitchAfterRun() throws Exception {
        Path x = Files.createDirectories(ws.resolve("x"));
        Path y = Files.createDirectories(ws.resolve("y"));
        UiSession session = new UiSession("fake-token", Locale.ENGLISH, x);
        CountDownLatch release = new CountDownLatch(1);
        RunSessions sessions = blocking(release);
        sessions.start(x, "lab", List.of(), Locale.ENGLISH, session::workspace);
        release.countDown();
        waitIdle(sessions);

        assertThat(sessions.activeWorkspace()).isNull();
        sessions.switchWorkspace(session, y);
        assertThat(session.workspace()).isEqualTo(y);
    }

    @Test
    @DisplayName("Should refuse a run of a workspace that is no longer the session's")
    void testStartRefusedWhenSwitched() throws Exception {
        Path x = Files.createDirectories(ws.resolve("x"));
        Path y = Files.createDirectories(ws.resolve("y"));
        UiSession session = new UiSession("fake-token", Locale.ENGLISH, y);
        RunSessions sessions = blocking(new CountDownLatch(0));

        assertThatThrownBy(() -> sessions.start(x, "lab", List.of(), Locale.ENGLISH, session::workspace))
                .isInstanceOf(RunSessions.SwitchedException.class);
        assertThat(sessions.active()).isFalse();
    }

    @Test
    @DisplayName("Opening another workspace and starting a run never both happen")
    void testOpenAndStartRace() throws Exception {
        Path x = Files.createDirectories(ws.resolve("x"));
        Path y = Files.createDirectories(ws.resolve("y"));
        for (int round = 0; round < 200; round++) {
            UiSession session = new UiSession("fake-token", Locale.ENGLISH, x);
            CountDownLatch release = new CountDownLatch(1);
            RunSessions sessions = blocking(release);
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicReference<Object> started = new AtomicReference<>();
            AtomicReference<Object> switched = new AtomicReference<>();
            Thread starter = Thread.ofPlatform().start(() -> started.set(attempt(barrier,
                    () -> sessions.start(x, "lab", List.of(), Locale.ENGLISH, session::workspace))));
            Thread opener = Thread.ofPlatform().start(() -> switched.set(attempt(barrier, () -> {
                sessions.switchWorkspace(session, y);
                return "opened";
            })));
            starter.join(10_000);
            opener.join(10_000);
            try {
                boolean runOnX = "20261021-090000-lab".equals(started.get())
                        && switched.get() instanceof RunSessions.RunningElsewhereException
                        && x.equals(session.workspace());
                boolean openedY = started.get() instanceof RunSessions.SwitchedException
                        && "opened".equals(switched.get()) && y.equals(session.workspace());
                assertThat(runOnX || openedY)
                        .as("round %d: start=%s, open=%s, session=%s", round, started.get(), switched.get(),
                                session.workspace())
                        .isTrue();
            } finally {
                release.countDown();
                waitIdle(sessions);
            }
        }
    }

    @Test
    @DisplayName("A switch that comes while a run is being installed waits for it and is then refused")
    void testSwitchDuringStartWindow() throws Exception {
        Path x = Files.createDirectories(ws.resolve("x"));
        Path y = Files.createDirectories(ws.resolve("y"));
        UiSession session = new UiSession("fake-token", Locale.ENGLISH, x);
        CountDownLatch release = new CountDownLatch(1);
        RunSessions sessions = blocking(release);
        CountDownLatch inWindow = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        // start() reads the session's workspace, then sits between that read and installing the run
        Supplier<Path> current = () -> {
            Path read = session.workspace();
            inWindow.countDown();
            try {
                proceed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return read;
        };
        AtomicReference<Object> started = new AtomicReference<>();
        AtomicReference<Object> switched = new AtomicReference<>();
        Thread starter = Thread.ofPlatform().start(() -> started.set(call(
                () -> sessions.start(x, "lab", List.of(), Locale.ENGLISH, current))));
        try {
            assertThat(inWindow.await(5, TimeUnit.SECONDS)).isTrue();
            Thread opener = Thread.ofPlatform().start(() -> switched.set(call(() -> {
                sessions.switchWorkspace(session, y);
                return "opened";
            })));
            // the switch either blocks behind start() (the lock) or, without one, gets through at once
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (opener.getState() != Thread.State.BLOCKED && opener.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            proceed.countDown();
            starter.join(10_000);
            opener.join(10_000);

            assertThat(started.get()).as("start").isEqualTo("20261021-090000-lab");
            assertThat(switched.get()).as("switch").isInstanceOf(RunSessions.RunningElsewhereException.class);
            assertThat(session.workspace()).isEqualTo(x);
        } finally {
            proceed.countDown();
            release.countDown();
            starter.join(10_000);
            waitIdle(sessions);
        }
    }

    /** {@code action}'s result, or what it threw. */
    private static Object call(Callable<Object> action) {
        try {
            return action.call();
        } catch (Exception e) {
            return e;
        }
    }

    /** {@code action}'s result after the barrier, or what it threw. */
    private static Object attempt(CyclicBarrier barrier, Callable<Object> action) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return action.call();
        } catch (Exception e) {
            return e;
        }
    }

    private static void waitIdle(RunSessions sessions) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sessions.active() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(sessions.active()).isFalse();
    }
}
