package com.idm.test.runner.ui.api;

import com.idm.test.engine.spi.Message;
import com.idm.test.runner.ui.FileWatcher;
import com.idm.test.runner.ui.GitTool;
import com.idm.test.runner.ui.RunSessions;
import com.idm.test.runner.ui.UiSession;
import com.idm.test.runner.ui.WorkspaceFiles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The editor's files: the workspace tree, reading a file with its version, saving it only over that
 * version (409 with {@code currentVersion} otherwise), adding scenarios, creating one
 * ({@code POST /api/files/new}: 201, or 409 {@code ui.file.exists}, never an overwrite), deleting a
 * scenario only at the version read ({@code DELETE /api/files}), a file's git state
 * ({@code GET /api/files/git}), and change events. All act on the session's current workspace
 * (400 {@code ui.workspace.none} while none is open); the rules for paths, {@code secrets.env} and saving are {@link WorkspaceFiles}'s. GETs only
 * read.
 */
@RestController
class FilesController {

    private final UiSession session;
    private final FileWatcher watcher;
    private final GitTool git;
    private final RunSessions runs;

    FilesController(UiSession session, FileWatcher watcher, GitTool git, RunSessions runs) {
        this.session = session;
        this.watcher = watcher;
        this.git = git;
        this.runs = runs;
    }

    record SaveRequest(String content, String version) {
    }

    record Saved(String version) {
    }

    record Added(List<String> added) {
    }

    record CreateRequest(String path, String content) {
    }

    record Created(String path, String version) {
    }

    /** A file's git state: {@code clean}, {@code changed}, {@code untracked} or {@code none}. */
    record GitState(String state) {
    }

    /** The 409 body: the error shape plus the version now on disk. */
    record Conflict(String key, String message, String currentVersion) {
    }

    @GetMapping("/api/files/tree")
    WorkspaceFiles.Node tree() {
        return files().tree();
    }

    @GetMapping("/api/files")
    WorkspaceFiles.Content read(@RequestParam String path) {
        WorkspaceFiles files = files();
        try {
            return files.read(path);
        } catch (WorkspaceFiles.FileException e) {
            throw error(e);
        }
    }

    @PutMapping("/api/files")
    ResponseEntity<?> save(@RequestParam String path, @RequestBody SaveRequest request, HttpServletRequest http) {
        WorkspaceFiles files = files();
        if (request.content() == null) {
            throw ApiError.badRequest("runner.ui.request.invalid", "content");
        }
        if (request.version() == null || request.version().isBlank()) {
            throw ApiError.badRequest("runner.ui.request.invalid", "version");
        }
        try {
            return ResponseEntity.ok(new Saved(files.save(path, request.content(), request.version())));
        } catch (WorkspaceFiles.FileException e) {
            throw error(e);
        } catch (WorkspaceFiles.ChangedException e) {
            return changed(path, e, http);
        } catch (UncheckedIOException e) {
            throw writeFailed(path, e);
        }
    }

    /**
     * Deletes the scenario file {@code path} if it is still at {@code version}: 204; 409
     * {@code ui.file.changed} with {@code currentVersion}, or {@code ui.file.inRun} while the run that
     * is going includes it (any scenario while a run is starting).
     */
    @DeleteMapping("/api/files")
    ResponseEntity<?> delete(@RequestParam String path, @RequestParam(required = false) String version,
                             HttpServletRequest http) {
        Path workspace = workspace();
        if (version == null || version.isBlank()) {
            throw ApiError.badRequest("runner.ui.request.invalid", "version");
        }
        try {
            new WorkspaceFiles(workspace).delete(path, version,
                    scenario -> runs.includes(scenario) && real(workspace).equals(runs.activeWorkspace()));
            return ResponseEntity.noContent().build();
        } catch (WorkspaceFiles.FileException e) {
            throw error(e);
        } catch (WorkspaceFiles.ChangedException e) {
            return changed(path, e, http);
        } catch (UncheckedIOException e) {
            throw writeFailed(path, e);
        }
    }

