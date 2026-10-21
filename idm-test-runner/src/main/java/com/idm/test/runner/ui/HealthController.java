package com.idm.test.runner.ui;

import com.idm.test.runner.IdmTest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/**
 * {@code GET /api/health}: the workbench answers, with the runner's version, whether {@code git} is
 * installed (the start page offers "Start a Git repository here" only then), the language it was
 * started with ({@code --locale}; the page uses it until the user picks one) and the workspace that
 * is open — the one of {@code --workspace} at first — or null for the start page.
 */
@RestController
class HealthController {

    private final GitTool git;
    private final UiSession session;

    HealthController(GitTool git, UiSession session) {
        this.git = git;
        this.session = session;
    }

    /** The body of {@code /api/health}. */
    record Health(String version, boolean git, String locale, String workspace) {
    }

    @GetMapping("/api/health")
    Health health() {
        Path workspace = session.workspace();
        return new Health(IdmTest.version(), git.available(), session.locale().getLanguage(),
                workspace == null ? null : workspace.toString());
    }
}
