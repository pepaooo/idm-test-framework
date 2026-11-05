package com.idm.test.engine.result;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PdfReportWriter")
class PdfReportWriterTest {

    private static final Instant STARTED = Instant.parse("2026-09-30T00:19:00Z");
    private static final String RUN_ID = "20260930-001900-dev";

    private static final RunInfo RUN = new RunInfo("Real environment", STARTED, List.of("dev", "lab"),
            "provisioning-*", Map.of("LDAP edirectory", "ldaps://edir.lab:636",
            "Database db-postgres", "jdbc:postgresql://db.lab/idm?password=s3cret"));

    private static ScenarioResult passed() {
        return new ScenarioResult("Grant resources", "provisioning-resources.yaml", true, 41_000,
                List.of(new StepResult(0, "resources.grant", Map.of("user", "cn=u,o=data"), true, 900,
                        "EVIDENCE-MESSAGE-OK", null, null, "Grant AppA")),
                List.of(), null, "Resources reach the app");
    }

    private static ScenarioResult finding() {
        return new ScenarioResult("Provision a user", "provisioning-user-1.yaml", false, 13_100,
                List.of(new StepResult(0, "ldap.create", Map.of("dn", "cn=u,ou=users"), true, 400,
                                "created", null, null, "Create the test account"),
                        new StepResult(1, "roles.grant", Map.of("user", "cn=u"), false, 700,
                                "Role service error", null, null, "Grant the Finance roles")),
                List.of(), null, "A user gets the Finance roles");
    }

    private static ScenarioResult stoppedOnly() {
        return new ScenarioResult("Stopped one", "stopped.yaml", false, 2_000,
                List.of(new StepResult(0, "ldap.verify", Map.of(), false, 1_400,
                        "Stopped — cleanup did not run", 2, 1_400L, null, null, "engine.run.stopped", List.of(),
                        null, null)),
                List.of(), null, null, null, null, true, List.of());
    }

    private static final List<ScenarioResult> RESULTS = List.of(passed(), finding(), stoppedOnly());

    private static RunFolder folder(Locale locale) {
        return new RunFolder(RUN_ID, RUN.withLocale(locale), STARTED.plusSeconds(102), RESULTS);
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }

    private static String summary(RunFolder folder) throws IOException {
        return text(PdfReportWriter.render(folder, PdfReportWriter.Kind.SUMMARY, "idmtest test"));
    }

    @Test
    @DisplayName("the summary header shows the run id, start time, profiles, filter and every endpoint")
    void summaryHasHeader() throws IOException {
        String text = summary(folder(Locale.ENGLISH));

        assertThat(text).contains("Real environment run").contains("Summary")
                .contains(RUN_ID)
                .contains(HtmlReportWriter.started(RUN))
                .contains("dev, lab")
                .contains("provisioning-*")
                .contains("LDAP edirectory").contains("ldaps://edir.lab:636")
                .contains("Database db-postgres").contains("jdbc:postgresql://db.lab/idm?password=***")
                .doesNotContain("s3cret");
    }

    @Test
    @DisplayName("the summary totals equal the HTML report's tiles")
    void summaryTotalsMatchHtml() throws IOException {
        String html = HtmlReportWriter.render(RUN, RESULTS, STARTED.plusSeconds(102));
        String text = summary(folder(Locale.ENGLISH));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("scenarios", "Scenarios");
        labels.put("passed", "Passed");
        labels.put("failed", "Failed");
        labels.put("findings", "Findings");
        labels.put("setup", "Setup problems");
        labels.put("stopped", "Stopped");
        labels.put("duration", "Time in scenarios");

        labels.forEach((key, label) -> {
            Matcher tile = Pattern.compile("data-tile=\"" + key + "\">([^<]*)<").matcher(html);
            assertThat(tile.find()).isTrue();
            // a tile is its label followed by its value
            assertThat(text).contains(label + " " + tile.group(1) + " ");
        });
    }

    @Test
    @DisplayName("the summary lists each scenario with its description and a failed one's failure summary")
    void summaryListsScenarios() throws IOException {
        String text = summary(folder(Locale.ENGLISH));

        assertThat(text).contains("provisioning-resources.yaml").contains("Resources reach the app")
                .contains("provisioning-user-1.yaml").contains("Provision a user")
                .contains("A user gets the Finance roles")
                .contains("stopped.yaml")
                .contains("Finding").contains("Stopped")
                .contains(finding().failureSummary(Locale.ENGLISH).replaceAll("\\s+", " "));
    }

    @Test
    @DisplayName("the summary has no step or evidence detail")
    void summaryHasNoDetail() throws IOException {
        String text = summary(folder(Locale.ENGLISH));

        assertThat(text).doesNotContain("resources.grant").doesNotContain("ldap.create")
                .doesNotContain("EVIDENCE-MESSAGE-OK").doesNotContain("cn=u,o=data");
    }

    @Test
    @DisplayName("the summary is in the folder's language")
    void summaryInSpanish() throws IOException {
        String text = summary(folder(Locale.forLanguageTag("es")));

        assertThat(text).contains("Resumen").contains("Escenarios").contains("Correctos")
                .contains("Fallidos").contains("Hallazgos").contains("Detenidos")
                .contains("Tiempo en escenarios").contains("Entorno");
    }

    @Test
    @DisplayName("a run without recorded details says so")
    void notRecordedFields() throws IOException {
        RunInfo bare = new RunInfo(null, Instant.EPOCH, List.of(), null, Map.of());
        String text = summary(new RunFolder("odd-folder", bare, Instant.EPOCH, RESULTS));

        assertThat(text).contains("Not recorded").contains("None recorded.").doesNotContain("1970");
    }

    @Test
    @DisplayName("kind parses summary and detailed, and file names carry the kind")
    void kindAndFileName() {
        assertThat(PdfReportWriter.Kind.parse("summary")).isEqualTo(PdfReportWriter.Kind.SUMMARY);
        assertThat(PdfReportWriter.Kind.parse("detailed")).isEqualTo(PdfReportWriter.Kind.DETAILED);
        assertThatThrownBy(() -> PdfReportWriter.Kind.parse("full")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PdfReportWriter.Kind.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PdfReportWriter.fileName(RUN_ID, PdfReportWriter.Kind.SUMMARY)).isEqualTo(RUN_ID + "-summary.pdf");
        assertThat(PdfReportWriter.fileName(RUN_ID, PdfReportWriter.Kind.DETAILED)).isEqualTo(RUN_ID + "-detailed.pdf");
    }
}
