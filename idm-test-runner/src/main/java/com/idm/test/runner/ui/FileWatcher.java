package com.idm.test.runner.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Tells the open browser tabs when a file of the current workspace changes on disk — another
 * editor, {@code git pull}, the CLI — as Server-Sent Events {@code {"type":
 * "changed"|"created"|"deleted", "path": "<workspace-relative>"}}; events never carry content.
 * <p>
 * One {@link WatchService} for the open workspace, over {@code scenarios/} and {@code config/} at
 * every level (folders created later are added as they appear; names starting with a dot, such as
 * the temp files of a save, are ignored). Events of one path are coalesced until it has been quiet
 * for {@link #QUIET}; the type is then decided by the file: gone → {@code deleted}; there and first
 * seen being created → {@code created}; otherwise {@code changed}. When the session's workspace
 * changes, the watcher moves to the new one. Subscribers are dropped when their connection
 * completes, fails or times out; a comment every {@link #HEARTBEAT} finds dead ones. Runs on one
 * daemon thread, from the server's start to its stop.
 * <p>
 * The JDK's WatchService on macOS polls; this asks for its highest sensitivity (2 s) through the
 * JDK-internal modifier when it is there, so a change reaches the browser within about 2.5 s there,
 * at once on Linux and Windows.
 */
@Component
public class FileWatcher implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(FileWatcher.class);
    static final Duration QUIET = Duration.ofMillis(200);
    static final Duration HEARTBEAT = Duration.ofSeconds(20);
    private static final long SUBSCRIPTION_TIMEOUT = Duration.ofMinutes(30).toMillis();
    private static final List<String> WATCHED = List.of("scenarios", "config");
    private static final WatchEvent.Modifier[] SENSITIVITY = sensitivity();

    private final UiSession session;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private Thread thread;

    public FileWatcher(UiSession session) {
        this.session = session;
    }

    /** An event as the browser gets it. */
    public record Event(String type, String path) {
    }

    /** A new subscriber of the current workspace's (and later workspaces') file events. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SUBSCRIPTION_TIMEOUT);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        try {
            emitter.send(SseEmitter.event().comment("watching"));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    // --- lifecycle ------------------------------------------------------------------------------

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().daemon().name("idmtest-file-watcher").start(this::loop);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // --- the watcher thread ---------------------------------------------------------------------

    private static final class Pending {
        final boolean firstCreated;
        long due;

        Pending(boolean firstCreated, long due) {
            this.firstCreated = firstCreated;
            this.due = due;
        }
    }

    private void loop() {
        Path watched = null;
        WatchService service = null;
        Map<WatchKey, Path> keys = new HashMap<>();
        Map<Path, Pending> pending = new LinkedHashMap<>();
        long nextHeartbeat = System.nanoTime() + HEARTBEAT.toNanos();
        try {
            while (running) {
                Path wanted = session.workspace();
                if (!Objects.equals(wanted, watched)) {
                    close(service);
                    keys.clear();
                    pending.clear();
                    service = wanted == null ? null : open(wanted, keys);
                    watched = wanted;
                }
                if (service == null) {
                    Thread.sleep(100);
                } else {
                    WatchKey key = service.poll(50, TimeUnit.MILLISECONDS);
                    while (key != null) {
                        handle(watched, key, keys, pending, service);
                        key = service.poll();
                    }
                }
                flush(watched, pending);
                if (System.nanoTime() - nextHeartbeat >= 0) {
                    broadcast(SseEmitter.event().comment(""));
                    nextHeartbeat = System.nanoTime() + HEARTBEAT.toNanos();
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // stopping
        } catch (RuntimeException e) {
            LOG.warn("The workbench stopped watching the workspace's files", e);
        } finally {
            close(service);
        }
    }

    /** A WatchService on {@code root}'s top level (for scenarios/ and config/ appearing) and both folders. */
    private static WatchService open(Path root, Map<WatchKey, Path> keys) {
        try {
            WatchService service = root.getFileSystem().newWatchService();
            register(service, root, keys);
            for (String folder : WATCHED) {
                registerTree(service, root.resolve(folder), keys, null, root);
            }
            return service;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cannot watch the workspace {} for changes: {}", root, e.toString());
            return null;
        }
    }

    /**
     * Registers {@code folder} and its sub-folders (not following links, leaving out dot-names);
     * when {@code found} is not null, the files met are added to it as created.
     */
    private static void registerTree(WatchService service, Path folder, Map<WatchKey, Path> keys,
                                     Map<Path, Pending> found, Path root) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        register(service, folder, keys);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    registerTree(service, entry, keys, found, root);
                } else if (found != null) {
                    found.putIfAbsent(root.relativize(entry), new Pending(true, due()));
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable: watched as far as it could be
        }
    }

    private static void register(WatchService service, Path folder, Map<WatchKey, Path> keys) {
        try {
            WatchKey key = folder.register(service, new WatchEvent.Kind<?>[] {StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY}, SENSITIVITY);
            keys.put(key, folder);
        } catch (IOException | RuntimeException e) {
            LOG.debug("Cannot watch {}: {}", folder, e.toString());
        }
    }

    private static void handle(Path root, WatchKey key, Map<WatchKey, Path> keys, Map<Path, Pending> pending,
                               WatchService service) {
        Path folder = keys.get(key);
        for (WatchEvent<?> event : key.pollEvents()) {
            if (folder == null || !(event.context() instanceof Path name)
                    || name.toString().startsWith(".")) {
                continue; // OVERFLOW, or a dot-name
            }
            Path changed = folder.resolve(name);
            Path relative = root.relativize(changed);
            if (folder.equals(root)) {
                // the top level is watched only for scenarios/ or config/ appearing
                if (WATCHED.contains(name.toString()) && event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerTree(service, changed, keys, pending, root);
                }
                continue;
            }
            boolean created = event.kind() == StandardWatchEventKinds.ENTRY_CREATE;
            if (created && Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS)) {
                registerTree(service, changed, keys, pending, root);
            }
            Pending entry = pending.get(relative);
            if (entry == null) {
                pending.put(relative, new Pending(created, due()));
            } else {
                entry.due = due();
            }
        }
        if (!key.reset()) {
            keys.remove(key);
        }
    }

    private static long due() {
        return System.nanoTime() + QUIET.toNanos();
    }

    /** Sends the events whose paths have been quiet for {@link #QUIET}. */
    private void flush(Path root, Map<Path, Pending> pending) {
        long now = System.nanoTime();
        for (Iterator<Map.Entry<Path, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, Pending> entry = it.next();
            if (now - entry.getValue().due < 0) {
                continue;
            }
            it.remove();
            boolean exists = Files.exists(root.resolve(entry.getKey()), LinkOption.NOFOLLOW_LINKS);
            String type = !exists ? "deleted" : entry.getValue().firstCreated ? "created" : "changed";
            String path = entry.getKey().toString().replace('\\', '/');
            broadcast(SseEmitter.event().data(new Event(type, path), MediaType.APPLICATION_JSON));
        }
    }

    private void broadcast(SseEmitter.SseEventBuilder event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    private static void close(WatchService service) {
        if (service != null) {
            try {
                service.close();
            } catch (IOException e) {
                // closing anyway
            }
        }
    }

    /**
     * {@code com.sun.nio.file.SensitivityWatchEventModifier.HIGH} when this Java has it (it is
     * JDK-internal and marked for removal, hence the reflection); without it, no modifier.
     */
    private static WatchEvent.Modifier[] sensitivity() {
        try {
            Class<?> type = Class.forName("com.sun.nio.file.SensitivityWatchEventModifier");
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals("HIGH")) {
                    return new WatchEvent.Modifier[] {(WatchEvent.Modifier) constant};
                }
            }
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            // not on this Java
        }
        return new WatchEvent.Modifier[0];
    }
}
