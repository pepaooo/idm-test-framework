package com.idm.test.runner;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.i18n.ReportLocale;
import com.idm.test.engine.spi.Message;
import com.idm.test.runner.ui.UiCommand;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Properties;

/** Entry point of the {@code idmtest} command. Help, version and usage errors never start Spring. */
public final class IdmTest {

    private IdmTest() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Locale locale = localeOf(args);
        CommandLine line;
        try {
            line = CommandLine.parse(args);
        } catch (UsageException e) {
            err.println("idmtest: " + Messages.render(e.detail(), locale));
            err.println(usage(locale));
            return ExitCodes.CANNOT_START;
        }
        try {
            return dispatch(line, out, err, locale);
        } catch (UsageException e) {
            err.println(Messages.render(e.detail(), locale));
            return ExitCodes.CANNOT_START;
        } catch (UncheckedIOException e) {
            Throwable cause = e.getCause();
            err.println(Messages.render(Message.of("runner.cli.ioError", line.command(), describe(cause)), locale));
            if (line.verbose()) {
                e.printStackTrace(err);
            }
            return ExitCodes.CANNOT_START;
        }
    }

    private static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static int dispatch(CommandLine line, PrintStream out, PrintStream err, Locale locale) {
        return switch (line.command()) {
            case "help" -> {
                out.println(usage(locale));
                yield ExitCodes.OK;
            }
            case "version" -> {
                out.println("idmtest " + version());
                yield ExitCodes.OK;
            }
            case "init" -> {
                Path target = line.initTarget() != null ? line.initTarget()
                        : line.workspace() != null ? line.workspace() : Path.of("");
                yield InitCommand.run(target.toAbsolutePath().normalize(), out, locale);
            }
            case "validate" -> ValidateCommand.run(line, out, err);
            case "run" -> RunCommand.run(line, out, err, Clock.systemDefaultZone());
            case "ui" -> UiCommand.run(line, out, err);
            // CommandLine.parse accepts no other command.
            default -> throw new IllegalStateException("unhandled command " + line.command());
        };
    }

    private static String usage(Locale locale) {
        return Messages.render(Message.of("runner.cli.usage"), locale);
    }

    /** The language of {@code --locale} when given and valid, else English. */
    private static Locale localeOf(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--locale")) {
                try {
                    return ReportLocale.resolve(args[i + 1]);
                } catch (ReportLocale.UnsupportedLocaleException e) {
                    return Locale.ENGLISH;
                }
            }
        }
        return Locale.ENGLISH;
    }

    public static String version() {
        Properties properties = new Properties();
        try (InputStream in = IdmTest.class.getResourceAsStream("/idmtest-version.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            // falls through to "unknown"
        }
        return properties.getProperty("version", "unknown");
    }
}
