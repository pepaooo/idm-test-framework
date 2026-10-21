package com.idm.test.runner.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file watcher keeps watching through failures: a watch service that cannot be made is tried
 * again, an exception while watching starts it again, and lost events (the system's OVERFLOW) tell
 * the tabs to reload. Watches real folders where it can (macOS polls: events take up to ~2.5 s).
 */
@DisplayName("Workbench file watcher")
class FileWatcherTest {

    private static final Duration RETRY = Duration.ofMillis(100);
    private static final Duration WITHIN = Duration.ofSeconds(15);

    @TempDir
    Path temp;

    private FileWatcher watcher;

    @AfterEach
    void stop() {
        if (watcher != null) {
            watcher.stop();
        }
    }

    /** An emitter that keeps the events sent to it (no HTTP response behind it). */
    private static class Captured extends SseEmitter {
        final BlockingQueue<FileWatcher.Event> events = new LinkedBlockingQueue<>();

        @Override
        public void send(SseEventBuilder builder) {
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                if (part.getData() instanceof FileWatcher.Event event) {
                    events.add(event);
                }
            }
        }

        /** The next event of {@code type}, skipping others; fails after {@link #WITHIN}. */
        FileWatcher.Event next(String type) throws InterruptedException {
            long deadline = System.nanoTime() + WITHIN.toNanos();
            while (System.nanoTime() < deadline) {
                FileWatcher.Event event = events.poll(100, TimeUnit.MILLISECONDS);
                if (event != null && event.type().equals(type)) {
                    return event;
                }
            }
            throw new AssertionError("no " + type + " event within " + WITHIN);
        }
    }

    private Path workspace() throws IOException {
        Path ws = temp.resolve("ws");
        Files.createDirectories(ws.resolve("scenarios"));
        Files.createDirectories(ws.resolve("config"));
        return ws.toRealPath();
    }

    private Captured start(Path ws, FileWatcher.Watching watching) {
        watcher = new FileWatcher(new UiSession("t", Locale.ENGLISH, ws), watching, RETRY);
        Captured captured = new Captured();
        watcher.subscribe(captured, ws);
        watcher.start();
        return captured;
    }

    @Test
    @DisplayName("Should try again when the watch service cannot be made, then report changes and say to reload")
    void testRetriesOpen() throws Exception {
        Path ws = workspace();
        AtomicInteger calls = new AtomicInteger();
        Captured captured = start(ws, root -> {
            if (calls.incrementAndGet() <= 2) {
                throw new IOException("too many open files (simulated)");
            }
            return root.getFileSystem().newWatchService();
        });

        // watching starts on the third try; events of the time before may be lost: reload
        assertThat(captured.next("overflow").path()).isEmpty();
        assertThat(calls.get()).isEqualTo(3);
        Files.writeString(ws.resolve("scenarios/new.yaml"), "name: new\n");
        assertThat(captured.next("created").path()).isEqualTo("scenarios/new.yaml");
    }

    @Test
    @DisplayName("Should keep watching after an unexpected exception while watching")
    void testSurvivesException() throws Exception {
        Path ws = workspace();
        AtomicInteger calls = new AtomicInteger();
        Captured captured = start(ws, root -> calls.incrementAndGet() == 1
                ? new FakeWatchService(List.of(), true)
                : root.getFileSystem().newWatchService());

        assertThat(captured.next("overflow").path()).isEmpty();
        assertThat(calls.get()).isEqualTo(2);
        Files.writeString(ws.resolve("scenarios/after.yaml"), "name: after\n");
        assertThat(captured.next("created").path()).isEqualTo("scenarios/after.yaml");
    }

    @Test
    @DisplayName("Should tell the tabs to reload when the system says events were lost (OVERFLOW)")
    void testOverflow() throws Exception {
        Path ws = workspace();
        Captured captured = start(ws, root -> new FakeWatchService(List.of(new OverflowKey()), false));

        FileWatcher.Event event = captured.next("overflow");

        assertThat(event.path()).isEmpty();
    }

    @Test
    @DisplayName("Should end the streams of tabs subscribed for another workspace when the workspace changes")
    void testOtherWorkspaceStreamsEnd() throws Exception {
        Path ws = workspace();
        Path other = Files.createDirectories(temp.resolve("other/scenarios")).getParent().toRealPath();
        UiSession session = new UiSession("t", Locale.ENGLISH, ws);
        watcher = new FileWatcher(session, root -> root.getFileSystem().newWatchService(), RETRY);
        AtomicInteger completed = new AtomicInteger();
        Captured mine = new Captured() {
            @Override
            public void complete() {
                completed.incrementAndGet();
            }
        };
        watcher.subscribe(mine, ws);
        watcher.start();

        session.open(other);

        long deadline = System.nanoTime() + WITHIN.toNanos();
        while (completed.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(completed.get()).isEqualTo(1);
    }

    /** A watch service that hands out the given keys once, then nothing; or fails on its first poll. */
    private static final class FakeWatchService implements WatchService {
        private final BlockingQueue<WatchKey> keys;
        private final boolean failFirst;
        private boolean polled;

        FakeWatchService(List<WatchKey> keys, boolean failFirst) {
            this.keys = new LinkedBlockingQueue<>(keys);
            this.failFirst = failFirst;
        }

        @Override
        public void close() {
        }

        @Override
        public WatchKey poll() {
            return keys.poll();
        }

        @Override
        public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
            if (failFirst && !polled) {
                polled = true;
                throw new IllegalStateException("watch service failed (simulated)");
            }
            WatchKey key = keys.poll();
            if (key == null) {
                unit.sleep(timeout);
            }
            return key;
        }

        @Override
        public WatchKey take() {
            throw new UnsupportedOperationException();
        }
    }

    /** A key with one OVERFLOW event. */
    private static final class OverflowKey implements WatchKey {
        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public List<WatchEvent<?>> pollEvents() {
            return List.of(new WatchEvent<Object>() {
                @Override
                public Kind<Object> kind() {
                    return StandardWatchEventKinds.OVERFLOW;
                }

                @Override
                public int count() {
                    return 1;
                }

                @Override
                public Object context() {
                    return null;
                }
            });
        }

        @Override
        public boolean reset() {
            return true;
        }

        @Override
        public void cancel() {
        }

        @Override
        public Watchable watchable() {
            return null;
        }
    }
}
