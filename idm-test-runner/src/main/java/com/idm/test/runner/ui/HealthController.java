package com.idm.test.runner.ui;

import com.idm.test.runner.IdmTest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/health}: the workbench answers, with the runner's version. */
@RestController
class HealthController {

    /** The body of {@code /api/health}. */
    record Health(String version) {
    }

    @GetMapping("/api/health")
    Health health() {
        return new Health(IdmTest.version());
    }
}