    /** The git state of the file {@code path}; only reads (see {@link GitTool#state}). */
    @GetMapping("/api/files/git")
    GitState git(@RequestParam String path) {
        Path workspace = workspace();
        String relative;
        try {
            relative = new WorkspaceFiles(workspace).relative(path);
        } catch (WorkspaceFiles.FileException e) {
            throw error(e);
        }
        return new GitState(git.state(workspace, relative).name().toLowerCase(Locale.ROOT));
    }

    private ResponseEntity<Conflict> changed(String path, WorkspaceFiles.ChangedException e, HttpServletRequest http) {
        ApiError.Body body = ApiError.Body.of(Message.of("runner.ui.file.changed", path),
                ApiError.locale(http.getHeader("Accept-Language"), session.locale()));
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body(new Conflict(body.key(), body.message(), e.currentVersion()));
    }

    /** The real path, or the normalized absolute one when it cannot be resolved. */
    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    @PostMapping(value = "/api/files/add", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Added add(@RequestParam("files") List<MultipartFile> uploads) {
        WorkspaceFiles files = files();
        List<WorkspaceFiles.Upload> list = new ArrayList<>();
        for (MultipartFile upload : uploads) {
            if (upload.getSize() > WorkspaceFiles.MAX_BYTES) {
                throw new ApiError(HttpStatus.CONTENT_TOO_LARGE,
                        Message.of("runner.ui.file.tooLarge", String.valueOf(upload.getOriginalFilename())));
            }
            try {
                list.add(new WorkspaceFiles.Upload(upload.getOriginalFilename(), upload.getBytes()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        try {
            return new Added(files.add(list));
        } catch (WorkspaceFiles.FileException e) {
            throw error(e);
        } catch (UncheckedIOException e) {
            throw writeFailed("scenarios/", e);
        }
    }

    @PostMapping("/api/files/new")
    ResponseEntity<Created> create(@RequestBody CreateRequest request) {
        WorkspaceFiles files = files();
        if (request.content() == null) {
            throw ApiError.badRequest("runner.ui.request.invalid", "content");
        }
        try {
            WorkspaceFiles.Content created = files.createScenario(request.path(), request.content());
            return ResponseEntity.status(HttpStatus.CREATED).body(new Created(request.path(), created.version()));
        } catch (WorkspaceFiles.FileException e) {
            throw error(e);
        } catch (UncheckedIOException e) {
            throw writeFailed(request.path(), e);
        }
    }

    @GetMapping(value = "/api/files/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        return watcher.subscribe(workspace());
    }

    private WorkspaceFiles files() {
        return new WorkspaceFiles(workspace());
    }

    private Path workspace() {
        Path workspace = session.workspace();
        if (workspace == null) {
            throw ApiError.badRequest("runner.ui.workspace.none");
        }
        return workspace;
    }

    private static ApiError error(WorkspaceFiles.FileException e) {
        return switch (e.problem()) {
            case BAD_PATH -> ApiError.badRequest("runner.ui.file.badPath", e.subject());
            case SECRETS -> new ApiError(HttpStatus.FORBIDDEN, Message.of("runner.ui.file.secrets"));
            case NOT_FOUND -> new ApiError(HttpStatus.NOT_FOUND, Message.of("runner.ui.file.notFound", e.subject()));
            case TOO_LARGE -> new ApiError(HttpStatus.CONTENT_TOO_LARGE, Message.of("runner.ui.file.tooLarge", e.subject()));
            case NOT_TEXT -> new ApiError(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    Message.of("runner.ui.file.notText", e.subject()));
            case NOT_YAML -> ApiError.badRequest("runner.ui.file.notYaml", e.subject());
            case BAD_NAME -> ApiError.badRequest("runner.ui.file.badName", e.subject());
            case EXISTS -> new ApiError(HttpStatus.CONFLICT, Message.of("runner.ui.file.exists", e.subject()));
            case NOT_DELETABLE -> ApiError.badRequest("runner.ui.file.notDeletable");
            case IN_RUN -> new ApiError(HttpStatus.CONFLICT, Message.of("runner.ui.file.inRun", e.subject()));
        };
    }

    private static ApiError writeFailed(String path, UncheckedIOException e) {
        Throwable cause = e.getCause();
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, Message.of("runner.ui.file.writeFailed", path, reason));
    }
}
