package com.idm.test.runner.ui;

import com.idm.test.engine.spi.Message;

/**
 * One entry of the editor's Problems panel: where it is (1-based line and column), how bad, a stable
 * {@code key} for the front end, and the text by key so it can be shown in the request's language.
 *
 * @param key     {@code ui.lint.plainSecret} for the password warning; for a validation error, the
 *                engine's key of the problem itself (for example {@code validation.step.unknownParam})
 * @param message the text to show: for a validation error the whole framed message, exactly as
 *                {@code idmtest validate} prints it
 */
public record Problem(Severity severity, int line, int column, String key, Message message) {

    public enum Severity {
        ERROR, WARNING;

        /** As the API writes it: {@code error} or {@code warning}. */
        public String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
