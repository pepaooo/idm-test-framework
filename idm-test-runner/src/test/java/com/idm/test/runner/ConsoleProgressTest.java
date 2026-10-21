package com.idm.test.runner;

import com.idm.test.engine.spi.FailureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Console progress")
class ConsoleProgressTest {

    private static String failed(Locale locale, FailureKind kind) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        new ConsoleProgress(out, locale).failed("a.yaml", 12, "went wrong", kind);
        return bytes.toString(StandardCharsets.UTF_8).strip();
    }

    @Test
    @DisplayName("A failed scenario says whether it is a finding or a setup problem")
    void testKindLabels() {
        assertThat(failed(Locale.ENGLISH, FailureKind.FINDING)).isEqualTo("✖ a.yaml  12 ms — went wrong · finding");
        assertThat(failed(Locale.ENGLISH, FailureKind.SETUP))
                .isEqualTo("✖ a.yaml  12 ms — went wrong · setup problem");
    }

    @Test
    @DisplayName("The kind labels are in Spanish too")
    void testSpanishKindLabels() {
        Locale es = Locale.forLanguageTag("es");
        assertThat(failed(es, FailureKind.FINDING)).isEqualTo("✖ a.yaml  12 ms — went wrong · hallazgo");
        assertThat(failed(es, FailureKind.SETUP))
                .isEqualTo("✖ a.yaml  12 ms — went wrong · problema de configuración");
    }

    @Test
    @DisplayName("Without a kind the line is as before")
    void testNoKind() {
        assertThat(failed(Locale.ENGLISH, null)).isEqualTo("✖ a.yaml  12 ms — went wrong");
    }
}
