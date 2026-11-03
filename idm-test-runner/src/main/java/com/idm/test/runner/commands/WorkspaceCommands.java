package com.idm.test.runner.commands;

import com.idm.test.connector.exec.client.RegisteredCommand;
import com.idm.test.runner.Workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The commands a workspace registers for a profile, as a run binds them, and their approval on this
 * machine (ADR 0008), for the workbench. Binding them starts a Spring context (the same settings, secrets
 * and configuration as a run, through {@link CommandsCommand#load}), so the answer is kept until a file
 * under {@code config/} or {@code secrets.env} changes: the key is a SHA-256 of their names and content.
 * The approvals file is read on every call, so an approval from {@code idmtest commands} in a terminal
 * shows at once.
 */
public final class WorkspaceCommands {

    /** The commands, or why they could not be bound (the settings do not load); never both. */
    public record Loaded(Map<String, RegisteredCommand> commands, String error) {
    }

    /** A command's approval here: approved, changed since its approval, or never approved. */
    public enum Approval {
        APPROVED, CHANGED, NOT_APPROVED;

        public String json() {
            return switch (this) {
                case APPROVED -> "approved";
                case CHANGED -> "changed";
                case NOT_APPROVED -> "not-approved";
            };
        }
    }

    private final BiFunction<Workspace, String, Map<String, RegisteredCommand>> loader;
    private final Supplier<ApprovalStore> store;
    private final Map<String, Loaded> cache = new ConcurrentHashMap<>();

    public WorkspaceCommands(BiFunction<Workspace, String, Map<String, RegisteredCommand>> loader,
                             Supplier<ApprovalStore> store) {
        this.loader = loader;
        this.store = store;
    }

    /** Bound as {@code idmtest run} binds them; approvals in {@code <user.home>} as it is at each call. */
    public static WorkspaceCommands standard() {
        return new WorkspaceCommands(CommandsCommand::load, ApprovalStore::inUserHome);
    }

    /** The profile's commands: kept while the settings files are unchanged. */
    public Loaded load(Path root, String profile) {
        Workspace workspace = new Workspace(root);
        String key = fingerprint(workspace, profile);
        if (key == null) {
            return bind(workspace, profile);
        }
        Loaded loaded = cache.get(key);
        if (loaded == null) {
            loaded = bind(workspace, profile);
            if (cache.size() > 32) {
                cache.clear();
            }
            cache.put(key, loaded);
        }
        return loaded;
    }

    private Loaded bind(Workspace workspace, String profile) {
        try {
            return new Loaded(Map.copyOf(loader.apply(workspace, profile)), null);
        } catch (RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            // the type only: a message may quote a setting's value
            return new Loaded(Map.of(), cause.getClass().getSimpleName());
        }
    }

    /** Whether this machine approved {@code command} as it is now. */
    public Approval approval(Path root, String profile, String name, RegisteredCommand command) {
        ApprovalStore.Approval approval = store.get().approvals(root, profile).get(name);
        if (approval == null) {
            return Approval.NOT_APPROVED;
        }
        return approval.hash().equals(command.hash()) ? Approval.APPROVED : Approval.CHANGED;
    }

    /** Approves {@code hash} for {@code name} on this machine. */
    public void approve(Path root, String profile, String name, String hash) {
        store.get().approve(root, profile, name, hash);
    }

    /**
     * A SHA-256 of the profile and of every file under {@code config/} and {@code secrets.env} (names and
     * content), or null when one cannot be read (then nothing is kept).
     */
    private static String fingerprint(Workspace workspace, String profile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((workspace.root().toAbsolutePath().normalize() + "\u0000" + profile + "\u0000")
                    .getBytes(StandardCharsets.UTF_8));
            List<Path> files;
            if (Files.isDirectory(workspace.config())) {
                try (Stream<Path> walk = Files.walk(workspace.config())) {
                    files = walk.filter(Files::isRegularFile).sorted().toList();
                }
            } else {
                files = List.of();
            }
            for (Path file : Stream.concat(files.stream(), Stream.of(workspace.secretsFile())).toList()) {
                digest.update((file.toString() + "\u0000").getBytes(StandardCharsets.UTF_8));
                if (Files.isRegularFile(file)) {
                    byte[] content = Files.readAllBytes(file);
                    digest.update(Long.toString(content.length).getBytes(StandardCharsets.UTF_8));
                    digest.update(content);
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | UncheckedIOException e) {
            return null;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
