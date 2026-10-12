package com.idm.test.engine.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReportLocale")
class ReportLocaleTest {

    @Test
    @DisplayName("Should default to English for null or blank")
    void testResolveDefault() {
        assertThat(ReportLocale.resolve(null)).isEqualTo(Locale.ENGLISH);
        assertThat(ReportLocale.resolve("  ")).isEqualTo(Locale.ENGLISH);
    }

    @Test
    @DisplayName("Should accept en and es in any case, with surrounding spaces")
    void testResolveSupported() {
        assertThat(ReportLocale.resolve("es")).isEqualTo(Locale.of("es"));
        assertThat(ReportLocale.resolve(" ES ")).isEqualTo(Locale.of("es"));
        assertThat(ReportLocale.resolve("En")).isEqualTo(Locale.ENGLISH);
    }

    @Test
    @DisplayName("Should reject anything else, including regional variants")
    void testResolveUnsupported() {
        for (String value : new String[]{"fr", "es-MX", "es_ES", "english"}) {
            assertThatThrownBy(() -> ReportLocale.resolve(value))
                    .isInstanceOf(ReportLocale.UnsupportedLocaleException.class)
                    .hasMessage("unsupported report locale '" + value.strip() + "'; supported: en, es");
        }
    }

    @Test
    @DisplayName("Should take the first non-blank source: property, then environment, then system property")
    void testResolvePrecedence() {
        assertThat(ReportLocale.resolve("es", "en", "en")).isEqualTo(Locale.of("es"));
        assertThat(ReportLocale.resolve(" ", "es", "en")).isEqualTo(Locale.of("es"));
        assertThat(ReportLocale.resolve(null, null, "es")).isEqualTo(Locale.of("es"));
        assertThat(ReportLocale.resolve(null, "", null)).isEqualTo(Locale.ENGLISH);
    }
}
