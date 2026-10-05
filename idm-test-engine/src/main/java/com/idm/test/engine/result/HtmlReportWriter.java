package com.idm.test.engine.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

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
import java.util.Map;

/**
 * Renders a run's results as one self-contained HTML page: inline CSS and JavaScript, nothing
 * loaded from outside, so it opens offline and travels as a single file. Every piece of text is
 * escaped, and parameter values under secret-looking keys are masked, because a report is shared
 * more widely than the JSON it is built from.
 */
public final class HtmlReportWriter {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter STARTED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private HtmlReportWriter() {
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
        String title = run.runner() + " run";

        StringBuilder html = new StringBuilder(16_384);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(esc(title)).append("</title>\n<style>").append(CSS).append("</style>\n")
                .append("</head>\n<body>\n<header>\n<h1>").append(esc(title)).append("</h1>\n");

        html.append("<p class=\"meta\">Started ").append(esc(STARTED.format(run.startedAt())))
                .append(" · Duration ").append(esc(duration(Duration.between(run.startedAt(), now).toMillis())))
                .append(" · Profiles ").append(esc(run.profiles().isEmpty() ? "none" : String.join(", ", run.profiles())))
                .append(" · Filter ").append(esc(run.scenarioFilter() == null ? "none" : run.scenarioFilter()))
                .append("</p>\n");

        html.append("<details class=\"endpoints\"><summary>Endpoints (").append(run.endpoints().size())
                .append(")</summary>\n");
        if (run.endpoints().isEmpty()) {
            html.append("<p class=\"muted\">None recorded.</p>\n");
        } else {
            html.append("<table>\n");
            run.endpoints().forEach((label, url) -> html.append("<tr><th>").append(esc(label))
                    .append("</th><td>").append(esc(url)).append("</td></tr>\n"));
            html.append("</table>\n");
        }
        html.append("</details>\n");

        long total = results.stream().mapToLong(ScenarioResult::durationMillis).sum();
        html.append("<div class=\"tiles\">")
                .append(tile("scenarios", "Scenarios", String.valueOf(results.size())))
                .append(tile("passed", "Passed", String.valueOf(passed)))
                .append(tile("failed", "Failed", String.valueOf(failed)))
                .append(tile("duration", "Time in scenarios", duration(total)))
                .append("</div>\n");

        html.append("<div class=\"controls\">")
                .append("<button type=\"button\" onclick=\"setAll(true)\">Expand all</button>")
                .append("<button type=\"button\" onclick=\"setAll(false)\">Collapse all</button>")
                .append("<button type=\"button\" aria-pressed=\"false\" onclick=\"onlyFailed(this)\">Only failed</button>")
                .append("</div>\n</header>\n<main>\n");

        for (ScenarioResult scenario : results) {
            appendScenario(html, scenario);
        }

        html.append("</main>\n<script>").append(JS).append("</script>\n</body>\n</html>\n");
        return html.toString();
    }

    private static void appendScenario(StringBuilder html, ScenarioResult scenario) {
        String status = scenario.success() ? "passed" : "failed";
        html.append("<details class=\"scenario ").append(status).append('"')
                .append(scenario.success() ? "" : " open").append(">\n<summary>")
                .append(icon(scenario.success()))
                .append(" <span class=\"file\">").append(esc(scenario.sourceName())).append("</span>");
        if (scenario.name() != null && !scenario.name().equals(scenario.sourceName())) {
            html.append(" <span class=\"name\">").append(esc(scenario.name())).append("</span>");
        }
        if (scenario.description() != null) {
            html.append(" <span class=\"desc\">").append(esc(scenario.description())).append("</span>");
        }
        html.append(" <span class=\"time\">").append(esc(duration(scenario.durationMillis()))).append("</span>")
                .append("</summary>\n");

        if (!scenario.success()) {
            html.append("<p class=\"failure\">").append(esc(scenario.failureSummary())).append("</p>\n");
        }

        if (!scenario.steps().isEmpty()) {
            html.append("<ol class=\"steps\">\n");
            for (StepResult step : scenario.steps()) {
                appendStep(html, step);
                if (!step.success()) {
                    html.append("<li class=\"not-run\">Any later steps were not run.</li>\n");
                }
            }
            html.append("</ol>\n");
        }

        if (!scenario.cleanup().isEmpty()) {
            html.append("<h3>Cleanup</h3>\n<ul class=\"cleanup\">\n");
            for (CleanupResult entry : scenario.cleanup()) {
                String entryStatus = entry.status().name();
                html.append("<li class=\"").append(entryStatus.toLowerCase(Locale.ROOT)).append("\">")
                        .append("<span class=\"status\">").append(entryStatus).append("</span> ")
                        .append("<span class=\"type\">").append(esc(entry.type())).append("</span>");
                if (entry.description() != null) {
                    html.append(" <span class=\"desc\">").append(esc(entry.description())).append("</span>");
                }
                html.append(" <span class=\"time\">").append(esc(duration(entry.durationMillis()))).append("</span>");
                if (entry.message() != null) {
                    html.append("<div class=\"message\">").append(esc(entry.message())).append("</div>");
                }
                html.append("</li>\n");
            }
            html.append("</ul>\n");
        }
        html.append("</details>\n");
    }

