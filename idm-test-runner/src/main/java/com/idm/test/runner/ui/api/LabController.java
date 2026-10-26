package com.idm.test.runner.ui.api;

import com.idm.test.runner.ui.UiSession;
import com.idm.test.runner.ui.lab.ConnectionTester;
import com.idm.test.runner.ui.lab.LabSettings;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The lab panel: {@code GET /api/lab?profile=} lists the systems the workspace needs with their
 * settings (secrets only as {@code set}, {@code not set}, {@code environment} or
 * {@code application.yml}, never their value) and status; {@code PUT /api/lab?profile=} saves one
 * system's settings ({@code values} into {@code config/application-<profile>.yml}, {@code secrets} into
 * {@code secrets.env}) and answers with the lab as GET does; {@code PUT /api/lab/default-directory?profile=}
 * saves {@code name} as the default directory (one of the lab's {@code defaultDirectory.choices}) into the
 * profile file and answers as GET does; {@code POST /api/lab/test} tests a system
 * with the form's values without saving anything. All act on the session's current workspace (400
 * {@code ui.workspace.none} while none is open); the rules are {@link LabSettings}'s. GET only reads.
 */
@RestController
class LabController {

    private final UiSession session;
    private final LabSettings lab;
    private final ConnectionTester tester = new ConnectionTester();

    LabController(UiSession session, LabSettings lab) {
        this.session = session;
        this.lab = lab;
    }

    record SaveRequest(String systemId, Map<String, String> values, Map<String, String> secrets) {
        /** Never shows the secrets. */
        @Override
        public String toString() {
            return "SaveRequest[systemId=" + systemId + ", values=" + values + ", secrets="
                    + (secrets == null ? null : secrets.keySet()) + "]";
        }
    }

    record DefaultDirectoryRequest(String name) {
    }

    record TestRequest(String systemId, String profile, Map<String, String> values, Map<String, String> secrets) {
        /** Never shows the secrets. */
        @Override
        public String toString() {
            return "TestRequest[systemId=" + systemId + ", profile=" + profile + ", values=" + values + ", secrets="
                    + (secrets == null ? null : secrets.keySet()) + "]";
        }
    }

    @GetMapping("/api/lab")
    LabSettings.View read(@RequestParam String profile, HttpServletRequest http) {
        return lab.read(workspace(), profile(profile), locale(http));
    }

    @PutMapping("/api/lab")
    LabSettings.View save(@RequestParam String profile, @RequestBody SaveRequest request, HttpServletRequest http) {
        Path workspace = workspace();
        String name = profile(profile);
        lab.save(workspace, name, request.systemId(), request.values(), request.secrets());
        return lab.read(workspace, name, locale(http));
    }

    @PutMapping("/api/lab/default-directory")
    LabSettings.View saveDefaultDirectory(@RequestParam String profile, @RequestBody DefaultDirectoryRequest request,
                                          HttpServletRequest http) {
        Path workspace = workspace();
        String name = profile(profile);
        lab.saveDefaultDirectory(workspace, name, request.name());
        return lab.read(workspace, name, locale(http));
    }

    @DeleteMapping("/api/lab")
    LabSettings.View delete(@RequestParam String profile, @RequestParam String systemId, HttpServletRequest http) {
        Path workspace = workspace();
        String name = profile(profile);
        lab.delete(workspace, name, systemId);
        return lab.read(workspace, name, locale(http));
    }

    @PostMapping("/api/lab/test")
    ConnectionTester.Result test(@RequestBody TestRequest request, HttpServletRequest http) {
        Path workspace = workspace();
        return lab.test(workspace, profile(request.profile()), request.systemId(), request.values(), request.secrets(),
                locale(http), tester);
    }

    @PreDestroy
    void close() {
        tester.close();
    }

    private Path workspace() {
        Path workspace = session.workspace();
        if (workspace == null) {
            throw ApiError.badRequest("runner.ui.workspace.none");
        }
        return workspace;
    }

    /** A profile name as {@code idmtest run} accepts it, and no more: letters, digits, '-', '_' and '.'. */
    static String profile(String profile) {
        if (profile == null || profile.isBlank() || profile.contains("..")
                || !profile.matches("[A-Za-z0-9_.-]+")) {
            throw ApiError.badRequest("runner.ui.lab.badProfile", String.valueOf(profile));
        }
        return profile;
    }

    private Locale locale(HttpServletRequest http) {
        return ApiError.locale(http.getHeader("Accept-Language"), session.locale());
    }
}
