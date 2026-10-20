package com.idm.test.runner.ui;

import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.runner.run.RunOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
            return new RunOutcome("20261019-101530-lab", null, 0, 0, 0, 0, true, 1);
        }, Map.of());

        assertThat(sessions.start(ws, "lab", List.of(), Locale.ENGLISH)).isEqualTo("20261019-101530-lab");
        assertThat(sessions.active()).isTrue();
        assertThatThrownBy(() -> sessions.start(ws, "lab", List.of(), Locale.ENGLISH))
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

        assertThat(sessions.start(ws, "lab", List.of(), Locale.ENGLISH)).isNull();
        waitIdle(sessions);

        List<RunSessions.Event> events = sessions.latestEvents();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.name()).isEqualTo("refused");
            assertThat(event.json()).contains("runner.ui.run.crashed", "IllegalStateException")
                    .doesNotContain("the run broke");
        });
        // the slot is free again: a new run starts instead of being refused as busy
        assertThat(sessions.start(ws, "lab", List.of(), Locale.ENGLISH)).isNull();
        waitIdle(sessions);
    }

    @Test
    @DisplayName("Should give the slot back and end the run for its subscribers when the after-run step throws an Error")
    void testAfterRunError() throws Exception {
        RunSessions sessions = new RunSessions((request, events, stop) -> {
            events.runFolder("20261019-101530-lab");
            return new RunOutcome("20261019-101530-lab", null, 0, 0, 0, 0, false, 0);
        }, Map.of());
        sessions.afterEachRun(() -> {
            throw new Error("after-run broke");
        });

        sessions.start(ws, "lab", List.of(), Locale.ENGLISH);
        waitIdle(sessions);

        assertThat(sessions.latestFinished()).isTrue();
        // the slot is free: the next run is not refused as busy
        sessions.start(ws, "lab", List.of(), Locale.ENGLISH);
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
            return new RunOutcome(null, null, 1, 1, 0, 0, false, 0);
        }, Map.of());

        sessions.start(ws, "lab", List.of(), Locale.ENGLISH);
        waitIdle(sessions);

        List<RunSessions.Event> events = sessions.latestEvents();
        assertThat(events).hasSize(RunSessions.KEPT + 1);
        assertThat(events.getLast().name()).isEqualTo("scenarioFinished");
    }

    private static void waitIdle(RunSessions sessions) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sessions.active() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(sessions.active()).isFalse();
    }
}
