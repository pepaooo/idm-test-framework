package com.idm.test.engine.result;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.FailureKind;
import com.idm.test.engine.spi.JsonMappers;
import com.idm.test.engine.spi.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders a run's results as one self-contained HTML page: inline CSS and JavaScript, nothing
 * loaded from outside, so it opens offline and travels as a single file. Every piece of text is
 * escaped, and parameter values under secret-looking keys are masked, because a report is shared
 * more widely than the JSON it is built from.
 */
public final class HtmlReportWriter {

    private static final JsonMapper JSON = JsonMappers.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private HtmlReportWriter() {
    }

    /** A label from the catalogs in the report's language. */
    static String label(String key, Locale locale, Object... args) {
        return Messages.render(Message.of(key, args), locale);
    }

    private static String started(RunInfo run) {
        return DateTimeFormatter.ofPattern(label("report.datePattern", run.locale()), run.locale())
                .withZone(ZoneId.systemDefault()).format(run.startedAt());
    }

    public static void write(RunInfo run, List<ScenarioResult> results, Path target) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, render(run, results, Instant.now()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the report to " + target, e);
        }
    }

    static String render(RunInfo run, List<ScenarioResult> results, Instant now) {
        long passed = results.stream().filter(ScenarioResult::success).count();
        long failed = results.size() - passed;
        long findings = results.stream().filter(r -> r.failureKind() == FailureKind.FINDING).count();
        long setup = results.stream().filter(r -> r.failureKind() == FailureKind.SETUP).count();
        Locale locale = run.locale();
        String title = label("report.title", locale, run.runner());

        StringBuilder html = new StringBuilder(16_384);
        html.append("<!DOCTYPE html>\n<html lang=\"")
                .append(locale.getLanguage()).append("\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(esc(title)).append("</title>\n<style>").append(CSS).append("</style>\n")
                .append("</head>\n<body>\n<header>\n<h1>").append(esc(title)).append("</h1>\n");

        String none = label("report.none", locale);
        html.append("<p class=\"meta\">").append(label("report.started", locale)).append(' ')
                .append(esc(started(run)))
                .append(" · ").append(label("report.duration", locale)).append(' ')
                .append(esc(duration(Duration.between(run.startedAt(), now).toMillis(), locale)))
                .append(" · ").append(label("report.profiles", locale)).append(' ')
                .append(esc(run.profiles().isEmpty() ? none : String.join(", ", run.profiles())))
                .append(" · ").append(label("report.filter", locale)).append(' ')
                .append(esc(run.scenarioFilter() == null ? none : run.scenarioFilter()))
                .append("</p>\n");

        html.append("<details class=\"endpoints\"><summary>")
                .append(label("report.endpoints", locale)).append(" (").append(run.endpoints().size())
                .append(")</summary>\n");
        if (run.endpoints().isEmpty()) {
            html.append("<p class=\"muted\">")
                    .append(label("report.noneRecorded", locale)).append("</p>\n");
        } else {
            html.append("<table>\n");
            run.endpoints().forEach((label, url) -> html.append("<tr><th>").append(esc(label))
                    .append("</th><td>").append(esc(url)).append("</td></tr>\n"));
            html.append("</table>\n");
        }
        html.append("</details>\n");

        long total = results.stream().mapToLong(ScenarioResult::durationMillis).sum();
        html.append("<div class=\"tiles\">")
                .append(tile("scenarios", label("report.tile.scenarios", locale), String.valueOf(results.size())))
                .append(tile("passed", label("report.tile.passed", locale), String.valueOf(passed)))
                .append(tile("failed", label("report.tile.failed", locale), String.valueOf(failed)))
                .append(tile("findings", label("report.tile.findings", locale), String.valueOf(findings)))
                .append(tile("setup", label("report.tile.setup", locale), String.valueOf(setup)))
                .append(tile("duration", label("report.tile.duration", locale), duration(total, locale)))
                .append("</div>\n");

        html.append("<div class=\"controls\">")
                .append("<button type=\"button\" onclick=\"setAll(true)\">")
                .append(label("report.expandAll", locale)).append("</button>")
                .append("<button type=\"button\" onclick=\"setAll(false)\">")
                .append(label("report.collapseAll", locale)).append("</button>")
                .append("<button type=\"button\" aria-pressed=\"false\" onclick=\"onlyFailed(this)\">")
                .append(label("report.onlyFailed", locale)).append("</button>")
                .append("</div>\n</header>\n<main>\n");

        for (ScenarioResult scenario : results) {
            appendScenario(html, scenario, locale);
        }

        html.append("</main>\n<script>").append(JS).append("</script>\n</body>\n</html>\n");
        return html.toString();
    }

    private static void appendScenario(StringBuilder html, ScenarioResult scenario, Locale locale) {
        String status = scenario.success() ? "passed" : "failed";
        html.append("<details class=\"scenario ").append(status).append('"')
                .append(scenario.success() ? "" : " open").append(">\n<summary>")
                .append(icon(scenario.success()))
                .append(kind(scenario.failureKind(), locale))
                .append(" <span class=\"file\">").append(esc(scenario.sourceName())).append("</span>");
        if (scenario.name() != null && !scenario.name().equals(scenario.sourceName())) {
            html.append(" <span class=\"name\">").append(esc(scenario.name())).append("</span>");
        }
        if (scenario.description() != null) {
            html.append(" <span class=\"desc\">").append(esc(scenario.description())).append("</span>");
        }
        html.append(" <span class=\"time\">").append(esc(duration(scenario.durationMillis(), locale))).append("</span>")
                .append("</summary>\n");

        if (!scenario.success()) {
            html.append("<p class=\"failure\">").append(esc(scenario.failureSummary(locale))).append("</p>\n");
        }

        if (!scenario.steps().isEmpty()) {
            html.append("<ol class=\"steps\">\n");
            for (StepResult step : scenario.steps()) {
                appendStep(html, step, locale);
                if (!step.success()) {
                    html.append("<li class=\"not-run\">")
                            .append(label("report.notRun", locale)).append("</li>\n");
                }
            }
            html.append("</ol>\n");
        }

        if (!scenario.cleanup().isEmpty()) {
            html.append("<h3>").append(label("report.cleanup", locale)).append("</h3>\n<ul class=\"cleanup\">\n");
            for (CleanupResult entry : scenario.cleanup()) {
                String entryStatus = entry.status().name();
                String statusLabel = switch (entry.status()) {
                    case SUCCEEDED -> label("report.status.succeeded", locale);
                    case FAILED -> label("report.status.failed", locale);
                    case SKIPPED -> label("report.status.skipped", locale);
                };
                html.append("<li class=\"").append(entryStatus.toLowerCase(Locale.ROOT)).append("\">")
                        .append("<span class=\"status\">").append(statusLabel).append("</span> ")
                        .append("<span class=\"type\">").append(esc(entry.type())).append("</span>");
                if (entry.status() == CleanupStatus.FAILED) {
                    // Every failed cleanup entry is a finding: something may be left in the lab.
                    html.append(kind(FailureKind.FINDING, locale));
                }
                if (entry.description() != null) {
                    html.append(" <span class=\"desc\">").append(esc(entry.description())).append("</span>");
                }
                html.append(" <span class=\"time\">").append(esc(duration(entry.durationMillis(), locale))).append("</span>");
                if (entry.message() != null) {
                    html.append("<div class=\"message\">")
                            .append(esc(text(entry.message(), entry.messageDetail(), locale))).append("</div>");
                }
                html.append("</li>\n");
            }
            html.append("</ul>\n");
        }
        html.append("</details>\n");
    }

    private static void appendStep(StringBuilder html, StepResult step, Locale locale) {
        FailureKind kind = step.kind();
        html.append("<li class=\"step ").append(step.success() ? "passed" : "failed")
                .append(kind == null ? "" : " " + cssClass(kind)).append("\">")
                .append("<div class=\"row\">").append(icon(step.success())).append(kind(kind, locale))
                .append(" <span class=\"num\">").append(step.index() + 1).append("</span>")
                .append(" <span class=\"type\">").append(esc(step.type())).append("</span>");
        if (step.description() != null) {
            html.append(" <span class=\"desc\">").append(esc(step.description())).append("</span>");
        }
        html.append(" <span class=\"time\">");
        if (step.attempts() != null) {
            html.append(esc(label(step.attempts() == 1 ? "report.attempts.one" : "report.attempts.other",
                    locale, step.attempts()) + " · "
                    + duration(step.waitedMillis() == null ? 0 : step.waitedMillis(), locale)));
        } else {
            html.append(esc(duration(step.durationMillis(), locale)));
        }
        html.append("</span></div>\n");
        if (step.message() != null) {
            html.append("<div class=\"message\">")
                    .append(esc(text(step.message(), step.messageDetail(), locale))).append("</div>\n");
        }
        if (step.resolvedParams() != null && !step.resolvedParams().isEmpty()) {
            html.append("<details class=\"params\"><summary>")
                    .append(label("report.parameters", locale)).append("</summary><pre>")
                    .append(esc(json(mask(step.resolvedParams())))).append("</pre></details>\n");
        }
        EvidenceHtml.append(html, step, locale);
        html.append("</li>\n");
    }

    /** A message in the report's language when it has a key; as recorded when it is plain text. */
    static String text(String english, Message detail, Locale locale) {
        return detail == null ? english : Messages.render(detail, locale);
    }

    /** A copy with every value under a secret-looking key, at any depth, replaced by {@code ***}. */
    static Object mask(Object value) {
        return Secrets.maskByKey(value);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }

    private static String tile(String key, String label, String value) {
        return "<div class=\"tile " + key + "\"><div class=\"label\">" + label + "</div>"
                + "<div class=\"value\" data-tile=\"" + key + "\">" + esc(value) + "</div></div>";
    }

    /** The kind's label as a badge, preceded by a space; empty for null (nothing failed). */
    private static String kind(FailureKind kind, Locale locale) {
        if (kind == null) {
            return "";
        }
        String key = kind == FailureKind.FINDING ? "report.kind.finding" : "report.kind.setup";
        return " <span class=\"kind " + cssClass(kind) + "\">" + label(key, locale) + "</span>";
    }

    private static String cssClass(FailureKind kind) {
        return kind == FailureKind.FINDING ? "finding" : "setup";
    }

    private static String icon(boolean success) {
        return success ? "<span class=\"icon ok\">✔</span>" : "<span class=\"icon ko\">✖</span>";
    }

    /** 850 ms, 10.1 s (10,1 s in Spanish), 1m 42s. */
    public static String duration(long millis, Locale locale) {
        if (millis < 1000) {
            return millis + " ms";
        }
        if (millis < 60_000) {
            return String.format(locale, "%.1f s", millis / 1000.0);
        }
        long seconds = millis / 1000;
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    static String duration(long millis) {
        return duration(millis, Locale.ENGLISH);
    }

    static String esc(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final String CSS = """
            :root{--bg:#fff;--fg:#1f2328;--muted:#656d76;--line:#d0d7de;--ok:#1a7f37;--okbg:#eef8f0;\
            --ko:#cf222e;--kobg:#fdeeee;--card:#f6f8fa;--finding:#e07b25;--findingbg:#fdf3e9;}
            @media (prefers-color-scheme: dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#8d96a0;--line:#30363d;\
            --ok:#3fb950;--okbg:#12261a;--ko:#f85149;--kobg:#2d1416;--card:#161b22;--finding:#f2994a;--findingbg:#2b1d10;}}
            body{background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,-apple-system,Segoe UI,sans-serif;\
            margin:0 auto;max-width:1100px;padding:16px}
            h1{font-size:22px;margin:0 0 4px}h3{font-size:14px;margin:12px 0 4px}
            .meta,.muted,.time,.num{color:var(--muted)}
            details.endpoints{margin:8px 0}details.endpoints table{border-collapse:collapse}
            details.endpoints th,details.endpoints td{text-align:left;padding:2px 12px 2px 0;border-bottom:1px solid var(--line)}
            .tiles{display:flex;gap:12px;margin:12px 0;flex-wrap:wrap}
            .tile{flex:1;min-width:120px;border:1px solid var(--line);border-radius:8px;padding:8px 12px;background:var(--card)}
            .tile .label{color:var(--muted);font-size:12px}.tile .value{font-size:20px;font-weight:600}
            .tile.passed{border-color:var(--ok);background:var(--okbg)}.tile.failed{border-color:var(--ko);background:var(--kobg)}
            .controls{margin:8px 0 16px}.controls button{margin-right:8px;padding:4px 10px;border:1px solid var(--line);\
            border-radius:6px;background:var(--card);color:var(--fg);cursor:pointer}
            .controls button[aria-pressed=true]{border-color:var(--ko)}
            details.scenario{border:1px solid var(--line);border-radius:8px;margin:8px 0;padding:0 12px}
            details.scenario.failed{border-color:var(--ko)}details.scenario>summary{padding:8px 0;cursor:pointer}
            details.scenario.failed>summary{color:var(--ko)}
            .file{font-weight:600}.desc{font-style:italic}.time{float:right}
            .icon.ok{color:var(--ok)}.icon.ko{color:var(--ko)}
            .failure{background:var(--kobg);color:var(--ko);padding:6px 8px;border-radius:6px;white-space:pre-wrap}
            ol.steps{padding-left:0;list-style:none}ol.steps li{border-top:1px solid var(--line);padding:6px 0}
            li.step.failed{background:var(--kobg)}li.not-run{color:var(--muted);font-style:italic}
            .message{margin-left:22px;white-space:pre-wrap;word-break:break-word}li.step.failed .message{color:var(--ko)}
            .kind{font-size:11px;font-weight:600;padding:1px 6px;border-radius:4px;border:1px solid currentColor}
            .kind.finding{color:var(--finding)}.kind.setup{color:var(--ko)}
            .tile.findings{border-color:var(--finding)}.tile.setup{border-color:var(--ko)}
            li.step.failed.finding{background:var(--findingbg)}li.step.failed.finding .message{color:var(--finding)}
            details.scenario.failed:has(>summary .kind.finding){border-color:var(--finding)}
            details.scenario.failed:has(>summary .kind.finding)>summary{color:var(--finding)}
            details.scenario.failed:has(>summary .kind.finding)>.failure{background:var(--findingbg);color:var(--finding)}
            details.params{margin-left:22px}details.params pre{background:var(--card);padding:8px;border-radius:6px;overflow:auto}
            ul.cleanup{padding-left:0;list-style:none}ul.cleanup li{padding:4px 0;border-top:1px dashed var(--line)}
            .status{font-size:11px;font-weight:600;padding:1px 6px;border-radius:4px;border:1px solid var(--line)}
            li.succeeded .status{color:var(--ok)}li.failed .status{color:var(--ko)}li.skipped .status{color:var(--muted)}
            details.evidence{margin-left:22px}details.evidence>summary{cursor:pointer}
            details.evidence pre{background:var(--card);padding:8px;border-radius:6px;overflow:auto;\
            white-space:pre-wrap;word-break:break-word;margin:4px 0}
            .http{display:grid;grid-template-columns:1fr 1fr;gap:8px}.http .label{font-size:12px;color:var(--muted)}
            @media (max-width:700px){.http{grid-template-columns:1fr}}
            table.rows{border-collapse:collapse;margin:4px 0}table.rows th,table.rows td{border:1px solid var(--line);\
            padding:2px 6px;text-align:left;vertical-align:top;word-break:break-word;overflow-wrap:anywhere}
            .more,.sql-params,.dn{color:var(--muted);font-size:12px}
            @media print{.controls{display:none}details.scenario{break-inside:avoid-page}}
            """;

    private static final String JS = """
            function setAll(open){document.querySelectorAll('details.scenario, details.evidence').forEach(function(d){d.open=open;});}
            function onlyFailed(button){var on=button.getAttribute('aria-pressed')!=='true';
            button.setAttribute('aria-pressed',String(on));
            document.querySelectorAll('details.scenario.passed').forEach(function(d){d.hidden=on;});}
            window.addEventListener('beforeprint',function(){document.querySelectorAll('details').forEach(function(d){d.open=true;});
document.querySelectorAll('details.scenario, details.evidence').forEach(function(d){d.hidden=false;});});
            """;
}
