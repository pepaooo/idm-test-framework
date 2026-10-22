package com.idm.test.runner.ui.api;

import com.idm.test.engine.spi.Message;
import com.idm.test.runner.ui.LastRuns;
import com.idm.test.runner.ui.RunSessions;
import com.idm.test.runner.ui.UiSession;
import com.idm.test.runner.ui.WorkspaceFiles;
import com.idm.test.wiring.DirectoryScenarioSource;
import jakarta.annotation.PreDestroy;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runs from the workbench, one at a time ({@link RunSessions}):
 * <ul>
 *   <li>{@code POST /api/runs} {@code {profile, scenarios, onlyFindings, locale}} starts a run of the current
 *       workspace and answers 202 {@code {runId}} (null when the run was refused before it had a folder;
 *       the events say why); 409 {@code ui.run.busy} while one is active, 409 {@code ui.workspace.switched}
 *       when another tab opened another workspace meanwhile. {@code scenarios} is a list of
 *       workspace-relative scenario paths, {@code "all"} (also when left out) or {@code {"folder":
 *       "scenarios/<sub>"}}; {@code onlyFindings} keeps those whose last result was a finding or a setup
 *       problem. {@code locale} ({@code en}, {@code es}) is the run's language, the UI's by default.</li>
 *   <li>{@code GET /api/runs/current/events}: Server-Sent Events of the current or most recent run —
 *       those so far, then the new ones; the stream ends after the run's last event; 204 before any run.
 *       Events have ids {@code <process>.<run>:<index>}; with {@code Last-Event-ID} of the same run and process the stream
 *       resumes after that event, and is 204 when the run has ended and nothing is newer.
 *       {@code runStarted}, {@code scenarioStarted}, {@code stepStarted}, {@code attempt},
 *       {@code stepFinished} (the masked step result as in {@code results.json}), {@code scenarioFinished}
 *       {@code {result, failureLine}}, {@code warning} and {@code refused} {@code {key, message}},
 *       {@code runFinished} (the outcome without the folder, with {@code reportUrl}).</li>
 *   <li>{@code POST /api/runs/current/stop} stops the active run at once (202 {@code {runId}}); 409
 *       {@code ui.run.none} when no run is active — a conflict with the workbench's state, like busy.</li>
 *   <li>{@code GET /api/runs/history?scenario=} the newest 10 runs of {@code reports/}, or of one
 *       scenario, as {@code {runId, at, profile, result}}.</li>
 *   <li>{@code GET /reports/<runId>/report.html} a run's report; 404 unless {@code runId} is a run id and
 *       the file's real location is in the workspace's {@code reports/<runId>/}.</li>
 * </ul>
 * Paths follow {@link WorkspaceFiles}'s rules (400 {@code ui.file.badPath}). GETs only read.
 */
@RestController
class RunsController {

    private static final int HISTORY = 10;
    /** A run folder's name ({@code yyyyMMdd-HHmmss-<profile>}, maybe {@code -<n>}); the profile as runs accept it. */
    private static final Pattern RUN_ID = Pattern.compile("\\d{8}-\\d{6}-[A-Za-z0-9_.-]+");
    private static final String REPORT = "report" + ".html";

    private final UiSession session;
    private final RunSessions runs;

    RunsController(UiSession session, RunSessions runs) {
        this.session = session;
        this.runs = runs;
    }

    record StartRequest(String profile, JsonNode scenarios, Boolean onlyFindings, String locale) {
    }

    record Started(String runId) {
    }

