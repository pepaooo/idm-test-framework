package com.idm.test.runner;

import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.StepRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("idmtest init")
class InitCommandTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);

    private String output() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Should write the starter files into a folder with spaces, and the example should validate")
    void testInitWritesStarterWorkspace(@TempDir Path temp) throws Exception {
        Path target = temp.resolve("My Tests");

        assertThat(InitCommand.run(target, out)).isEqualTo(ExitCodes.OK);

        try (Stream<Path> walk = Files.walk(target)) {
            List<String> files = walk.filter(Files::isRegularFile)
                    .map(p -> target.relativize(p).toString().replace('\\', '/')).sorted().toList();
            assertThat(files).containsExactly(".gitignore", "README.md", "config/application-lab.yml",
                    "drivers/README.md", "scenarios/example.yaml", "secrets.env.example");
        }
        assertThat(Files.readAllLines(target.resolve(".gitignore"))).contains("secrets.env", "reports/");
        assertThat(output()).contains("created scenarios/example.yaml", "workspace ready: " + target);

        Path example = target.resolve("scenarios/example.yaml");
        try (InputStream in = Files.newInputStream(example)) {
            Scenario scenario = ScenarioLoader.load(in, "example.yaml");
            assertThatCode(() -> EngineFactory.validator(StepRegistry.forValidation())
                    .validate(scenario, "example.yaml")).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("Should keep edited files and report them as skipped on a second run")
    void testSecondRunKeepsEdits(@TempDir Path temp) throws Exception {
        InitCommand.run(temp, out);
        Files.writeString(temp.resolve("README.md"), "mine");
        outBytes.reset();

        assertThat(InitCommand.run(temp, out)).isEqualTo(ExitCodes.OK);

        assertThat(Files.readString(temp.resolve("README.md"))).isEqualTo("mine");
        assertThat(output()).contains("skipped README.md (already exists)").doesNotContain("created ");
    }

    @Test
    @DisplayName("Should parse the settings template as YAML and print Spanish with --locale es")
    void testSettingsParseAndSpanish(@TempDir Path temp) throws Exception {
        PrintStream err = new PrintStream(new ByteArrayOutputStream());
        assertThat(IdmTest.run(new String[]{"init", temp.toString(), "--locale", "es"}, out, err))
                .isEqualTo(ExitCodes.OK);

        assertThat(output()).contains("creado README.md", "espacio de trabajo listo: ");
        Object parsed = new org.yaml.snakeyaml.Yaml()
                .load(Files.readString(temp.resolve("config/application-lab.yml")));
        // every key is commented out, so a fresh profile names no system and the guards refuse it
        assertThat(parsed).isNull();
        assertThat(Files.readString(temp.resolve("config/application-lab.yml")))
                .doesNotContainPattern("(?m)^\\s*[^#\\s]");
    }
}
