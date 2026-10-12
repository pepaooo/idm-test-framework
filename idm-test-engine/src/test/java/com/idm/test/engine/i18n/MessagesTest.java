package com.idm.test.engine.i18n;

import com.idm.test.engine.spi.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Messages")
class MessagesTest {

    private static final String BASE = "com/idm/test/engine/i18n/test-messages";
    private static final Locale ES = Locale.of("es");

    private static String render(Message message, Locale locale) {
        return Messages.render(message, locale, BASE);
    }

    @Test
    @DisplayName("Should render English and Spanish")
    void testRendersBothLanguages() {
        assertThat(render(Message.of("greeting", "Ada"), Locale.ENGLISH)).isEqualTo("Hello Ada");
        assertThat(render(Message.of("greeting", "Ada"), ES)).isEqualTo("Hola Ada");
    }

    @Test
    @DisplayName("Should fall back to English for a key missing in Spanish, never to the JVM default")
    void testFallsBackToEnglish() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(ES);
            assertThat(render(Message.of("onlyEnglish"), ES)).isEqualTo("English only");
            assertThat(render(Message.of("greeting", "Ada"), Locale.ENGLISH)).isEqualTo("Hello Ada");
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    @DisplayName("Should render !key! with the arguments for a missing key or a malformed pattern, never throw")
    void testMissingAndBroken() {
        assertThat(render(Message.of("nope", "a", 1), Locale.ENGLISH)).isEqualTo("!nope! [a, 1]");
        assertThat(render(Message.of("broken", "x"), ES)).isEqualTo("!broken! [x]");
    }

    @Test
    @DisplayName("Should keep apostrophes in patterns and treat arguments literally")
    void testArgumentsAreLiteral() {
        assertThat(render(Message.of("quoted", "sn"), Locale.ENGLISH)).isEqualTo("found a mismatch for 'sn'");
        assertThat(render(Message.of("greeting", "O'Brien {0} {\"a\":1}"), Locale.ENGLISH))
                .isEqualTo("Hello O'Brien {0} {\"a\":1}");
        assertThat(render(Message.of("twice", "x"), ES)).isEqualTo("x y x");
    }

    @Test
    @DisplayName("Should render numbers without grouping and null as 'null'")
    void testNumbersAndNull() {
        assertThat(render(Message.of("greeting", 1234567), ES)).isEqualTo("Hola 1234567");
        assertThat(render(Message.of("greeting", 1.5), ES)).isEqualTo("Hola 1.5");
        assertThat(render(Message.of("greeting", (Object) null), Locale.ENGLISH)).isEqualTo("Hello null");
    }

    @Test
    @DisplayName("Should render a nested message in the same language")
    void testNested() {
        Message inner = Message.of("greeting", "Ada");
        assertThat(render(Message.of("nested", inner), ES)).isEqualTo("externo: Hola Ada");
        assertThat(render(Message.of("nested", inner), Locale.ENGLISH)).isEqualTo("outer: Hello Ada");
    }

    @Test
    @DisplayName("Should keep plain argument values and convert anything else to text when created")
    void testArgumentNormalisation() {
        Message message = Message.of("k", "s", 1, 2L, true, null, java.util.List.of("a"), Message.of("n"));
        assertThat(message.args()).containsExactly("s", 1, 2L, true, null, "[a]", Message.of("n"));
    }
}
