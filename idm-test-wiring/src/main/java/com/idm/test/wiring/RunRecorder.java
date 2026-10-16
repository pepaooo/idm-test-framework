package com.idm.test.wiring;

import com.idm.test.engine.result.HtmlReportWriter;
import com.idm.test.engine.result.JsonResultWriter;
import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.result.ScenarioResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/** Writes a run's results as JSON and, beside them, as an HTML report. */
public final class RunRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(RunRecorder.class);

    private RunRecorder() {
    }

    /**
     * The JSON results and the HTML report, both of everything run so far. The JSON
     * is the record and its failure is fatal; the report is auxiliary, so a failure to write it is
     * logged and never changes a scenario's outcome.
     */
    public static void write(List<ScenarioResult> collected, Path resultsJson, Path reportHtml, RunInfo run) {
        List<ScenarioResult> snapshot = List.copyOf(collected);
        JsonResultWriter.write(snapshot, resultsJson);
        try {
            HtmlReportWriter.write(run, snapshot, reportHtml);
        } catch (RuntimeException e) {
            LOG.warn("Could not write the HTML report to {}: {}", reportHtml, e.toString());
        }
    }

    /** {@code x-results.json} becomes {@code x-report.html}; any other name gets {@code .html} appended. */
    public static Path reportPath(Path resultsPath) {
        String name = resultsPath.getFileName().toString();
        String suffix = "-results.json";
        String report = name.endsWith(suffix)
                ? name.substring(0, name.length() - suffix.length()) + "-report.html"
                : name + ".html";
        return resultsPath.resolveSibling(report);
    }
}
