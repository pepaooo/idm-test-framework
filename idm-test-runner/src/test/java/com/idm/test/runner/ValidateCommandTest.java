package com.idm.test.runner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("idmtest validate")
class ValidateCommandTest {

    private static final String GOOD = """
            name: good
            steps:
              - ldap.delete:
                  dn: "cn=x,ou=users"
            """;
    private static final String BAD = """
            name: bad
            steps:
              - no.such.step:
                  dn: x
            """;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    @TempDir
    Path ws;

    @BeforeEach
    void workspace() throws Exception {
        Files.createDirectories(ws.resolve("scenarios"));
        Files.writeString(ws.resolve("scenarios/good.yaml"), GOOD);
        Files.writeString(ws.resolve("scenarios/bad.yaml"), BAD);
    }

    private int validate(String... extra) {
        String[] args = new String[extra.length + 3];
        args[0] = "validate";
        args[1] = "--workspace";
        args[2] = ws.toString();
        System.arraycopy(extra, 0, args, 3, extra.length);
        return IdmTest.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Should list valid and invalid scenarios, summarise and exit 1")
    void testMixed() {
        assertThat(validate()).isEqualTo(ExitCodes.FAILED);
        assertThat(out()).contains("✔ good.yaml", "✖ bad.yaml — ", "no.such.step",
                "2 scenarios: 1 valid, 1 invalid");
    }

    @Test
    @DisplayName("Should print the summary and validator message in Spanish with --locale es")
    void testSpanish() {
        assertThat(validate("--locale", "es")).isEqualTo(ExitCodes.FAILED);
        assertThat(out()).contains("2 escenarios: 1 válidos, 1 no válidos", "✖ bad.yaml — ");
        assertThat(out()).doesNotContain("unknown step type");
    }

    @Test
    @DisplayName("Should validate only the selected scenario and exit 0")
    void testSelect() {
        assertThat(validate("--scenario", "good")).isEqualTo(ExitCodes.OK);
        assertThat(out()).contains("✔ good.yaml", "1 scenarios: 1 valid, 0 invalid").doesNotContain("bad.yaml");
    }

    @Test
    @DisplayName("Should exit 2 when no scenario matches")
    void testNoMatch() {
        assertThat(validate("--scenario", "none*")).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("no scenario matches 'none*'");
    }

    @Test
    @DisplayName("Should report an invalid glob without mentioning -Dscenario")
    void testInvalidGlob() {
        assertThat(validate("--scenario", "[abc")).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("invalid --scenario pattern '[abc': ").doesNotContain("-Dscenario");
    }

    @Test
    @DisplayName("Should exit 2 for a profile without a settings file")
    void testMissingProfile() {
        assertThat(validate("--profile", "lab")).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("no settings for profile 'lab': config/application-lab.yml not found");
    }

    @Test
    @DisplayName("Should exit 2 for an unsupported locale")
    void testBadLocale() {
        assertThat(validate("--locale", "fr")).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("unsupported report locale 'fr'");
    }

    @Test
    @DisplayName("Should never connect to the systems a profile names")
    void testValidateNeverConnects() throws Exception {
        Files.delete(ws.resolve("scenarios/bad.yaml"));
        Files.createDirectories(ws.resolve("config"));
        Files.writeString(ws.resolve("config/application-lab.yml"), """
                idm:
                  test:
                    ldap:
                      url: ldap://192.0.2.1:389
                      base-dn: o=data
                      user-dn: cn=admin,o=data
                      password: x
                    databases:
                      main:
                        jdbc-url: jdbc:postgresql://192.0.2.1/x
                        username: u
                        password: p
                """);

        long start = System.nanoTime();
        assertThat(validate("--profile", "lab")).isEqualTo(ExitCodes.OK);

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5000);
    }
}