    @PostMapping("/api/runs")
    ResponseEntity<Started> start(@RequestBody StartRequest request) {
        Path workspace = workspace();
        if (runs.active()) {
            throw busy();
        }
        String profile = LabController.profile(request.profile());
        Locale locale = locale(request.locale());
        List<String> patterns = select(workspace, request.scenarios(), Boolean.TRUE.equals(request.onlyFindings()));
        try {
            String runId = runs.start(workspace, profile, patterns, locale, session::workspace);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Started(runId));
        } catch (RunSessions.BusyException e) {
            throw busy();
        } catch (RunSessions.SwitchedException e) {
            // another tab opened another workspace since this request was let in
            Path current = session.workspace();
            Path name = current == null ? null : current.getFileName();
            throw new ApiError(HttpStatus.CONFLICT, Message.of("runner.ui.workspace.switched",
                    name != null ? name.toString() : String.valueOf(current)));
        }
    }

    @GetMapping(value = "/api/runs/current/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> events(@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = runs.subscribe(lastEventId);
        if (emitter == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(emitter);
    }

    @PostMapping("/api/runs/current/stop")
    ResponseEntity<Started> stop() {
        if (!runs.stop()) {
            throw new ApiError(HttpStatus.CONFLICT, Message.of("runner.ui.run.none"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Started(runs.activeRunId()));
    }

    @GetMapping("/api/runs/history")
    List<LastRuns.HistoryEntry> history(@RequestParam(required = false) String scenario) {
        Path workspace = workspace();
        String name = scenario == null || scenario.isBlank() ? null : scenarioName(workspace, scenario);
        return LastRuns.history(workspace, name, HISTORY);
    }

    @GetMapping("/reports/{runId}/" + REPORT)
    ResponseEntity<byte[]> report(@PathVariable String runId) {
        Path workspace = session.workspace();
        if (workspace == null || !RUN_ID.matcher(runId).matches() || runId.contains("..")) {
            throw notFound();
        }
        try {
            Path folder = workspace.resolve("reports").toRealPath().resolve(runId);
            Path file = folder.resolve(REPORT).toRealPath();
            if (!file.startsWith(folder) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw notFound();
            }
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .cacheControl(CacheControl.noStore())
                    // The report's own script runs in a sandbox: not with the workbench's origin and cookie.
                    .header("Content-Security-Policy", "sandbox allow-scripts; frame-ancestors 'none'")
                    .body(Files.readAllBytes(file));
        } catch (IOException e) {
            // missing, unreadable (access denied), a loop of links: no report to serve
            throw notFound();
        }
    }

    @PreDestroy
    void close() {
        runs.shutdown();
    }

    // --- selection --------------------------------------------------------------------------------

    /**
     * The scenario patterns for the run: none for all of them, else each chosen scenario's name, its
     * glob characters escaped so that it matches only itself.
     */
    private static List<String> select(Path workspace, JsonNode scenarios, boolean onlyFindings) {
        List<String> names = names(workspace);
        List<String> chosen;
        String where = "scenarios/";
        boolean all = false;
        WorkspaceFiles files = new WorkspaceFiles(workspace);
        if (scenarios == null || scenarios.isNull() || scenarios.isString() && scenarios.asString().equals("all")) {
            chosen = names;
            all = true;
        } else if (scenarios.isArray() && !scenarios.isEmpty()) {
            Set<String> set = new LinkedHashSet<>();
            for (JsonNode path : scenarios) {
                if (!path.isString()) {
                    throw ApiError.badRequest("runner.ui.request.invalid", "scenarios");
                }
                String name = scenarioName(workspace, path.asString());
                if (!names.contains(name)) {
                    throw new ApiError(HttpStatus.NOT_FOUND, Message.of("runner.ui.run.noSuchScenario", path.asString()));
                }
                set.add(name);
            }
            chosen = List.copyOf(set);
        } else if (scenarios.isObject() && scenarios.size() == 1 && scenarios.path("folder").isString()) {
            String folder = scenarios.get("folder").asString();
            String prefix = under(files, folder);
            where = folder;
            all = prefix.isEmpty();
            chosen = all ? names : names.stream().filter(name -> name.startsWith(prefix + "/")).toList();
        } else {
            throw ApiError.badRequest("runner.ui.request.invalid", "scenarios");
        }
        if (onlyFindings) {
            Map<String, LastRuns.Outcome> last = LastRuns.lastOutcomes(workspace, Set.copyOf(chosen));
            chosen = chosen.stream().filter(name -> last.get(name) == LastRuns.Outcome.FINDING
                    || last.get(name) == LastRuns.Outcome.SETUP).toList();
            if (chosen.isEmpty()) {
                throw ApiError.badRequest("runner.ui.run.noFindings");
            }
            all = false;
        }
        if (chosen.isEmpty()) {
            throw ApiError.badRequest("runner.ui.run.noScenarios", where);
        }
        if (all) {
            return List.of();
        }
        List<String> patterns = new ArrayList<>();
        for (String name : chosen) {
            patterns.add(escapeGlob(name));
        }
        return patterns;
    }

    /** The scenarios of the workspace, as the runner names them (paths under {@code scenarios/}). */
    private static List<String> names(Path workspace) {
        try {
            return new DirectoryScenarioSource(workspace.resolve("scenarios")).names();
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The runner's name of the scenario at the workspace-relative {@code path}. */
    private static String scenarioName(Path workspace, String path) {
        String name = under(new WorkspaceFiles(workspace), path);
        if (name.isEmpty()) {
            throw ApiError.badRequest("runner.ui.file.badPath", path);
        }
        return name;
    }

    private static String under(WorkspaceFiles files, String path) {
        try {
            return files.underScenarios(path);
        } catch (WorkspaceFiles.FileException e) {
            throw ApiError.badRequest("runner.ui.file.badPath", e.subject());
        }
    }

    /** {@code name} as a glob that matches only {@code name}. */
    static String escapeGlob(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            if ("*?[]{}\\".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    // --- helpers ------------------------------------------------------------------------------------

    private Path workspace() {
        Path workspace = session.workspace();
        if (workspace == null) {
            throw ApiError.badRequest("runner.ui.workspace.none");
        }
        return workspace;
    }

    private Locale locale(String tag) {
        if (tag == null || tag.isBlank()) {
            return session.locale();
        }
        return switch (tag) {
            case "en" -> Locale.ENGLISH;
            case "es" -> Locale.of("es");
            default -> throw ApiError.badRequest("runner.ui.run.badLocale", tag);
        };
    }

    private static ApiError busy() {
        return new ApiError(HttpStatus.CONFLICT, Message.of("runner.ui.run.busy"));
    }

    private static ApiError notFound() {
        return new ApiError(HttpStatus.NOT_FOUND, Message.of("runner.ui.request.notFound"));
    }
}
