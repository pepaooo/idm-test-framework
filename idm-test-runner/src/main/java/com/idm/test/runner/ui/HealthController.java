package com.idm.test.runner.ui;

import com.idm.test.runner.IdmTest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/health}: the workbench answers, with the runner's version and whether {@code git}
 * is installed (the start page offers "Start a Git repository here" only then).
 */
@RestController
class HealthController {

    private final GitTool git;

    HealthController(GitTool git) {
        this.git = git;
    }

    /** The body of {@code /api/health}. */
    record Health(String version, boolean git) {
    }

    @GetMapping("/api/health")
    Health health() {
        return new Health(IdmTest.version(), git.available());
    }
}
