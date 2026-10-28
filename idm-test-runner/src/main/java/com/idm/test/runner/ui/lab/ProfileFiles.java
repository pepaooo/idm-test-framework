package com.idm.test.runner.ui.lab;

import com.idm.test.runner.InitCommand;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        byte[] content = InitCommand.profileTemplate(name).getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(config, ".application-", ".tmp");
        try {
            Files.write(temporary, content);
            // A hard link publishes the complete file with CREATE_NEW semantics: even a concurrent
            // creator cannot make us replace its profile after the case-insensitive check.
            Files.createLink(target, temporary);
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (UnsupportedOperationException | FileSystemException e) {
            // some file systems (network shares, FAT drives) have no hard links
            writeNew(target, content);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    /** Writes {@code content} to a file that must not exist yet; a failed write leaves no file behind. */
    static void writeNew(Path target, byte[] content) throws IOException {
        FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (channel) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }
}
