package com.idm.test.engine.i18n;

import java.util.List;
import java.util.Locale;

/** Which language a run's report uses: English unless the setting says Spanish. */
public final class ReportLocale {

    public static final List<String> SUPPORTED = List.of("en", "es");

    private ReportLocale() {
    }

    /** The first non-blank of the three sources, resolved; English when all are blank. */
    public static Locale resolve(String property, String env, String systemProperty) {
        for (String value : new String[]{property, env, systemProperty}) {
            if (value != null && !value.isBlank()) {
                return resolve(value);
            }
        }
        return Locale.ENGLISH;
    }

    public static Locale resolve(String value) {
        if (value == null || value.isBlank()) {
            return Locale.ENGLISH;
        }
        String tag = value.strip();
        return switch (tag.toLowerCase(Locale.ROOT)) {
            case "en" -> Locale.ENGLISH;
            case "es" -> Locale.of("es");
            default -> throw new UnsupportedLocaleException(tag);
        };
    }

    public static final class UnsupportedLocaleException extends IllegalArgumentException {
        UnsupportedLocaleException(String value) {
            super("unsupported report locale '" + value + "'; supported: " + String.join(", ", SUPPORTED));
        }
    }
}
