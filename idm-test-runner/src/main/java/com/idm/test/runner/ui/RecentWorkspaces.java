package com.idm.test.runner.ui;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The start page's recent workspaces: a JSON array of folder paths, most recent first, at most
 * {@value #MAX}, in {@code <user.home>/.idmtest/recent.json}. Only paths: never scenario content or
 * secrets. Reading never creates the file; a file that is not such a list (corrupt) reads as empty
 * and stays as it is until the next change replaces it. A file that cannot be read at all
 * (permissions, I/O) also reads as empty, but a change is then refused rather than replacing the
 * list with one made without it. Every change is written to a temporary file
 * beside it and moved over it, so a crash leaves the old list or the new one. Safe for concurrent
 * requests.
 */
public final class RecentWorkspaces {

    static final int MAX = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path file;

    public RecentWorkspaces(Path file) {
        this.file = file;
    }

    /** {@code <user.home>/.idmtest/recent.json}, with {@code user.home} as it is now. */
    public static RecentWorkspaces inUserHome() {
        return new RecentWorkspaces(Path.of(System.getProperty("user.home"), ".idmtest", "recent.json"));
    }

    /** The folders, most recent first; empty when the file is missing, corrupt or cannot be read. */
    public synchronized List<Path> paths() {
        try {
            return read();
        } catch (IOException | SecurityException e) {
            return List.of();
        }
    }

    /**
     * Puts {@code folder} first, once.
     *
     * @throws UncheckedIOException when the list cannot be read (it is then left as it is) or written
     */
    public synchronized void add(Path folder) {
        List<Path> paths = new ArrayList<>(readForChange());
        paths.remove(folder);
        paths.addFirst(folder);
        write(paths.size() > MAX ? paths.subList(0, MAX) : paths);
    }

    /**
     * Takes {@code folder} off the list; the folder itself is left alone.
     *
     * @throws UncheckedIOException when the list cannot be read (it is then left as it is) or written
     */
    public synchronized void remove(Path folder) {
        List<Path> paths = new ArrayList<>(readForChange());
        if (paths.remove(folder)) {
            write(paths);
        }
    }

    /**
     * The list as {@link #read()} has it. A file that cannot be read (permissions, I/O) is not
     * replaced by a list made without it: the change is refused.
     */
    private List<Path> readForChange() {
        try {
            return read();
        } catch (IOException | SecurityException e) {
            throw new UncheckedIOException("Could not read " + file + "; it is left as it is",
                    e instanceof IOException io ? io : new IOException(e));
        }
    }

    /**
     * The list in the file: empty when there is no file or it is not such a list (corrupt).
     *
     * @throws IOException when the file exists but cannot be read
     */
    private List<Path> read() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        byte[] bytes = Files.readAllBytes(file);
        try {
            JsonNode root = JSON.readTree(bytes);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<Path> paths = new ArrayList<>();
            for (JsonNode entry : root) {
                if (entry.isString()) {
                    Path path = Path.of(entry.asString());
                    if (path.isAbsolute() && !paths.contains(path) && paths.size() < MAX) {
                        paths.add(path);
                    }
                }
            }
            return List.copyOf(paths);
        } catch (JacksonException | InvalidPathException e) {
            return List.of();
        }
    }

    private void write(List<Path> paths) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "recent", ".tmp");
            try {
                Files.write(temporary, JSON.writeValueAsBytes(paths.stream().map(Path::toString).toList()));
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
