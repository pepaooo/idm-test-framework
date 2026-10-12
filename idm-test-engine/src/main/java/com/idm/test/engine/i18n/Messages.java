package com.idm.test.engine.i18n;

import com.idm.test.engine.spi.Message;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Renders a {@link Message} from the catalogs {@code messages.properties} (English) and
 * {@code messages_es.properties}. A key missing in Spanish falls back to English; the JVM's
 * default locale is never consulted. A key missing everywhere, or a malformed pattern, renders as
 * {@code !key! [args]} — this never throws, so a catalog mistake cannot hide a failure.
 */
public final class Messages {

    static final String BASE = "com/idm/test/engine/i18n/messages";
    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private Messages() {
    }

    public static String render(Message message, Locale locale) {
        return render(message, locale, BASE);
    }

    /** The English text — what results record as {@code message}. */
    public static String english(Message message) {
        return render(message, Locale.ENGLISH);
    }

    static String render(Message message, Locale locale, String baseName) {
        Object[] args = message.args().stream()
                .map(arg -> arg instanceof Message nested ? render(nested, locale, baseName) : String.valueOf(arg))
                .toArray();
        String pattern = pattern(message.key(), locale, baseName);
        if (pattern != null) {
            try {
                return new MessageFormat(pattern, locale).format(args);
            } catch (IllegalArgumentException malformed) {
                // falls through to the visible marker
            }
        }
        return "!" + message.key() + "! " + java.util.Arrays.toString(args);
    }

    private static String pattern(String key, Locale locale, String baseName) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(baseName, locale, NO_FALLBACK);
            return bundle.containsKey(key) ? bundle.getString(key) : null;
        } catch (MissingResourceException e) {
            return null;
        }
    }
}
