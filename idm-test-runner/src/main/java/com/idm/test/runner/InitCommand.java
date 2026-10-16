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

/** {@code idmtest init}: writes a starter workspace and never overwrites an existing file. */
public final class InitCommand {

    /** Workspace-relative file (with {@code /} separators) to its template resource. */
    private static final Map<String, String> FILES = files();

    private InitCommand() {
    }

    private static Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("scenarios/example.yaml", "example.yaml");
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
        try {
            Files.createDirectories(target);
            for (Map.Entry<String, String> file : FILES.entrySet()) {
                Path path = target.resolve(file.getKey());
                if (Files.exists(path)) {
                    out.println(Messages.render(Message.of("runner.init.skipped", file.getKey()), locale));
                    continue;
                }
                Files.createDirectories(path.getParent());
                try (InputStream in = InitCommand.class.getResourceAsStream("init/" + file.getValue())) {
                    if (in == null) {
                        throw new IllegalStateException("missing template " + file.getValue());
                    }
                    Files.copy(in, path);
                }
                out.println(Messages.render(Message.of("runner.init.created", file.getKey()), locale));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.println(Messages.render(Message.of("runner.init.ready", target.toString()), locale));
        return ExitCodes.OK;
    }
}
