package com.idm.test.runner;

import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.ValidationException;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.i18n.ReportLocale;
import com.idm.test.engine.spi.Message;
import com.idm.test.wiring.DirectoryScenarioSource;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.ScenarioGlob;
import com.idm.test.wiring.StepRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/** {@code idmtest validate}: loads and validates scenarios; starts no Spring context and connects to nothing. */
public final class ValidateCommand {

    private ValidateCommand() {
    }

    public static int run(CommandLine line, PrintStream out, PrintStream err) {
        Locale locale;
        try {
            locale = ReportLocale.resolve(line.locale(), System.getenv("IDM_TEST_LOCALE"),
                    System.getProperty("idm.test.locale"));
        } catch (ReportLocale.UnsupportedLocaleException e) {
            err.println(e.getMessage());
            return ExitCodes.CANNOT_START;
        }
        try {
            Workspace workspace = Workspace.open(line.workspace());
            if (line.profile() != null) {
                Workspace.requireProfile(workspace, line.profile());
            }
            DirectoryScenarioSource source = new DirectoryScenarioSource(workspace.scenarios());
            List<String> names = select(source, line.scenario());
            return validate(source, names, out, locale);
        } catch (UsageException e) {
            err.println(Messages.render(e.detail(), locale));
            return ExitCodes.CANNOT_START;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The scenarios {@code glob} selects; an invalid glob or no match is a {@link UsageException}. */
    static List<String> select(DirectoryScenarioSource source, String glob) throws IOException {
        List<String> names;
        try {
            names = ScenarioGlob.select(source.names(), glob);
        } catch (ScenarioGlob.InvalidPatternException e) {
            throw new UsageException(Message.of("runner.scenario.invalid", e.pattern(), e.reason()));
        }
        if (names.isEmpty()) {
            throw new UsageException(glob == null || glob.isBlank()
                    ? Message.of("runner.guard.noScenariosIn", source.describe())
                    : Message.of("runner.scenario.noMatch", glob));
        }
        return names;
    }

    private static int validate(DirectoryScenarioSource source, List<String> names, PrintStream out,
                                Locale locale) throws IOException {
        ScenarioValidator validator = EngineFactory.validator(StepRegistry.forValidation());
        ConsoleProgress progress = new ConsoleProgress(out, locale);
        int valid = 0;
        for (String name : names) {
            try (InputStream in = source.open(name)) {
                Scenario scenario = ScenarioLoader.load(in, name);
                validator.validate(scenario, name);
                progress.validated(name);
                valid++;
            } catch (ValidationException e) {
                progress.invalid(name, e.detail() != null ? Messages.render(e.detail(), locale) : e.getMessage());
            }
        }
        progress.summary(names.size(), valid, names.size() - valid, null);
        return valid == names.size() ? ExitCodes.OK : ExitCodes.FAILED;
    }
}
