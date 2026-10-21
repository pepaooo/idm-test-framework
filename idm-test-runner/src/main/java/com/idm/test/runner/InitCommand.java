package com.idm.test.runner;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/** {@code idmtest init}: writes a starter workspace and never overwrites an existing file. */
public final class InitCommand {

    private static final String EXAMPLE = "scenarios/example.yaml";

    /** Workspace-relative file (with {@code /} separators) to its template resource. */
    private static final Map<String, String> FILES = files();

    private InitCommand() {
    }

    private static Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(EXAMPLE, "example.yaml");
        files.put("config/application-lab.yml", "application-lab.yml");
        files.put("secrets.env.example", "secrets.env.example");
        files.put("drivers/README.md", "drivers-README.md");
        files.put(".gitignore", "gitignore");
        files.put("README.md", "README.md");
        return files;
    }

    public static int run(Path target, PrintStream out) {
        return run(target, out, Locale.ENGLISH);
    }

    public static int run(Path target, PrintStream out, Locale locale) {
        write(target, true, (file, created) -> out.println(Messages.render(
                Message.of(created ? "runner.init.created" : "runner.init.skipped", file), locale)));
        out.println(Messages.render(Message.of("runner.init.ready", target.toString()), locale));
        return ExitCodes.OK;
    }

    /**
     * Writes the starter workspace into {@code target} as {@code idmtest init} does, printing nothing;
     * without {@code includeExample}, {@code scenarios/} is left empty. Never overwrites a file.
     *
     * @throws UncheckedIOException when a folder or file cannot be written
     */
    public static void create(Path target, boolean includeExample) {
        write(target, includeExample, (file, created) -> {
        });
        try {
            Files.createDirectories(target.resolve("scenarios"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes each starter file that does not exist yet, telling {@code done} its name and whether it wrote it. */
    private static void write(Path target, boolean includeExample, BiConsumer<String, Boolean> done) {
        try {
            Files.createDirectories(target);
            for (Map.Entry<String, String> file : FILES.entrySet()) {
                if (!includeExample && file.getKey().equals(EXAMPLE)) {
                    continue;
                }
                Path path = target.resolve(file.getKey());
                if (Files.exists(path)) {
                    done.accept(file.getKey(), false);
                    continue;
                }
                Files.createDirectories(path.getParent());
                try (InputStream in = InitCommand.class.getResourceAsStream("init/" + file.getValue())) {
                    if (in == null) {
                        throw new IllegalStateException("missing template " + file.getValue());
                    }
                    Files.copy(in, path);
                }
                done.accept(file.getKey(), true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
