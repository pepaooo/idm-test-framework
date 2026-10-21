package com.idm.test.runner.ui.api;

import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.runner.SecretMask;
import com.idm.test.runner.ui.PlainSecretLint;
import com.idm.test.runner.ui.Problem;
import com.idm.test.runner.ui.ScenarioProblems;
import com.idm.test.runner.ui.StepSchema;
import com.idm.test.runner.ui.UiSession;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.StepRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The editor's Problems panel and its schema. {@code POST /api/validate} checks text the user is
 * editing — never the file on disk, which it neither reads nor writes: a scenario
 * ({@code scenarios/**.yml|yaml}) is loaded and validated as {@code idmtest validate} does (first
 * error, red) and checked for plain-text passwords (warnings, amber); a settings file
 * ({@code config/*.yml|yaml}) only for plain-text passwords. {@code secrets.env} is never accepted.
 * The {@code path} is workspace-relative and only says what kind of file it is and how messages
 * name it, so this works whether or not a workspace is open. No problem text contains a value
 * under a secret-looking key: such values are masked in validator and parser messages.
 * <p>
 * {@code GET /api/schema/scenario} is the step-vocabulary JSON Schema, computed once.
 */
@RestController
class ValidationController {

    private static final String SCENARIOS = "scenarios";
    private static final String CONFIG = "config";
    private static final String SECRETS_FILE = "secrets.env";

    private final UiSession session;
    private final ScenarioValidator validator = EngineFactory.validator(StepRegistry.forValidation());
    private final String schema = StepSchema.of(StepRegistry.forValidation());

    ValidationController(UiSession session) {
        this.session = session;
    }

    record ValidateRequest(String path, String content) {
    }

    record ProblemBody(String severity, int line, int column, String message, String key) {
    }

    record Problems(List<ProblemBody> problems) {
    }

    @PostMapping("/api/validate")
    Problems validate(@RequestBody ValidateRequest request, HttpServletRequest http) {
        Kind kind = kind(request.path());
        if (request.content() == null) {
            throw ApiError.badRequest("runner.ui.request.invalid", "content");
        }
        String content = request.content();
        List<Problem> problems = new ArrayList<>();
        if (kind == Kind.SCENARIO) {
            String sourceName = request.path().substring(SCENARIOS.length() + 1);
            ScenarioProblems.check(content, sourceName, validator).ifPresent(problems::add);
        }
        problems.addAll(PlainSecretLint.check(content,
                kind == Kind.SCENARIO ? PlainSecretLint.FileKind.SCENARIO : PlainSecretLint.FileKind.SETTINGS));
        problems.sort(Comparator.comparingInt(Problem::line).thenComparingInt(Problem::column)
                .thenComparing(Problem::severity));

        Locale locale = ApiError.locale(http.getHeader("Accept-Language"), session.locale());
        SecretMask mask = SecretMask.ofValues(PlainSecretLint.literals(content));
        return new Problems(problems.stream()
                .map(p -> new ProblemBody(p.severity().json(), p.line(), p.column(),
                        mask.apply(Messages.render(p.message(), locale)), p.key()))
                .toList());
    }

    @GetMapping(value = "/api/schema/scenario", produces = MediaType.APPLICATION_JSON_VALUE)
    String schema() {
        return schema;
    }

    private enum Kind { SCENARIO, CONFIG }

    /** What the workspace-relative {@code path} is; refuses {@code secrets.env} and anything else. */
    private static Kind kind(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")
                || path.contains(":") || path.indexOf('\0') >= 0) {
            throw ApiError.badRequest("runner.ui.validate.badPath", path == null ? "" : path);
        }
        String[] parts = path.split("/", -1);
        if (parts[parts.length - 1].equalsIgnoreCase(SECRETS_FILE)) {
            throw ApiError.badRequest("runner.ui.validate.secretsFile");
        }
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw ApiError.badRequest("runner.ui.validate.badPath", path);
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        boolean yaml = lower.endsWith(".yml") || lower.endsWith(".yaml");
        if (yaml && parts.length >= 2 && parts[0].equals(SCENARIOS)) {
            return Kind.SCENARIO;
        }
        if (yaml && parts.length == 2 && parts[0].equals(CONFIG)) {
            return Kind.CONFIG;
        }
        throw ApiError.badRequest("runner.ui.validate.badPath", path);
    }
}
