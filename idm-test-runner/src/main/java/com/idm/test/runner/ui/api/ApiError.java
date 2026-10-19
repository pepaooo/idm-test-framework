package com.idm.test.runner.ui.api;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.Message;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;

/**
 * The workbench API's error, and the shape every API error and warning has in JSON:
 * {@code {"key": "ui.workspace.notWorkspace", "message": "…"}}. The {@code key} is stable for the
 * front end to act on; the {@code message} is ready to show, in the request's language. Each key
 * {@code ui.<x>} is rendered from the runner catalog's {@code runner.ui.<x>}.
 */
public final class ApiError extends RuntimeException {

    private static final String CATALOG_PREFIX = "runner.";

    private final HttpStatus status;
    private final transient Message message;

    /** @param message a message of the runner catalog whose key starts with {@code runner.ui.} */
    public ApiError(HttpStatus status, Message message) {
        super(message.key());
        if (!message.key().startsWith(CATALOG_PREFIX + "ui.")) {
            throw new IllegalArgumentException("not a workbench message: " + message.key());
        }
        this.status = status;
        this.message = message;
    }

    /** A 400 for {@code message}. */
    public static ApiError badRequest(String key, Object... args) {
        return new ApiError(HttpStatus.BAD_REQUEST, Message.of(key, args));
    }

    public HttpStatus status() {
        return status;
    }

    public Body body(Locale locale) {
        return Body.of(message, locale);
    }

    /** An error or warning as JSON. */
    public record Body(String key, String message) {

        /** {@code message} (a {@code runner.ui.*} key) as the API shows it in {@code locale}. */
        public static Body of(Message message, Locale locale) {
            return new Body(message.key().substring(CATALOG_PREFIX.length()), Messages.render(message, locale));
        }
    }

    /** The supported language {@code acceptLanguage} prefers, or {@code fallback} (the UI's own). */
    public static Locale locale(String acceptLanguage, Locale fallback) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return fallback;
        }
        try {
            Locale chosen = Locale.lookup(Locale.LanguageRange.parse(acceptLanguage),
                    List.of(Locale.ENGLISH, Locale.of("es")));
            return chosen != null ? chosen : fallback;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