    private static void appendStep(StringBuilder html, StepResult step) {
        html.append("<li class=\"step ").append(step.success() ? "passed" : "failed").append("\">")
                .append("<div class=\"row\">").append(icon(step.success()))
                .append(" <span class=\"num\">").append(step.index() + 1).append("</span>")
                .append(" <span class=\"type\">").append(esc(step.type())).append("</span>");
        if (step.description() != null) {
            html.append(" <span class=\"desc\">").append(esc(step.description())).append("</span>");
        }
        html.append(" <span class=\"time\">");
        if (step.attempts() != null) {
            html.append(esc(step.attempts() + (step.attempts() == 1 ? " attempt · " : " attempts · ")
                    + duration(step.waitedMillis() == null ? 0 : step.waitedMillis())));
        } else {
            html.append(esc(duration(step.durationMillis())));
        }
        html.append("</span></div>\n");
        if (step.message() != null) {
            html.append("<div class=\"message\">").append(esc(step.message())).append("</div>\n");
        }
        if (step.resolvedParams() != null && !step.resolvedParams().isEmpty()) {
            html.append("<details class=\"params\"><summary>Parameters</summary><pre>")
                    .append(esc(json(mask(step.resolvedParams())))).append("</pre></details>\n");
        }
        html.append("<div class=\"evidence\"></div>\n</li>\n");
    }

    /** A copy with every value under a secret-looking key, at any depth, replaced by {@code ***}. */
    static Object mask(Object value) {
        return Secrets.maskByKey(value);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static String tile(String key, String label, String value) {
        return "<div class=\"tile " + key + "\"><div class=\"label\">" + label + "</div>"
                + "<div class=\"value\" data-tile=\"" + key + "\">" + esc(value) + "</div></div>";
    }

    private static String icon(boolean success) {
        return success ? "<span class=\"icon ok\">✔</span>" : "<span class=\"icon ko\">✖</span>";
    }

    /** 850 ms, 10.1 s, 1m 42s. */
    static String duration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        if (millis < 60_000) {
            return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        }
        long seconds = millis / 1000;
        return (seconds / 60) + "m " + (seconds % 60) + "s";
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
            --ko:#cf222e;--kobg:#fdeeee;--card:#f6f8fa;}
            @media (prefers-color-scheme: dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#8d96a0;--line:#30363d;\
            --ok:#3fb950;--okbg:#12261a;--ko:#f85149;--kobg:#2d1416;--card:#161b22;}}
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
            details.params{margin-left:22px}details.params pre{background:var(--card);padding:8px;border-radius:6px;overflow:auto}
            ul.cleanup{padding-left:0;list-style:none}ul.cleanup li{padding:4px 0;border-top:1px dashed var(--line)}
            .status{font-size:11px;font-weight:600;padding:1px 6px;border-radius:4px;border:1px solid var(--line)}
            li.succeeded .status{color:var(--ok)}li.failed .status{color:var(--ko)}li.skipped .status{color:var(--muted)}
            @media print{.controls{display:none}details.scenario{break-inside:avoid-page}}
            """;

    private static final String JS = """
            function setAll(open){document.querySelectorAll('details.scenario').forEach(function(d){d.open=open;});}
            function onlyFailed(button){var on=button.getAttribute('aria-pressed')!=='true';
            button.setAttribute('aria-pressed',String(on));
            document.querySelectorAll('details.scenario.passed').forEach(function(d){d.hidden=on;});}
            window.addEventListener('beforeprint',function(){document.querySelectorAll('details').forEach(function(d){d.open=true;});
document.querySelectorAll('details.scenario').forEach(function(d){d.hidden=false;});});
            """;
}
