package com.idm.test.runner.commands;

import com.idm.test.engine.spi.CommandApproval;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The commands approved on this machine (ADR 0008), in {@code <user.home>/.idmtest/command-approvals.json}:
 * outside every workspace, so nothing that arrives through Git can approve a command. An approval is
 * for one workspace (by its real path, so a link to it is another workspace), one profile and one
 * command, and holds the hash of the definition it approved; a changed definition is not approved.
 * <p>
 * A missing file, or one that is not such a list (corrupt), approves nothing; reading never creates the
 * file. A file that cannot be read approves nothing, and a change is then refused rather than replacing
 * it. Every change is written beside the file and moved over it. Safe for concurrent use.
 */
public final class ApprovalStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One approval. */
    public record Approval(String workspace, String profile, String command, String hash, Instant approvedAt) {
    }

    private final Path file;
    private final Clock clock;

    public ApprovalStore(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** {@code <user.home>/.idmtest/command-approvals.json}, with {@code user.home} as it is now. */
    public static ApprovalStore inUserHome() {
        return new ApprovalStore(Path.of(System.getProperty("user.home"), ".idmtest", "command-approvals.json"),
                Clock.systemUTC());
    }

    public Path file() {
        return file;
    }

    /** The approvals of {@code workspace} for {@code profile}, by command; empty when nothing can be read. */
    public synchronized Map<String, Approval> approvals(Path workspace, String profile) {
        String key = key(workspace);
        Map<String, Approval> found = new LinkedHashMap<>();
        for (Approval approval : readQuietly()) {
            if (approval.workspace().equals(key) && approval.profile().equals(profile)) {
                found.put(approval.command(), approval);
            }
        }
        return found;
    }

    /** Whether this exact definition of {@code command} is approved for {@code workspace} and {@code profile}. */
    public boolean approved(Path workspace, String profile, String command, String hash) {
        Approval approval = approvals(workspace, profile).get(command);
        return approval != null && approval.hash().equals(hash);
    }

    /** The approvals of {@code workspace} and {@code profile}, as the steps and the guard ask for them. */
    public CommandApproval forWorkspace(Path workspace, String profile) {
        return (command, hash) -> approved(workspace, profile, command, hash);
    }

    /** Approves {@code hash} as the definition of {@code command}, replacing an earlier approval of it. */
    public synchronized void approve(Path workspace, String profile, String command, String hash) {
        String key = key(workspace);
        List<Approval> approvals = new ArrayList<>(readForChange());
        approvals.removeIf(a -> a.workspace().equals(key) && a.profile().equals(profile) && a.command().equals(command));
        approvals.add(new Approval(key, profile, command, hash, clock.instant()));
        write(approvals);
    }

    /** Removes the approval of {@code command}; returns whether there was one. */
    public synchronized boolean revoke(Path workspace, String profile, String command) {
        String key = key(workspace);
        List<Approval> approvals = new ArrayList<>(readForChange());
        boolean removed = approvals.removeIf(a -> a.workspace().equals(key) && a.profile().equals(profile)
                && a.command().equals(command));
        if (removed) {
            write(approvals);
        }
        return removed;
    }

    /** The workspace's real path: a symbolic link to an approved workspace is not that workspace. */
    static String key(Path workspace) {
        try {
            return workspace.toRealPath().toString();
        } catch (IOException e) {
            return workspace.toAbsolutePath().normalize().toString();
        }
    }

    private List<Approval> readQuietly() {
        try {
            return read();
        } catch (IOException | SecurityException e) {
            return List.of();
        }
    }

    private List<Approval> readForChange() {
        try {
            return read();
        } catch (IOException | SecurityException e) {
            throw new UncheckedIOException("Could not read " + file + "; it is left as it is",
                    e instanceof IOException io ? io : new IOException(e));
        }
    }

    /** @throws IOException when the file exists but cannot be read */
    private List<Approval> read() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        byte[] bytes = Files.readAllBytes(file);
        try {
            JsonNode root = JSON.readTree(bytes);
            JsonNode list = root == null ? null : root.get("approvals");
            if (list == null || !list.isArray()) {
                return List.of();
            }
            List<Approval> approvals = new ArrayList<>();
            for (JsonNode entry : list) {
                String workspace = text(entry, "workspace");
                String profile = text(entry, "profile");
                String command = text(entry, "command");
                String hash = text(entry, "hash");
                if (workspace == null || profile == null || command == null || hash == null
                        || !hash.matches("[0-9a-f]{64}")) {
                    continue;
                }
                Instant at;
                try {
                    at = Instant.parse(Objects.requireNonNullElse(text(entry, "approvedAt"), ""));
                } catch (RuntimeException e) {
                    at = Instant.EPOCH;
                }
                approvals.add(new Approval(workspace, profile, command, hash, at));
            }
            return List.copyOf(approvals);
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private void write(List<Approval> approvals) {
        ObjectNode root = JSON.createObjectNode();
        root.put("note", "Commands approved on this machine (idmtest commands approve). Each holds the hash of the "
                + "definition it approved; a changed definition needs a new approval.");
        ArrayNode list = root.putArray("approvals");
        for (Approval approval : approvals) {
            ObjectNode entry = list.addObject();
            entry.put("workspace", approval.workspace());
            entry.put("profile", approval.profile());
            entry.put("command", approval.command());
            entry.put("hash", approval.hash());
            entry.put("approvedAt", approval.approvedAt().toString());
        }
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "command-approvals", ".tmp");
            try {
                Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
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
