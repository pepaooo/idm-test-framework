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
 * secrets. Reading never creates the file; a file that cannot be read as such a list reads as empty
 * and stays as it is until the next change replaces it. Every change is written to a temporary file
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

    /** The folders, most recent first. */
    public synchronized List<Path> paths() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(file));
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
        } catch (IOException | JacksonException | InvalidPathException | SecurityException e) {
            return List.of();
        }
    }

    /** Puts {@code folder} first, once. */
    public synchronized void add(Path folder) {
        List<Path> paths = new ArrayList<>(paths());
        paths.remove(folder);
        paths.addFirst(folder);
        write(paths.size() > MAX ? paths.subList(0, MAX) : paths);
    }

    /** Takes {@code folder} off the list; the folder itself is left alone. */
    public synchronized void remove(Path folder) {
        List<Path> paths = new ArrayList<>(paths());
        if (paths.remove(folder)) {
            write(paths);
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
