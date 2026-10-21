package com.idm.test.runner.ui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workbench's run events stream ({@code GET /api/runs/current/events}) as a test reads it: on its
 * own thread, into a queue, until the stream ends. Shared by the runs API tests.
 */
public final class RunEventStream implements AutoCloseable {

    /** How long a test waits for the end of a run. */
    public static final Duration END = Duration.ofSeconds(120);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Sse END_OF_STREAM = new Sse(null, "", null, "");

    /** One Server-Sent Event: its id, its name, its data as JSON, and the data as sent. */
    public record Sse(String id, String name, JsonNode data, String raw) {
    }

    private final BlockingQueue<Sse> queue = new LinkedBlockingQueue<>();
    private final CompletableFuture<HttpResponse<Stream<String>>> response;
    private final List<Sse> seen = new ArrayList<>();

    /** Subscribes to {@code server}'s run events. */
    public RunEventStream(UiServer server) {
        this(server, null);
    }

    /** Subscribes with {@code Last-Event-ID: lastEventId} unless it is null, as a reconnecting browser does. */
    public RunEventStream(UiServer server, String lastEventId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + server.port() + UiHttp.withWorkspace(server, "/api/runs/current/events")))
                .header("Cookie", UiHttp.cookie(server)).GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        response = HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofLines());
        response.thenAccept(r -> {
            String id = null;
            String name = null;
            StringBuilder data = new StringBuilder();
            try (Stream<String> lines = r.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (line.isEmpty()) {
                        if (name != null) {
                            queue.add(new Sse(id, name, JSON.readTree(data.toString()), data.toString()));
                        }
                        id = null;
                        name = null;
                        data.setLength(0);
                    } else if (line.startsWith("id:")) {
                        id = line.substring("id:".length()).strip();
                    } else if (line.startsWith("event:")) {
                        name = line.substring("event:".length()).strip();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring("data:".length()));
                    }
                }
            } catch (RuntimeException e) {
                // the stream was closed
            } finally {
                queue.add(END_OF_STREAM);
            }
        }).exceptionally(e -> {
            queue.add(END_OF_STREAM);
            return null;
        });
    }

    /** The response's status (waits for its headers). */
    public int status() throws Exception {
        return response.get(END.toMillis(), TimeUnit.MILLISECONDS).statusCode();
    }

    /** The next event, or null at the end of the stream; fails after {@code within}. */
    public Sse next(Duration within) throws InterruptedException {
        Sse event = queue.poll(within.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(event).as("an event within " + within + "; seen " + names(seen)).isNotNull();
        if (event == END_OF_STREAM) {
            queue.add(END_OF_STREAM);
            return null;
        }
        seen.add(event);
        return event;
    }

    /** Reads until the event {@code name}, which it returns. */
    public Sse until(String name) throws InterruptedException {
        long deadline = System.nanoTime() + END.toNanos();
        while (true) {
            Sse event = next(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            assertThat(event).as("the event " + name + "; seen " + names(seen)).isNotNull();
            if (event.name().equals(name)) {
                return event;
            }
        }
    }

    /** Every event to the end of the stream, those read before included. */
    public List<Sse> all() throws InterruptedException {
        long deadline = System.nanoTime() + END.toNanos();
        while (next(Duration.ofNanos(Math.max(1, deadline - System.nanoTime()))) != null) {
            // reading
        }
        return seen;
    }

    /** The events' names, in order. */
    public static List<String> names(List<Sse> events) {
        return events.stream().map(Sse::name).toList();
    }

    /** {@code server}'s current run's events, from the start to the end of the stream. */
    public static List<Sse> toEnd(UiServer server) throws InterruptedException {
        try (RunEventStream stream = new RunEventStream(server)) {
            return stream.all();
        }
    }

    @Override
    public void close() {
        response.cancel(true);
    }
}
