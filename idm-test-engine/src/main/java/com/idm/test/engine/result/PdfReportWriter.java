package com.idm.test.engine.result;

import com.idm.test.engine.result.PdfPages.Cell;
import com.idm.test.engine.result.PdfPages.Style;
import com.idm.test.engine.spi.FailureKind;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a finished run as a PDF on request. The summary is the header every PDF opens with: the
 * run, its totals, its endpoints and one line per scenario. Nothing is written to disk here; the
 * caller gets the bytes.
 */
public final class PdfReportWriter {

    /** Which PDF to produce. */
    public enum Kind {
        SUMMARY("summary", "pdf.kind.summary"),
        DETAILED("detailed", "pdf.kind.detailed");

        private final String word;
        private final String labelKey;

        Kind(String word, String labelKey) {
            this.word = word;
            this.labelKey = labelKey;
        }

        /**
         * @param value {@code summary} or {@code detailed}
         * @throws IllegalArgumentException for anything else, including null
         */
        public static Kind parse(String value) {
            for (Kind kind : values()) {
                if (kind.word.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown report kind '" + value + "': use summary or detailed");
        }
    }

    private PdfReportWriter() {
    }

    /** {@code <runId>-summary.pdf} or {@code <runId>-detailed.pdf}. */
    public static String fileName(String runId, Kind kind) {
        return runId + "-" + kind.word + ".pdf";
    }

    /**
     * @param creator the producing program, e.g. {@code idmtest 1.2.0}, for the document information
     */
    public static byte[] render(RunFolder folder, Kind kind, String creator) {
        RunInfo run = folder.run();
        Locale locale = run.locale();
        String notRecorded = HtmlReportWriter.label("pdf.notRecorded", locale);
        String report = HtmlReportWriter.label("report.title", locale,
                run.runner() == null ? notRecorded : run.runner());
        String kindLabel = HtmlReportWriter.label(kind.labelKey, locale);
        String title = report + " · " + kindLabel;

        try (PdfPages pages = new PdfPages(report + " · " + folder.runId() + " · " + kindLabel, locale)) {
            pages.info(title, creator);
            pages.title(title);
            header(pages, folder, locale, notRecorded);
            if (kind == Kind.DETAILED) {
                for (ScenarioResult scenario : folder.results()) {
                    pages.newPage();
                    scenario(pages, scenario, locale);
                }
            }
            return pages.finish();
        }
    }

    private static void header(PdfPages pages, RunFolder folder, Locale locale, String notRecorded) {
        RunInfo run = folder.run();
        List<ScenarioResult> results = folder.results();
        boolean startKnown = !Instant.EPOCH.equals(run.startedAt());
        String none = HtmlReportWriter.label("report.none", locale);

        pages.table(List.of(
                row(HtmlReportWriter.label("pdf.runId", locale), folder.runId()),
                row(HtmlReportWriter.label("report.started", locale),
                        startKnown ? HtmlReportWriter.started(run) : notRecorded),
                row(HtmlReportWriter.label("report.duration", locale), startKnown
                        ? HtmlReportWriter.duration(Duration.between(run.startedAt(), folder.finishedAt()).toMillis(),
                        locale) : notRecorded),
                row(HtmlReportWriter.label("report.profiles", locale),
                        run.profiles().isEmpty() ? none : String.join(", ", run.profiles())),
                row(HtmlReportWriter.label("report.filter", locale),
                        run.scenarioFilter() != null ? run.scenarioFilter()
                                : folder.recorded() ? none : notRecorded)), 0.25f, 0.75f);

        HtmlReportWriter.Totals totals = HtmlReportWriter.Totals.of(results);
        pages.tiles(List.of(
                Map.entry(HtmlReportWriter.label("report.tile.scenarios", locale), String.valueOf(totals.scenarios())),
                Map.entry(HtmlReportWriter.label("report.tile.passed", locale), String.valueOf(totals.passed())),
                Map.entry(HtmlReportWriter.label("report.tile.failed", locale), String.valueOf(totals.failed())),
                Map.entry(HtmlReportWriter.label("report.tile.findings", locale), String.valueOf(totals.findings())),
                Map.entry(HtmlReportWriter.label("report.tile.setup", locale), String.valueOf(totals.setup())),
                Map.entry(HtmlReportWriter.label("report.tile.stopped", locale), String.valueOf(totals.stopped())),
                Map.entry(HtmlReportWriter.label("report.tile.duration", locale),
                        HtmlReportWriter.duration(totals.durationMillis(), locale))));

        pages.heading(HtmlReportWriter.label("pdf.environment", locale));
        if (run.endpoints().isEmpty()) {
            pages.muted(HtmlReportWriter.label("report.noneRecorded", locale));
        } else {
            List<List<Cell>> rows = new ArrayList<>();
            run.endpoints().forEach((label, url) -> rows.add(row(label, url)));
            pages.table(rows, 0.3f, 0.7f);
        }

        pages.heading(HtmlReportWriter.label("pdf.scenarios", locale));
        for (ScenarioResult scenario : results) {
            pages.table(List.of(scenarioRow(scenario, locale)), 0.05f, 0.17f, 0.30f, 0.36f, 0.12f);
            if (!scenario.success()) {
                // On its own wrapped line: a table row cannot split across pages.
                pages.muted(scenario.failureSummary(locale));
            }
        }
    }

    private static void scenario(PdfPages pages, ScenarioResult scenario, Locale locale) {
        String name = scenario.name() != null && !scenario.name().equals(scenario.sourceName())
                ? scenario.sourceName() + " · " + scenario.name() : scenario.sourceName();
        pages.heading((scenario.success() ? "✔ " : "✘ ") + name);
        List<String> facts = new ArrayList<>();
        String badge = scenarioBadge(scenario, locale);
        if (!badge.isEmpty()) {
            facts.add(badge);
        }
        if (scenario.description() != null) {
            facts.add(scenario.description());
        }
        facts.add(HtmlReportWriter.duration(scenario.durationMillis(), locale));
        pages.muted(String.join(" · ", facts));
        if (!scenario.success()) {
            pages.paragraph(scenario.failureSummary(locale));
        }

        for (StepResult step : scenario.steps()) {
            step(pages, step, locale);
            if (!step.success()) {
                pages.muted(HtmlReportWriter.label("report.notRun", locale));
            }
        }

        if (!scenario.cleanup().isEmpty()) {
            pages.heading(HtmlReportWriter.label("report.cleanup", locale));
            for (CleanupResult entry : scenario.cleanup()) {
                cleanup(pages, entry, locale);
            }
        }
    }

    private static void step(PdfPages pages, StepResult step, Locale locale) {
        // The step a stop interrupted: the engine marks it SETUP, but it is no setup problem.
        boolean interrupted = !step.success() && ScenarioResult.STOPPED_KEY.equals(step.messageKey());
        String badge = interrupted ? HtmlReportWriter.label("report.kind.stopped", locale)
                : kindLabel(step.kind(), locale);
        String time = step.attempts() != null
                ? HtmlReportWriter.label(step.attempts() == 1 ? "report.attempts.one" : "report.attempts.other",
                locale, step.attempts()) + " · "
                + HtmlReportWriter.duration(step.waitedMillis() == null ? 0 : step.waitedMillis(), locale)
                : HtmlReportWriter.duration(step.durationMillis(), locale);
        // Only short, bounded values in the row: a table row cannot split across pages.
        pages.table(List.of(List.of(
                new Cell(step.success() ? "✔" : "✘", step.success() ? Style.PASS : Style.FAIL),
                new Cell(badge, step.success() ? Style.NORMAL : Style.BADGE),
                new Cell(String.valueOf(step.index() + 1), Style.MUTED),
                new Cell(step.type(), Style.BOLD),
                new Cell(time, Style.MUTED))), 0.05f, 0.17f, 0.06f, 0.42f, 0.30f);
        if (step.description() != null) {
            pages.paragraph(step.description());
        }
        if (step.message() != null) {
            pages.paragraph(HtmlReportWriter.text(step.message(), step.messageDetail(), locale));
        }
        if (step.resolvedParams() != null && !step.resolvedParams().isEmpty()) {
            pages.muted(HtmlReportWriter.label("report.parameters", locale));
            pages.mono(EvidenceHtml.pretty(HtmlReportWriter.mask(step.resolvedParams())));
        }
        EvidencePdf.append(pages, step, locale);
    }

    private static void cleanup(PdfPages pages, CleanupResult entry, Locale locale) {
        boolean failed = entry.status() == CleanupStatus.FAILED;
        String status = HtmlReportWriter.label(switch (entry.status()) {
            case SUCCEEDED -> "report.status.succeeded";
            case FAILED -> "report.status.failed";
            case SKIPPED -> "report.status.skipped";
        }, locale);
        pages.table(List.of(List.of(
                new Cell(status, failed ? Style.BADGE : Style.BOLD),
                // Every failed cleanup entry is a finding: something may be left in the lab.
                new Cell(failed ? kindLabel(FailureKind.FINDING, locale) : "", failed ? Style.BADGE : Style.NORMAL),
                new Cell(entry.type(), Style.BOLD),
                new Cell(HtmlReportWriter.duration(entry.durationMillis(), locale), Style.MUTED))),
                0.2f, 0.17f, 0.43f, 0.20f);
        if (entry.description() != null) {
            pages.paragraph(entry.description());
        }
        if (entry.message() != null) {
            pages.paragraph(HtmlReportWriter.text(entry.message(), entry.messageDetail(), locale));
        }
    }

    private static String scenarioBadge(ScenarioResult scenario, Locale locale) {
        if (scenario.success()) {
            return "";
        }
        return scenario.stoppedOnly() ? HtmlReportWriter.label("report.kind.stopped", locale)
                : kindLabel(scenario.failureKind(), locale);
    }

    /** The kind's label; empty for null (nothing failed). */
    private static String kindLabel(FailureKind kind, Locale locale) {
        if (kind == null) {
            return "";
        }
        return HtmlReportWriter.label(kind == FailureKind.FINDING ? "report.kind.finding" : "report.kind.setup", locale);
    }

    private static List<Cell> row(String label, String value) {
        return List.of(new Cell(label, Style.BOLD), new Cell(value, Style.NORMAL));
    }

    private static List<Cell> scenarioRow(ScenarioResult scenario, Locale locale) {
        String badge = scenarioBadge(scenario, locale);
        String name = scenario.name() != null && !scenario.name().equals(scenario.sourceName())
                ? scenario.sourceName() + " · " + scenario.name() : scenario.sourceName();
        return List.of(
                new Cell(scenario.success() ? "✔" : "✘", scenario.success() ? Style.PASS : Style.FAIL),
                new Cell(badge, scenario.success() ? Style.NORMAL : Style.BADGE),
                new Cell(name, Style.BOLD),
                new Cell(scenario.description() == null ? "" : scenario.description(), Style.NORMAL),
                new Cell(HtmlReportWriter.duration(scenario.durationMillis(), locale), Style.MUTED));
    }
}
