package com.idm.test.runner.ui.lab;

import com.idm.test.runner.InitCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/** Creates a profile from the commented init starter without changing other workspace files. */
public final class ProfileFiles {

    private ProfileFiles() {
    }

    public static Path create(Path workspaceRoot, String name) throws IOException {
        if (name == null || name.isBlank() || name.contains("..") || !name.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid profile name");
        }
        Path config = workspaceRoot.resolve("config");
        if (!Files.isDirectory(config) || !config.toRealPath().startsWith(workspaceRoot.toRealPath())) {
            throw new IOException("config directory is missing or outside workspace");
        }
        Path target = config.resolve("application-" + name + ".yml");
        try (DirectoryStream<Path> files = Files.newDirectoryStream(config, "application-*.yml")) {
            for (Path file : files) {
                String existing = file.getFileName().toString();
                String candidate = existing.substring("application-".length(), existing.length() - ".yml".length());
                if (candidate.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                    throw new FileAlreadyExistsException(file.toString());
                }
            }
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        Path temporary = Files.createTempFile(config, ".application-", ".tmp");
        try {
            Files.writeString(temporary, InitCommand.profileTemplate(name), StandardCharsets.UTF_8);
            Files.move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }
}
