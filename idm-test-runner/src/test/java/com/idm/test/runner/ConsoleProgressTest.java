package com.idm.test.runner;

import com.idm.test.engine.spi.FailureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    @Test
    @DisplayName("UTF-8 streams keep ✔ and ✖ for run and validate lines")
    void testUtf8KeepsSymbols() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        ConsoleProgress progress = new ConsoleProgress(out, Locale.ENGLISH);
        progress.passed("a.yaml", 12);
        progress.failed("b.yaml", 34, "boom", FailureKind.FINDING);
        progress.validated("c.yaml");
        progress.invalid("d.yaml", "bad step");
        progress.summary(2, 1, 1, null);
        assertThat(bytes.toString(StandardCharsets.UTF_8).lines().toList()).containsExactly(
                "✔ a.yaml  12 ms",
                "✖ b.yaml  34 ms — boom · finding",
                "✔ c.yaml",
                "✖ d.yaml — bad step",
                "2 scenarios: 1 valid, 1 invalid");
    }

    @Test
    @DisplayName("Streams whose charset cannot encode ✔ and ✖ fall back to [OK] and [FAIL]")
    void testFallbackMarksWhenCharsetCannotEncodeSymbols() {
        Charset windows1252 = Charset.forName("windows-1252");
        ByteArrayOutputStream winBytes = new ByteArrayOutputStream();
        ConsoleProgress winProgress = new ConsoleProgress(new PrintStream(winBytes, true, windows1252), Locale.ENGLISH);
        winProgress.passed("a.yaml", 12);
        winProgress.failed("b.yaml", 34, "boom", FailureKind.SETUP);
        winProgress.validated("c.yaml");
        winProgress.invalid("d.yaml", "bad step");
        winProgress.summary(2, 1, 1, null);
        assertThat(winBytes.toString(windows1252).lines().toList()).containsExactly(
                "[OK] a.yaml  12 ms",
                "[FAIL] b.yaml  34 ms — boom · setup problem",
                "[OK] c.yaml",
                "[FAIL] d.yaml — bad step",
                "2 scenarios: 1 valid, 1 invalid");

        ByteArrayOutputStream asciiBytes = new ByteArrayOutputStream();
        ConsoleProgress asciiProgress = new ConsoleProgress(
                new PrintStream(asciiBytes, true, StandardCharsets.US_ASCII), Locale.ENGLISH);
        asciiProgress.passed("a.yaml", 12);
        asciiProgress.failed("b.yaml", 34, "boom", null);
        asciiProgress.validated("c.yaml");
        asciiProgress.invalid("d.yaml", "bad step");
        List<String> asciiLines = asciiBytes.toString(StandardCharsets.US_ASCII).lines().toList();
        assertThat(asciiLines.get(0)).isEqualTo("[OK] a.yaml  12 ms");
        assertThat(asciiLines.get(1)).startsWith("[FAIL] b.yaml  34 ms ").endsWith(" boom");
        assertThat(asciiLines.get(2)).isEqualTo("[OK] c.yaml");
        assertThat(asciiLines.get(3)).startsWith("[FAIL] d.yaml ").endsWith(" bad step");
    }
}
