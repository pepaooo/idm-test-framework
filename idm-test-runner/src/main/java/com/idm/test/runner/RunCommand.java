package com.idm.test.runner;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.i18n.ReportLocale;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.Message;
import com.idm.test.runner.run.RunEvents;
import com.idm.test.runner.run.RunOutcome;
import com.idm.test.runner.run.RunRequest;
import com.idm.test.runner.run.RunService;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code idmtest run}: runs a workspace's scenarios against the systems of a profile, through
 * {@link RunService}, printing one line per scenario and a summary on {@code out} and every problem on
 * {@code err}. This class checks the options, then hands the run to the service and prints what it hears.
 */
public final class RunCommand {

    /** Concatenated so CatalogTest, which reads {@code "report.*"} literals as message keys, skips it. */
    private static final String REPORT_FILE = "report" + ".html";

    private RunCommand() {
    }

    public static int run(CommandLine line, PrintStream out, PrintStream err, Clock clock) {
        return run(line, out, err, clock, null);
    }

    /**
     * The run, with {@code environment} standing in for the process's environment variables when it
     * is not null: tests hand in their own, and Spring then sees it in place of the real one.
     */
    static int run(CommandLine line, PrintStream out, PrintStream err, Clock clock, Map<String, String> environment) {
        Map<String, String> variables = environment == null ? System.getenv() : Map.copyOf(environment);
        // --locale, then IDM_TEST_LOCALE, then -Didm.test.locale: checked here, so a wrong one or a
        // missing --profile is reported in the right words; the service resolves the same chain.
        String localeTag = firstNonBlank(line.locale(), variables.get("IDM_TEST_LOCALE"),
                System.getProperty("idm.test.locale"));
        Locale early;
        try {
            early = ReportLocale.resolve(localeTag);
        } catch (ReportLocale.UnsupportedLocaleException e) {
            if (line.profile() == null) {
                err.println(Messages.render(Message.of("runner.run.needsProfile"), Locale.ENGLISH));
            } else {
                err.println(e.getMessage());
            }
            return ExitCodes.CANNOT_START;
        }
        if (line.profile() == null) {
            err.println(Messages.render(Message.of("runner.run.needsProfile"), early));
            return ExitCodes.CANNOT_START;
        }
        Locale requested = line.locale() == null || line.locale().isBlank() ? null : early;
        RunRequest request = new RunRequest(line.workspace(), line.profile(),
                line.scenario() == null ? List.of() : List.of(line.scenario()), requested, environment);
        Console console = new Console(line, out, err);
        return new RunService(clock, line.verbose()).run(request, console, () -> false).exitCode();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /** What the service tells the command, printed as {@code idmtest run} always has. */
    private static final class Console implements RunEvents {

        private final CommandLine line;
        private final PrintStream out;
        private final PrintStream err;
        private ConsoleProgress progress;

        Console(CommandLine line, PrintStream out, PrintStream err) {
            this.line = line;
            this.out = out;
            this.err = err;
        }

        @Override
        public void warning(Message warning, String text) {
            err.println(text);
        }

        @Override
        public void refused(Message reason, String text, String stackTrace) {
            err.println(text);
            if (line.verbose() && stackTrace != null) {
                err.print(stackTrace);
            }
        }

        @Override
        public void runStarted(String runId, List<String> scenarios, Locale locale) {
            progress = new ConsoleProgress(out, locale);
        }

        @Override
        public void scenarioFinished(ScenarioResult result, String failureLine) {
            if (result.success()) {
                progress.passed(result.sourceName(), result.durationMillis());
            } else {
                progress.failed(result.sourceName(), result.durationMillis(), failureLine, result.failureKind());
            }
        }

        @Override
        public void runFinished(RunOutcome outcome) {
            Path root = Workspace.open(line.workspace()).root();
            String report = root.relativize(outcome.folder().resolve(REPORT_FILE)).toString().replace('\\', '/');
            progress.summary(outcome.total(), outcome.passed(), outcome.total() - outcome.passed(), report);
        }
    }
}
