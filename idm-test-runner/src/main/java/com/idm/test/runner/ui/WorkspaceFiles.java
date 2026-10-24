package com.idm.test.runner.ui;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A workspace's files as the workbench's editor sees them: the tree, reading, saving with a version
 * check and adding scenarios. Paths are workspace-relative with {@code /}; absolute paths, empty,
 * {@code .} and {@code ..} segments, {@code \}, {@code :}, NUL, a {@code .git} segment and anything
 * whose real location (symbolic links resolved) is outside the workspace's real folder are refused
 * ({@link Problem#BAD_PATH}). {@code secrets.env} — in any folder, any case, or behind a link — is
 * never read or written ({@link Problem#SECRETS}); the tree lists it, without its content.
 * <p>
 * A file's version is the SHA-256 of its bytes. A save must carry the version it was read with and
 * only replaces a file that is still at that version, so a file changed on disk is never silently
 * overwritten ({@link ChangedException}); saves of one file are serialized so two with the same
 * version cannot both win. A save writes a temp file in the same folder and moves it over the file
 * (atomically where the file system can), with the content exactly as the UTF-8 bytes of the text
 * sent — line endings and a byte order mark included. Saves never create files: new files come only
 * through {@link #add} and {@link #createScenario}, under {@code scenarios/}, never replacing one.
 * <p>
 * The tree has {@code scenarios/} and {@code config/} (all their levels) and the workspace's
 * top-level files; it leaves out every other folder ({@code reports/}, {@code drivers/}, {@code .git},
 * build output) and every entry whose name starts with a dot, as the runner does for scenarios. Only
 * reading the tree never writes anything.
 */
public final class WorkspaceFiles {

    /** The largest file the workbench reads, saves or adds: 2 MB. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final String SCENARIOS = "scenarios";
    private static final String CONFIG = "config";
    private static final String SECRETS_FILE = "secrets.env";
    private static final String SCENARIO_KIND = "scenario";
    private static final String SECRETS_KIND = "secrets";
    /** One lock per file (by real path) for every workspace of this program: saves of one file wait in turn. */
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Path root;

    /** @param root the workspace folder */
    public WorkspaceFiles(Path root) {
        this.root = root;
    }

    /** What is wrong with a request for a file. */
    public enum Problem { BAD_PATH, BAD_NAME, SECRETS, NOT_FOUND, TOO_LARGE, NOT_TEXT, NOT_YAML, EXISTS }

    /** A file request that cannot be done; {@link #subject()} is the path or file name it was about. */
    public static final class FileException extends RuntimeException {

        private final Problem problem;
        private final String subject;

        FileException(Problem problem, String subject) {
            super(problem + ": " + subject);
            this.problem = problem;
            this.subject = subject;
        }

        public Problem problem() {
            return problem;
        }

        public String subject() {
            return subject;
        }
    }

    /** A save whose version is not the file's any more; carries the version on disk. */
    public static final class ChangedException extends RuntimeException {

        private final String currentVersion;

        ChangedException(String path, String currentVersion) {
            super("changed on disk: " + path);
            this.currentVersion = currentVersion;
        }

        public String currentVersion() {
            return currentVersion;
        }
    }

    /**
     * A tree entry. {@code kind} is {@code scenario}, {@code config}, {@code secrets} or {@code other}
     * for files and null for folders; {@code lastResult} ({@code passed}, {@code finding},
     * {@code setup}) only for scenarios that ran; {@code children} only for folders.
     */
    public record Node(String name, String path, boolean dir, String kind, String lastResult, List<Node> children) {
    }

    public record Content(String content, String version) {
    }

    /** A file to add: the name it came with (only its base name is used) and its bytes. */
    public record Upload(String filename, byte[] bytes) {
    }

    /** The version of {@code bytes}: their SHA-256, as 64 lower-case hex digits. */
    public static String version(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java", e);
        }
    }

    // --- tree -----------------------------------------------------------------------------------

    /** The workspace as the file tree shows it; the root node's path is empty. */
    public Node tree() {
        List<Node> children = new ArrayList<>();
        for (Path entry : entries(root)) {
            String name = entry.getFileName().toString();
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                if (name.equals(SCENARIOS) || name.equals(CONFIG)) {
                    children.add(folder(entry, name));
                }
            } else if (Files.isRegularFile(entry)) {
                children.add(file(name, name));
            }
        }
        Path name = root.getFileName();
        Node tree = new Node(name == null ? root.toString() : name.toString(), "", true, null, null, sorted(children));
        Set<String> scenarios = new HashSet<>();
        collectScenarios(tree, scenarios);
        return withResults(tree, LastRuns.lastOutcomes(root, scenarios));
    }

    private Node folder(Path folder, String path) {
        List<Node> children = new ArrayList<>();
        for (Path entry : entries(folder)) {
            String name = entry.getFileName().toString();
            String childPath = path + "/" + name;
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                children.add(folder(entry, childPath));
            } else if (Files.isRegularFile(entry)) {
                children.add(file(name, childPath));
            }
        }
        return new Node(folder.getFileName().toString(), path, true, null, null, sorted(children));
    }

    private static Node file(String name, String path) {
        String kind;
        if (name.equalsIgnoreCase(SECRETS_FILE)) {
            kind = SECRETS_KIND;
        } else if (path.startsWith(SCENARIOS + "/") && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
            kind = SCENARIO_KIND;
        } else if (path.startsWith(CONFIG + "/")) {
            kind = "config";
        } else {
            kind = "other";
        }
        return new Node(name, path, false, kind, null, null);
    }

    /** The source names (path under {@code scenarios/}) of the tree's scenarios. */
    private static void collectScenarios(Node node, Set<String> into) {
        if (SCENARIO_KIND.equals(node.kind())) {
            into.add(node.path().substring(SCENARIOS.length() + 1));
        }
        if (node.children() != null) {
            node.children().forEach(child -> collectScenarios(child, into));
        }
    }

    private static Node withResults(Node node, Map<String, LastRuns.Outcome> outcomes) {
        if (node.children() != null) {
            return new Node(node.name(), node.path(), true, null, null,
                    node.children().stream().map(child -> withResults(child, outcomes)).toList());
        }
        if (!SCENARIO_KIND.equals(node.kind())) {
            return node;
        }
        LastRuns.Outcome outcome = outcomes.get(node.path().substring(SCENARIOS.length() + 1));
        return outcome == null ? node
                : new Node(node.name(), node.path(), false, node.kind(), outcome.json(), null);
    }

    /** The entries of {@code folder} whose names do not start with a dot; none when it cannot be read. */
    private static List<Path> entries(Path folder) {
        List<Path> list = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) {
                if (!entry.getFileName().toString().startsWith(".")) {
                    list.add(entry);
                }
            }
        } catch (IOException | UncheckedIOException | SecurityException e) {
            return List.of();
        }
        return list;
    }

    /** Folders first, then files, each by name ignoring case. */
    private static List<Node> sorted(List<Node> nodes) {
        nodes.sort(Comparator.comparing((Node n) -> !n.dir())
                .thenComparing(n -> n.name().toLowerCase(Locale.ROOT)).thenComparing(Node::name));
        return List.copyOf(nodes);
    }

    // --- read and save --------------------------------------------------------------------------

    /** The file at {@code path}, as text, with its version. */
    public Content read(String path) {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new FileException(Problem.NOT_FOUND, path);
        }
        try {
            if (Files.size(file) > MAX_BYTES) {
                throw new FileException(Problem.TOO_LARGE, path);
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_BYTES) {
                throw new FileException(Problem.TOO_LARGE, path);
            }
            return new Content(decode(bytes, path), version(bytes));
        } catch (NoSuchFileException e) {
            throw new FileException(Problem.NOT_FOUND, path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Replaces the file at {@code path} with {@code content} if it is still at {@code version}.
     *
     * @return the new version
     * @throws ChangedException         when the file on disk is not at {@code version}
     * @throws IllegalArgumentException when {@code version} or {@code content} is null
     */
    public String save(String path, String content, String version) {
        if (version == null || content == null) {
            throw new IllegalArgumentException(version == null ? "version" : "content");
        }
        Path file = resolve(path);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new FileException(Problem.TOO_LARGE, path);
        }
        ReentrantLock lock = LOCKS.computeIfAbsent(file.toString(), k -> new ReentrantLock());
        lock.lock();
        try {
            if (!Files.isRegularFile(file)) {
                throw new FileException(Problem.NOT_FOUND, path);
            }
            String current = version(Files.readAllBytes(file));
            if (!current.equals(version)) {
                throw new ChangedException(path, current);
            }
            replace(file, bytes);
            return version(bytes);
        } catch (NoSuchFileException e) {
            throw new FileException(Problem.NOT_FOUND, path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    /** Writes {@code bytes} to a temp file next to {@code file}, with its permissions, and moves it over it. */
    private static void replace(Path file, byte[] bytes) throws IOException {
        writeAtomically(file, bytes, null);
    }

    /**
     * Writes {@code bytes} to a temp file next to {@code file} and moves it over it (atomically where
     * the file system can). An existing file's permissions are kept; a new file gets
     * {@code newFilePermissions} where the file system has POSIX permissions (null: the temp file's
     * own, owner-only). The temp file is removed when anything fails. The caller serializes writers,
     * e.g. with {@link #locked}.
     */
    public static void writeAtomically(Path file, byte[] bytes, Set<PosixFilePermission> newFilePermissions)
            throws IOException {
        Path folder = file.getParent();
        Path temp = Files.createTempFile(folder, "." + file.getFileName() + ".", ".tmp");
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(temp, PosixFileAttributeView.class);
            if (posix != null && Files.exists(file)) {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file));
            } else if (posix != null && newFilePermissions != null) {
                Files.setPosixFilePermissions(temp, newFilePermissions);
            }
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Runs {@code action} holding the lock that saves of {@code file} take, so a write by another
     * part of the workbench (the lab panel) and an editor save of the same file wait in turn.
     */
    public static <T> T locked(Path file, IoAction<T> action) throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        Path real = Files.exists(folder) ? folder.toRealPath().resolve(file.getFileName().toString()) : file;
        if (Files.exists(real)) {
            real = real.toRealPath();
        }
        ReentrantLock lock = LOCKS.computeIfAbsent(real.toString(), k -> new ReentrantLock());
        lock.lock();
        try {
            return action.run();
        } finally {
            lock.unlock();
        }
    }

    /** What {@link #locked} runs. */
    @FunctionalInterface
    public interface IoAction<T> {
        T run() throws IOException;
    }

    // --- add ------------------------------------------------------------------------------------

    /**
     * Copies {@code uploads} into {@code scenarios/}, each under its base name (control characters
     * dropped, {@code < > : " | ? *} replaced by {@code _}, leading dots and spaces removed, the
     * extension in lower case) or, when that is taken, {@code name (2).yaml}, {@code name (3).yaml}, ….
     * Every upload is checked first (a {@code .yaml}/{@code .yml} name, at most 2 MB, UTF-8 text):
     * when one is refused, none is added.
     *
     * @return the added files' workspace-relative paths, in the order given
     */
    public List<String> add(List<Upload> uploads) {
        List<String> names = new ArrayList<>();
        for (Upload upload : uploads) {
            String name = sanitize(upload.filename());
            if (upload.bytes().length > MAX_BYTES) {
                throw new FileException(Problem.TOO_LARGE, name);
            }
            decode(upload.bytes(), name);
            names.add(name);
        }
        Path folder = resolve(SCENARIOS);
        if (!Files.isDirectory(folder)) {
            throw new FileException(Problem.NOT_FOUND, SCENARIOS);
        }
        List<String> added = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            added.add(SCENARIOS + "/" + create(folder, names.get(i), uploads.get(i).bytes()));
        }
        return added;
    }

    /** Creates {@code name} — or the first free {@code stem (n).ext} — in {@code folder}; never replaces a file. */
    private static String create(Path folder, String name, byte[] bytes) {
        int dot = name.lastIndexOf('.');
        String stem = name.substring(0, dot);
        String extension = name.substring(dot);
        for (int n = 1; ; n++) {
            String candidate = n == 1 ? name : stem + " (" + n + ")" + extension;
            try {
                Files.write(folder.resolve(candidate), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return candidate;
            } catch (FileAlreadyExistsException e) {
                // taken (also by a dangling link): try the next number
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static String sanitize(String filename) {
        String name = filename == null ? "" : filename;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        StringBuilder clean = new StringBuilder();
        name.codePoints().forEach(c -> {
            if (Character.isISOControl(c)) {
                return;
            }
            clean.appendCodePoint("<>:\"|?*".indexOf(c) >= 0 ? '_' : c);
        });
        name = clean.toString().strip();
        String lower = name.toLowerCase(Locale.ROOT);
        String extension = lower.endsWith(".yaml") ? ".yaml" : lower.endsWith(".yml") ? ".yml" : null;
        if (extension == null) {
            throw new FileException(Problem.NOT_YAML, filename == null ? "" : filename);
        }
        String stem = name.substring(0, name.length() - extension.length());
        int start = 0;
        while (start < stem.length() && (stem.charAt(start) == '.' || Character.isWhitespace(stem.charAt(start)))) {
            start++;
        }
        stem = stem.substring(start).strip();
        if (stem.isEmpty() || stem.endsWith(".")) {
            throw new FileException(Problem.BAD_PATH, filename);
        }
        return stem + extension;
    }

    // --- create ---------------------------------------------------------------------------------

    /**
     * Creates the scenario file {@code path} with {@code content}, written exactly as its UTF-8 bytes;
     * never replaces a file, also when two creates of one name race ({@link Problem#EXISTS}). The file
     * name (after the last {@code /}) must not be empty, start with a dot or contain {@code \},
     * {@code ..} or {@code :} ({@link Problem#BAD_NAME}); {@code path} must end in {@code .yaml} or
     * {@code .yml} and pass the checks of every path, under {@code scenarios/}, in a folder that exists
     * ({@link Problem#BAD_PATH}; folders are not created). At most {@link #MAX_BYTES}.
     *
     * @return the content and its version
     * @throws IllegalArgumentException when {@code content} is null
     */
    public Content createScenario(String path, String content) {
        if (content == null) {
            throw new IllegalArgumentException("content");
        }
        if (path == null || path.isEmpty()) {
            throw new FileException(Problem.BAD_PATH, "");
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        String stem = name.endsWith(".yaml") ? name.substring(0, name.length() - 5)
                : name.endsWith(".yml") ? name.substring(0, name.length() - 4) : name;
        if (stem.isEmpty() || name.startsWith(".") || name.contains("\\") || name.contains("..")
                || name.contains(":")) {
            throw new FileException(Problem.BAD_NAME, name);
        }
        if (stem.equals(name)) {
            throw new FileException(Problem.BAD_PATH, path);
        }
        underScenarios(path);
        Path file = resolve(path);
        if (!Files.isDirectory(file.getParent())) {
            throw new FileException(Problem.BAD_PATH, path);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new FileException(Problem.TOO_LARGE, path);
        }
        ReentrantLock lock = LOCKS.computeIfAbsent(file.toString(), k -> new ReentrantLock());
        lock.lock();
        try {
            OutputStream out;
            try {
                out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) {
                throw new FileException(Problem.EXISTS, path);
            }
            try (out) {
                out.write(bytes);
            } catch (IOException e) {
                Files.deleteIfExists(file); // ours since CREATE_NEW succeeded: leave no half-written file
                throw e;
            }
            return new Content(content, version(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    // --- paths ----------------------------------------------------------------------------------

    /**
     * The part under {@code scenarios/} of the workspace-relative {@code path} — {@code scenarios} itself
     * gives {@code ""} — after the same checks as every other path ({@link Problem#BAD_PATH}, also for a
     * path outside {@code scenarios/}). Whether a scenario or folder is there is the caller's to check.
     */
    public String underScenarios(String path) {
        resolve(path);
        if (path.equals(SCENARIOS)) {
            return "";
        }
        if (!path.startsWith(SCENARIOS + "/")) {
            throw new FileException(Problem.BAD_PATH, path);
        }
        return path.substring(SCENARIOS.length() + 1);
    }

    /**
     * The real location of the workspace-relative {@code path}: symbolic links of the file and its
     * existing folders resolved, the part that does not exist yet appended as given.
     */
    private Path resolve(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":")
                || path.indexOf('\0') >= 0) {
            throw new FileException(Problem.BAD_PATH, path == null ? "" : path);
        }
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")) {
                throw new FileException(Problem.BAD_PATH, path);
            }
        }
        if (parts[parts.length - 1].equalsIgnoreCase(SECRETS_FILE)) {
            throw new FileException(Problem.SECRETS, path);
        }
        try {
            Path realRoot = root.toRealPath();
            Path target = root.resolve(path);
            Path existing = target;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                throw new FileException(Problem.BAD_PATH, path);
            }
            Path real = existing.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw new FileException(Problem.BAD_PATH, path);
            }
            if (existing.equals(target) && real.getFileName() != null
                    && real.getFileName().toString().equalsIgnoreCase(SECRETS_FILE)) {
                throw new FileException(Problem.SECRETS, path);
            }
            return real.resolve(existing.relativize(target).toString());
        } catch (InvalidPathException e) {
            throw new FileException(Problem.BAD_PATH, path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code bytes} as UTF-8 exactly (a byte order mark stays as U+FEFF), or {@link Problem#NOT_TEXT}. */
    private static String decode(byte[] bytes, String subject) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new FileException(Problem.NOT_TEXT, subject);
        }
    }
}
