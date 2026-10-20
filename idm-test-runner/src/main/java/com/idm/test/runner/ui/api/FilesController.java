package com.idm.test.runner.ui.api;

import com.idm.test.engine.spi.Message;
import com.idm.test.runner.ui.FileWatcher;
import com.idm.test.runner.ui.UiSession;
import com.idm.test.runner.ui.WorkspaceFiles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

/**
 * The editor's files: the workspace tree, reading a file with its version, saving it only over that
 * version (409 with {@code currentVersion} otherwise), adding scenarios, and change events. All act
 * on the session's current workspace (400 {@code ui.workspace.none} while none is open); the rules
 * for paths, {@code secrets.env} and saving are {@link WorkspaceFiles}'s. GETs only read.
 */
@RestController
class FilesController {

    private final UiSession session;
    private final FileWatcher watcher;

    FilesController(UiSession session, FileWatcher watcher) {
        this.session = session;
        this.watcher = watcher;
    }

    record SaveRequest(String content, String version) {
    }

    record Saved(String version) {
    }

    record Added(List<String> added) {
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
            ApiError.Body body = ApiError.Body.of(Message.of("runner.ui.file.changed", path),
                    ApiError.locale(http.getHeader("Accept-Language"), session.locale()));
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body(new Conflict(body.key(), body.message(), e.currentVersion()));
        } catch (UncheckedIOException e) {
            throw writeFailed(path, e);
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

    @GetMapping(value = "/api/files/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        workspace();
        return watcher.subscribe();
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
        };
    }

    private static ApiError writeFailed(String path, UncheckedIOException e) {
        Throwable cause = e.getCause();
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, Message.of("runner.ui.file.writeFailed", path, reason));
    }
}
