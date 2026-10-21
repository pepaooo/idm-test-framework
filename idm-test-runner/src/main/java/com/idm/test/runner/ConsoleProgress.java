package com.idm.test.runner;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.result.HtmlReportWriter;
import com.idm.test.engine.spi.FailureKind;
import com.idm.test.engine.spi.Message;

import java.io.PrintStream;
import java.util.Locale;

/** One console line per scenario and a closing summary, shared by {@code validate} and {@code run}. */
public final class ConsoleProgress {

    private final PrintStream out;
    private final Locale locale;

    public ConsoleProgress(PrintStream out, Locale locale) {
        this.out = out;
        this.locale = locale;
    }

    public void passed(String name, long millis) {
        out.println("✔ " + name + "  " + HtmlReportWriter.duration(millis, locale));
    }

    /** @param kind whether the failure is a finding or a setup problem; null adds nothing */
    public void failed(String name, long millis, String message, FailureKind kind) {
        out.println("✖ " + name + "  " + HtmlReportWriter.duration(millis, locale) + " — " + message + kind(kind));
    }

    private String kind(FailureKind kind) {
        if (kind == null) {
            return "";
        }
        String key = kind == FailureKind.FINDING ? "runner.console.kind.finding" : "runner.console.kind.setup";
        return Messages.render(Message.of(key), locale);
    }

    public void validated(String name) {
        out.println("✔ " + name);
    }

    public void invalid(String name, String message) {
        out.println("✖ " + name + " — " + message);
    }

    /** @param reportPath the report's path for a run, or null for {@code validate} (which has none) */
    public void summary(int total, int passed, int failed, String reportPath) {
        String key = reportPath == null ? "runner.console.validated" : "runner.console.summary";
        String line = Messages.render(Message.of(key, total, passed, failed), locale);
        if (reportPath != null) {
            line += Messages.render(Message.of("runner.console.report", reportPath), locale);
        }
        out.println(line);
    }
}
