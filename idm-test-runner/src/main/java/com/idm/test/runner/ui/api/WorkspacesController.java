package com.idm.test.runner.ui.api;

import com.idm.test.engine.spi.Message;
import com.idm.test.runner.InitCommand;
import com.idm.test.runner.UsageException;
import com.idm.test.runner.Workspace;
import com.idm.test.runner.ui.FolderBrowser;
import com.idm.test.runner.ui.GitTool;
import com.idm.test.runner.ui.LastRuns;
import com.idm.test.runner.ui.RecentWorkspaces;
import com.idm.test.runner.ui.UiSession;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The start page: recent workspaces, create one (as {@code idmtest init} does, optionally with
 * {@code git init}), open one, and the folder picker. GETs only read; changes are POST and DELETE.
 * Paths in requests must be absolute; paths in answers are absolute and normalized.
 */
@RestController
class WorkspacesController {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspacesController.class);
    private static final String PROFILE_PREFIX = "application-";
    private static final String PROFILE_SUFFIX = ".yml";

    private final UiSession session;
    private final RecentWorkspaces recent;
    private final GitTool git;

    WorkspacesController(UiSession session, RecentWorkspaces recent, GitTool git) {
        this.session = session;
        this.recent = recent;
        this.git = git;
    }

    /** A recent workspace; {@code lastRun} is null when it never ran or no longer exists. */
    record Recent(String name, String path, boolean exists, LastRuns.LastRun lastRun) {
    }

    record CreateRequest(String name, String parent, Boolean includeExample, Boolean gitInit) {
    }

    /** The new workspace; {@code warning} is null unless something optional (git init) did not work. */
    record Created(String path, ApiError.Body warning) {
    }

    record OpenRequest(String path) {
    }

    record Opened(String path, List<String> profiles) {
    }

    @GetMapping("/api/workspaces/recent")
    List<Recent> recent() {
        List<Recent> list = new ArrayList<>();
        for (Path path : recent.paths()) {
            boolean exists = Files.isDirectory(path);
            Path name = path.getFileName();
            list.add(new Recent(name == null ? path.toString() : name.toString(), path.toString(), exists,
                    exists ? LastRuns.newest(path).orElse(null) : null));
        }
        return list;
    }

    @PostMapping("/api/workspaces")
    Created create(@RequestBody CreateRequest request, HttpServletRequest http) {
        String name = request.name();
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/")
                || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0
                || name.strip().length() != name.length()) {
            throw ApiError.badRequest("runner.ui.workspace.badName", name == null ? "" : name);
        }
        Path parent = absolute(request.parent());
        if (parent == null || !Files.isDirectory(parent)) {
            throw ApiError.badRequest("runner.ui.workspace.badParent", String.valueOf(request.parent()));
        }
        Path target;
        try {
            target = parent.resolve(name).normalize();
        } catch (InvalidPathException e) {
            throw ApiError.badRequest("runner.ui.workspace.badName", name);
        }
        if (!parent.equals(target.getParent())) {
            // a name that is a path of its own (Windows "C:x") must not create the workspace elsewhere
            throw ApiError.badRequest("runner.ui.workspace.badName", name);
        }
        if (Files.exists(target) && !isEmptyFolder(target)) {
            throw ApiError.badRequest("runner.ui.workspace.notEmpty", target.toString());
        }
        try {
            InitCommand.create(target, !Boolean.FALSE.equals(request.includeExample()));
        } catch (UncheckedIOException e) {
            throw new ApiError(HttpStatus.INTERNAL_SERVER_ERROR,
                    Message.of("runner.ui.workspace.createFailed", target.toString(), reason(e.getCause())));
        }
        ApiError.Body warning = null;
        if (Boolean.TRUE.equals(request.gitInit()) && !git.init(target)) {
            warning = ApiError.Body.of(Message.of("runner.ui.workspace.gitFailed", target.toString()),
                    ApiError.locale(http.getHeader("Accept-Language"), session.locale()));
        }
        remember(target);
        return new Created(target.toString(), warning);
    }

    @PostMapping("/api/workspaces/open")
    Opened open(@RequestBody OpenRequest request) {
        Path path = absolute(request.path());
        if (path == null) {
            throw ApiError.badRequest("runner.ui.workspace.notWorkspace", String.valueOf(request.path()));
        }
        Workspace workspace;
        try {
            workspace = Workspace.open(path);
        } catch (UsageException e) {
            throw ApiError.badRequest("runner.ui.workspace.notWorkspace", path.toString());
        }
        session.open(workspace.root());
        remember(workspace.root());
        return new Opened(workspace.root().toString(), profiles(workspace));
    }

    @DeleteMapping("/api/workspaces/recent")
    ResponseEntity<Void> forget(@RequestParam String path) {
        Path folder = absolute(path);
        if (folder == null) {
            throw ApiError.badRequest("runner.ui.request.invalid", "path");
        }
        try {
            recent.remove(folder);
        } catch (UncheckedIOException e) {
            throw new ApiError(HttpStatus.INTERNAL_SERVER_ERROR,
                    Message.of("runner.ui.recent.writeFailed", reason(e.getCause())));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/folders")
    FolderBrowser.Listing folders(@RequestParam(required = false) String path) {
        Path folder = path == null || path.isBlank() ? Path.of(System.getProperty("user.home")) : absolute(path);
        if (folder == null) {
            throw ApiError.badRequest("runner.ui.folder.notFolder", path);
        }
        try {
            return FolderBrowser.list(folder.toAbsolutePath());
        } catch (FolderBrowser.NotAFolderException e) {
            throw ApiError.badRequest("runner.ui.folder.notFolder", folder.toString());
        }
    }

    /** {@code text} as an absolute, normalized path, or null when it is none. */
    private static Path absolute(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(text);
            return path.isAbsolute() ? path.normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean isEmptyFolder(Path folder) {
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (Stream<Path> children = Files.list(folder)) {
            return children.findAny().isEmpty();
        } catch (IOException | UncheckedIOException e) {
            return false;
        }
    }

    /** The profiles of {@code config/application-<p>.yml}, sorted. */
    private static List<String> profiles(Workspace workspace) {
        List<String> profiles = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(workspace.config(),
                PROFILE_PREFIX + "*" + PROFILE_SUFFIX)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String profile = name.substring(PROFILE_PREFIX.length(), name.length() - PROFILE_SUFFIX.length());
                if (!profile.isEmpty() && Files.isRegularFile(file)) {
                    profiles.add(profile);
                }
            }
        } catch (IOException | RuntimeException e) {
            // no config/ folder, or unreadable: no profiles to pick
        }
        profiles.sort(null);
        return profiles;
    }

    /** Adds to the recent list; failing to save it never fails the request it came with. */
    private void remember(Path folder) {
        try {
            recent.add(folder);
        } catch (UncheckedIOException e) {
            LOG.warn("Could not save the recent workspaces list: {}", reason(e.getCause()));
        }
    }

    private static String reason(Throwable e) {
        if (e == null) {
            return "";
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
