package com.idm.test.engine.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Message catalogs")
class CatalogTest {

    private static final Path DIR = Path.of("src/main/resources/com/idm/test/engine/i18n");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");
    private static final Pattern KEY_LITERAL = Pattern.compile(
            "\"((?:report|evidence|step|engine|validation|runner)\\.[A-Za-z0-9_.]+)\"");
    /** Sources that may use keys: the engine, and the runners in the scenarios module. */
    private static final Path[] SOURCES = {Path.of("src/main/java"), Path.of("../idm-test-scenarios/src/test/java")};
    /** Keys whose Spanish text may equal the English (technical words that stay as they are). */
    private static final Set<String> SAME_IN_BOTH = Set.of(
            "report.datePattern",
            // "<file>: <detail>" — only punctuation
            "engine.summary.detail",
            // " — <notes>" and "<note>; <note>" — only punctuation around http.verify's check notes
            "step.http.verify.notes",
            "step.http.verify.notesJoin");

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(DIR.resolve(name), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Set<String> placeholders(String pattern) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    @Test
    @DisplayName("English and Spanish have the same keys and the same placeholders per key")
    void testSameKeysAndPlaceholders() throws IOException {
        Properties en = load("messages.properties");
        Properties es = load("messages_es.properties");
        assertThat(es.stringPropertyNames()).isEqualTo(en.stringPropertyNames());
        for (String key : en.stringPropertyNames()) {
            assertThat(placeholders(es.getProperty(key))).as(key).isEqualTo(placeholders(en.getProperty(key)));
        }
    }

    @Test
    @DisplayName("Every pattern parses and uses only plain {n} placeholders")
    void testPatternsParse() throws IOException {
        for (String file : new String[]{"messages.properties", "messages_es.properties"}) {
            Properties catalog = load(file);
            for (String key : catalog.stringPropertyNames()) {
                String pattern = catalog.getProperty(key);
                assertThat(pattern).as(file + " " + key).doesNotContainPattern("\\{\\d+,");
                new MessageFormat(pattern, Locale.ENGLISH);
            }
        }
    }

    @Test
    @DisplayName("Spanish text differs from English, apart from the listed technical keys")
    void testSpanishIsTranslated() throws IOException {
        Properties en = load("messages.properties");
        Properties es = load("messages_es.properties");
        for (String key : en.stringPropertyNames()) {
            if (!SAME_IN_BOTH.contains(key)) {
                assertThat(es.getProperty(key)).as(key).isNotEqualTo(en.getProperty(key));
            }
        }
    }

    @Test
    @DisplayName("Every key used in code exists, and every key in the catalog is used")
    void testKeysUsed() throws IOException {
        Set<String> used = new TreeSet<>();
        for (Path root : SOURCES) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher matcher = KEY_LITERAL.matcher(Files.readString(file));
                    while (matcher.find()) {
                        used.add(matcher.group(1));
                    }
                }
            }
        }
        Set<String> defined = load("messages.properties").stringPropertyNames();
        assertThat(defined).as("keys used in code but missing from the catalog").containsAll(used);
        assertThat(used).as("keys in the catalog that no code uses").containsAll(defined);
    }
}
