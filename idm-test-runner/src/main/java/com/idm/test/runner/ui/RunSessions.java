package com.idm.test.runner.ui;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.JsonMappers;
import com.idm.test.engine.spi.Message;
import com.idm.test.runner.run.RunEvents;
import com.idm.test.runner.run.RunOutcome;
import com.idm.test.runner.run.RunRequest;
import com.idm.test.runner.run.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * The workbench's runs: one at a time, each on its own daemon thread through {@link RunService} —
 * the same path as {@code idmtest run} — with its events kept for the browser tabs that subscribe.
 * <p>
 * The slot is taken atomically by {@link #start} and given back when the run's thread ends, however it
 * ends. {@link #stop} sets the flag the run polls; the engine notices it within a moment, during a wait
 * too, and the scenario's cleanup does not run.
 * <p>
 * Every event of the current (or, once it ended, the most recent) run is kept, up to {@link #KEPT}
 * (past that, only the events of scenarios and of the run as a whole), so a subscriber gets the run's
 * events so far and then the new ones, in order, each once. Each subscriber has its own sender
 * thread: a browser that is slow or gone never holds up the run, and its failed send only drops it.
 * The events' texts are those {@link RunService} delivers, masked with the run's secret values.
 */
public final class RunSessions {

    private static final Logger LOG = LoggerFactory.getLogger(RunSessions.class);

    /** The events kept of one run before only those of scenarios and of the run are. */
    static final int KEPT = 10_000;
    static final Duration HEARTBEAT = Duration.ofSeconds(15);
    /** How long {@link #start} waits for the run's folder before answering without its id. */
    private static final Duration ID_WAIT = Duration.ofSeconds(30);
    private static final Set<String> ALWAYS_KEPT = Set.of("runStarted", "scenarioStarted", "scenarioFinished",
            "runFinished", "refused", "warning");
    private static final JsonMapper JSON = JsonMappers.builder().build();

    /** Runs a request: {@link RunService#run} in the workbench; a stand-in in tests. */
    @FunctionalInterface
    public interface Runner {
        RunOutcome run(RunRequest request, RunEvents events, BooleanSupplier stopRequested);
    }

    private final Runner runner;
    private final Map<String, String> environmentOverride;
    private final AtomicReference<Run> active = new AtomicReference<>();
    private volatile Run latest;

    /**
     * @param environmentOverride stands in for the process's environment variables in every run when not
     *                            null (tests); null in production
     */
    public RunSessions(Runner runner, Map<String, String> environmentOverride) {
        this.runner = runner;
        this.environmentOverride = environmentOverride == null ? null : Map.copyOf(environmentOverride);
    }

    /** The workbench's: {@link RunService} on the system clock, its run log off the console. */
    public static RunSessions withRunService(Map<String, String> environmentOverride) {
        RunService service = new RunService();
        return new RunSessions(service::run, environmentOverride);
    }

    /** Another run is active. */
    public static final class BusyException extends RuntimeException {
        BusyException() {
            super("a run is active");
        }
    }

    /**
     * Starts a run of {@code scenarios} (patterns as {@link RunRequest} takes them; empty: all) on its
     * own thread, and returns its id once the run has its folder (null when it was refused before, or
     * did not get one within 30 s; its events tell why).
     *
     * @throws BusyException when a run is active
     */
    public String start(Path workspace, String profile, List<String> scenarios, Locale locale) {
        Run run = new Run(locale);
        if (!active.compareAndSet(null, run)) {
            throw new BusyException();
        }
        latest = run;
        RunRequest request = new RunRequest(workspace, profile, scenarios, locale, environmentOverride);
        try {
            Thread.ofPlatform().daemon().name("idmtest-ui-run").start(() -> execute(run, request));
        } catch (RuntimeException | Error e) {
            active.compareAndSet(run, null);
            run.finish();
            throw e;
        }
        try {
            run.idKnown.await(ID_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return run.runId;
    }

    private void execute(Run run, RunRequest request) {
        try {
            runner.run(request, run, run.stop::get);
        } catch (RuntimeException | Error e) {
            // RunService turns failures into events; anything else still ends the run for its subscribers.
            LOG.error("The workbench's run thread failed", e);
            Message reason = Message.of("runner.ui.run.crashed", e.getClass().getSimpleName());
            run.add("refused", new Refused(reason.key(), Messages.render(reason, run.locale)));
        } finally {
            active.compareAndSet(run, null);
            run.finish();
        }
    }

    /**
     * Asks the active run to stop.
     *
     * @return whether a run was active
     */
    public boolean stop() {
        Run run = active.get();
        if (run == null) {
            return false;
        }
        run.stop.set(true);
        return true;
    }

    /** The active run's id, or null when no run is active or it has no folder yet. */
    public String activeRunId() {
        Run run = active.get();
        return run == null ? null : run.runId;
    }

    /** Whether a run is active. */
    public boolean active() {
        return active.get() != null;
    }

    /**
     * A subscriber of the current or most recent run: its events so far, then the new ones; completed
     * after the run's last event. Null when the workbench has not run anything yet.
     */
    public SseEmitter subscribe() {
        Run run = latest;
        if (run == null) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(run, emitter);
        emitter.onCompletion(subscriber::close);
        emitter.onTimeout(subscriber::close);
        emitter.onError(e -> subscriber.close());
        Thread.ofVirtual().name("idmtest-ui-run-events").start(subscriber::send);
        return emitter;
    }

    /** The events kept of the current or most recent run (tests). */
    List<Event> latestEvents() {
        Run run = latest;
        if (run == null) {
            return List.of();
        }
        synchronized (run) {
            return List.copyOf(run.events);
        }
    }

    /** Stops the active run, if any: the workbench is closing. */
    public void shutdown() {
        Run run = active.get();
        if (run != null) {
            run.stop.set(true);
        }
    }

    // --- events -----------------------------------------------------------------------------------

    /** One event as sent: its name and its data as JSON. */
    record Event(String name, String json) {
    }

    record RunStarted(String runId, List<String> scenarios, String locale) {
    }

    record ScenarioStarted(String name) {
    }

    record StepStarted(int index, String type, String description) {
    }

    record Attempt(int index, int attempt, long elapsedMillis, String message) {
    }

    record ScenarioFinished(ScenarioResult result, String failureLine) {
    }

    record Refused(String key, String message) {
    }

    record Warning(String key, String message) {
    }

    /** {@link RunOutcome} without the folder's path, and where its report is served. */
    record RunFinished(String runId, int total, int passed, int findings, int setupProblems, boolean stopped,
                       int exitCode, String reportUrl) {
    }

    /** One run: its stop flag, id and events. */
    static final class Run implements RunEvents {

        final AtomicBoolean stop = new AtomicBoolean();
        final CountDownLatch idKnown = new CountDownLatch(1);
        final Locale locale;
        volatile String runId;
        private final List<Event> events = new ArrayList<>();
        private boolean finished;

        Run(Locale locale) {
            this.locale = locale;
        }

        void add(String name, Object data) {
            String json;
            try {
                json = JSON.writeValueAsString(data);
            } catch (JacksonException e) {
                LOG.warn("Workbench run event {} could not be written as JSON: {}", name, e.getClass().getName());
                return;
            }
            synchronized (this) {
                if (events.size() < KEPT || ALWAYS_KEPT.contains(name)) {
                    events.add(new Event(name, json));
                }
                notifyAll();
            }
        }

        synchronized void finish() {
            finished = true;
            idKnown.countDown();
            notifyAll();
        }

        synchronized void wake() {
            notifyAll();
        }

        /** The events from {@code from} on (empty when there are none yet), and whether the run is over. */
        synchronized Batch await(int from, long millis) throws InterruptedException {
            if (from >= events.size() && !finished) {
                wait(millis);
            }
            return new Batch(from < events.size() ? List.copyOf(events.subList(from, events.size())) : List.of(),
                    finished);
        }

        record Batch(List<Event> events, boolean finished) {
        }

        @Override
        public void runFolder(String id) {
            runId = id;
            idKnown.countDown();
        }

        @Override
        public void runStarted(String id, List<String> scenarios, Locale runLocale) {
            add("runStarted", new RunStarted(id, scenarios, runLocale.toLanguageTag()));
        }

        @Override
        public void scenarioStarted(String name) {
            add("scenarioStarted", new ScenarioStarted(name));
        }

        @Override
        public void stepStarted(int index, String type, String description) {
            add("stepStarted", new StepStarted(index, type, description));
        }

        @Override
        public void attempt(int index, int attempt, long elapsedMillis, String message) {
            add("attempt", new Attempt(index, attempt, elapsedMillis, message));
        }

        @Override
        public void stepFinished(StepResult result) {
            add("stepFinished", result);
        }

        @Override
        public void scenarioFinished(ScenarioResult result, String failureLine) {
            add("scenarioFinished", new ScenarioFinished(result, failureLine));
        }

        @Override
        public void runFinished(RunOutcome outcome) {
            add("runFinished", new RunFinished(outcome.runId(), outcome.total(), outcome.passed(), outcome.findings(),
                    outcome.setupProblems(), outcome.stopped(), outcome.exitCode(),
                    outcome.runId() == null ? null : "/reports/" + outcome.runId() + "/report.html"));
        }

        @Override
        public void refused(Message reason, String text, String stackTrace) {
            add("refused", new Refused(reason.key(), text));
        }

        @Override
        public void warning(Message warning, String text) {
            add("warning", new Warning(warning.key(), text));
        }
    }

    /** Sends one run's events to one browser tab, on its own thread. */
    private static final class Subscriber {

        private final Run run;
        private final SseEmitter emitter;
        private volatile boolean closed;

        Subscriber(Run run, SseEmitter emitter) {
            this.run = run;
            this.emitter = emitter;
        }

        void close() {
            closed = true;
            run.wake();
        }

        void send() {
            int next = 0;
            long lastSent = System.nanoTime();
            try {
                while (!closed) {
                    Run.Batch batch = run.await(next, HEARTBEAT.toMillis());
                    for (Event event : batch.events()) {
                        emitter.send(SseEmitter.event().id(String.valueOf(next)).name(event.name()).data(event.json()));
                        next++;
                        lastSent = System.nanoTime();
                    }
                    if (batch.finished() && batch.events().isEmpty()) {
                        end(null);
                        return;
                    }
                    if (System.nanoTime() - lastSent >= HEARTBEAT.toNanos()) {
                        emitter.send(SseEmitter.event().comment(""));
                        lastSent = System.nanoTime();
                    }
                }
            } catch (IOException | IllegalStateException e) {
                // the browser went away: this subscriber ends, the run goes on
                end(e);
            } catch (InterruptedException e) {
                end(null);
            }
        }

        private void end(Exception failure) {
            try {
                if (failure == null) {
                    emitter.complete();
                } else {
                    emitter.completeWithError(failure);
                }
            } catch (RuntimeException e) {
                // already completed
            }
        }
    }
}
