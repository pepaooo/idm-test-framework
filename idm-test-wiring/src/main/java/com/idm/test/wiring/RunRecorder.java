package com.idm.test.wiring;

import com.idm.test.engine.result.HtmlReportWriter;
import com.idm.test.engine.result.JsonResultWriter;
import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.result.RunRecord;
import com.idm.test.engine.result.ScenarioResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Writes a run's results as JSON and, beside them, its {@code run.json} and an HTML report. */
public final class RunRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(RunRecorder.class);

    private RunRecorder() {
    }

    /**
     * The JSON results and the HTML report, both of everything run so far. The JSON
     * is the record and its failure is fatal; {@code run.json} and the report are auxiliary, so a failure
     * to write either is logged and never changes a scenario's outcome. Both use one finish time.
     */
    public static void write(List<ScenarioResult> collected, Path resultsJson, Path reportHtml, RunInfo run) {
        List<ScenarioResult> snapshot = List.copyOf(collected);
        JsonResultWriter.write(snapshot, resultsJson);
        Instant finishedAt = Instant.now();
        try {
            new RunRecord(run, finishedAt).write(runInfoPath(resultsJson));
            HtmlReportWriter.write(run, snapshot, finishedAt, reportHtml);
        } catch (RuntimeException e) {
            LOG.warn("Could not write the run record or HTML report beside {}: {}", resultsJson, e.toString());
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

    /** {@code results.json} becomes {@code run.json}, {@code x-results.json} {@code x-run.json}; any other name gets {@code .run.json} appended. */
    public static Path runInfoPath(Path resultsJson) {
        String name = resultsJson.getFileName().toString();
        String suffix = "results.json";
        String info = name.endsWith(suffix)
                ? name.substring(0, name.length() - suffix.length()) + "run.json"
                : name + ".run.json";
        return resultsJson.resolveSibling(info);
    }
}
