package com.idm.test.runner;

import com.idm.test.engine.spi.Message;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** A folder of scenarios, settings and reports that {@code idmtest} works in. */
public record Workspace(Path root) {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Opens {@code dir} as a workspace; it must contain a {@code scenarios/} folder. */
    public static Workspace open(Path dir) {
        Path root = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("scenarios"))) {
            throw new UsageException(Message.of("runner.workspace.notWorkspace", root.toString()));
        }
        return new Workspace(root);
    }

    public Path scenarios() {
        return root.resolve("scenarios");
    }

    public Path config() {
        return root.resolve("config");
    }

    public Path secretsFile() {
        return root.resolve("secrets.env");
    }

    public Path drivers() {
        return root.resolve("drivers");
    }

    public Path reports() {
        return root.resolve("reports");
    }

    public Path profileFile(String profile) {
        return config().resolve("application-" + profile + ".yml");
    }

    /** Fails with {@code runner.profile.missing} when the profile's settings file does not exist. */
    public static void requireProfile(Workspace ws, String profile) {
        checkProfileName(profile);
        if (!Files.isRegularFile(ws.profileFile(profile))) {
            throw new UsageException(Message.of("runner.profile.missing", profile));
        }
    }

    private static void checkProfileName(String profile) {
        if (profile.contains("/") || profile.contains("\\") || profile.contains("..")) {
            throw new UsageException(Message.of("runner.profile.invalid", profile));
        }
    }

    /** Creates {@code reports/<yyyyMMdd-HHmmss>-<profile>/}, adding {@code -2}, {@code -3} when it exists. */
    public Path newRunFolder(String profile, Clock clock) {
        checkProfileName(profile);
        String id = RUN_ID.format(LocalDateTime.now(clock)) + "-" + profile;
        try {
            Files.createDirectories(reports());
            Path folder = reports().resolve(id);
            for (int n = 2; ; n++) {
                try {
                    return Files.createDirectory(folder);
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    folder = reports().resolve(id + "-" + n);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
