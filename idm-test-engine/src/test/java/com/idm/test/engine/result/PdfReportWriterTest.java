package com.idm.test.engine.result;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.SqlEvidence;
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
        return new RunFolder(RUN_ID, RUN.withLocale(locale), STARTED.plusSeconds(102), RESULTS, true);
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
        String text = summary(new RunFolder("odd-folder", bare, Instant.EPOCH, RESULTS, false));

        assertThat(text).containsPattern("Filter\\s*Not recorded").contains("Not recorded").contains("None recorded.").doesNotContain("1970");
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

    private static byte[] detailed(RunFolder folder) {
        return PdfReportWriter.render(folder, PdfReportWriter.Kind.DETAILED, "idmtest test");
    }

    private static String page(byte[] pdf, int number) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(number);
            stripper.setEndPage(number);
            return stripper.getText(doc);
        }
    }

    /** The first line of the page that is not the running header. */
    private static String firstHeading(byte[] pdf, int number) throws IOException {
        return page(pdf, number).lines().filter(line -> !line.contains(RUN_ID)).findFirst().orElseThrow();
    }

    private static RunFolder folderOf(List<ScenarioResult> results) {
        return new RunFolder(RUN_ID, RUN, STARTED.plusSeconds(102), results, true);
    }

    @Test
    @DisplayName("the detailed PDF opens with the same header as the summary")
    void detailedStartsWithSummaryHeader() throws IOException {
        RunFolder folder = folder(Locale.ENGLISH);
        String summaryPage = page(PdfReportWriter.render(folder, PdfReportWriter.Kind.SUMMARY, "x"), 1)
                .replaceAll("Page \\d+ of \\d+", "").replace("Summary", "KIND").replaceAll("\\s+", " ");
        String detailedPage = page(detailed(folder), 1)
                .replaceAll("Page \\d+ of \\d+", "").replace("Detailed", "KIND").replaceAll("\\s+", " ");

        assertThat(detailedPage).isEqualTo(summaryPage);
    }

    @Test
    @DisplayName("the detailed PDF has every step with its number, type, description and message")
    void detailedHasEveryStep() throws IOException {
        String text = text(detailed(folder(Locale.ENGLISH)));

        assertThat(text).contains("1 resources.grant").contains("Grant AppA").contains("EVIDENCE-MESSAGE-OK")
                .contains("1 ldap.create").contains("Create the test account").contains("created")
                .contains("2 roles.grant").contains("Grant the Finance roles").contains("Role service error")
                .contains("Any later steps were not run.");
        // only the failed step is followed by the not-run line (finding and stopped scenarios fail a step)
        assertThat(text.split("Any later steps were not run\\.", -1)).hasSize(3);
    }

    @Test
    @DisplayName("a step that waited shows its attempts, wait time and the last-attempt note")
    void detailedHasAttempts() throws IOException {
        StepResult waited = new StepResult(0, "ldap.verify", Map.of(), true, 5_000, "found", 5, 4_000L, "Wait for it",
                new LdapEvidence("edir", "cn=a,o=d", "read", Map.of("cn", "a"), true));
        String text = text(detailed(folderOf(List.of(new ScenarioResult("S", "s.yaml", true, 5_000,
                List.of(waited), List.of(), null, null)))));

        assertThat(text).contains("5 attempts").contains("4.0 s").contains("(last attempt)");
    }

    @Test
    @DisplayName("parameters are shown as JSON with secret values masked")
    void parametersAreMasked() throws IOException {
        Map<String, Object> params = Map.of("password", "hunter2", "nested", Map.of("apiToken", "abc"),
                "dn", "cn=visible");
        StepResult step = new StepResult(0, "ldap.create", params, true, 5, "ok", null, null);
        String text = text(detailed(folderOf(List.of(new ScenarioResult("S", "s.yaml", true, 5,
                List.of(step), List.of(), null, null)))));

        assertThat(text).doesNotContain("hunter2").doesNotContain("abc").contains("***").contains("cn=visible")
                .contains("Parameters");
    }

    @Test
    @DisplayName("cleanup entries show status, type, description and message; a failed one gets the finding badge")
    void cleanupListed() throws IOException {
        List<CleanupResult> cleanup = List.of(
                new CleanupResult(0, "ldap.delete", Map.of(), CleanupStatus.SUCCEEDED, 12, "gone", "Delete user"),
                new CleanupResult(1, "file.delete", Map.of(), CleanupStatus.FAILED, 30, "CLEANUP-BOOM", "Remove file"),
                new CleanupResult(2, "db.update", Map.of(), CleanupStatus.SKIPPED, 0, "skipped earlier", null));
        String text = text(detailed(folderOf(List.of(new ScenarioResult("S", "s.yaml", true, 5,
                List.of(), cleanup, null, null)))));

        assertThat(text).contains("Cleanup").contains("SUCCEEDED").contains("ldap.delete").contains("Delete user")
                .contains("FAILED Finding file.delete").contains("Remove file").contains("CLEANUP-BOOM")
                .contains("SKIPPED").contains("db.update").contains("skipped earlier");
    }

    @Test
    @DisplayName("each scenario starts on a new page, with its heading first")
    void eachScenarioStartsNewPage() throws IOException {
        byte[] pdf = detailed(folder(Locale.ENGLISH));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(4);
        }

        // page 1 is the header; then one page per scenario (running header line first, then the heading)
        assertThat(firstHeading(pdf, 2)).contains("provisioning-resources.yaml");
        assertThat(firstHeading(pdf, 3)).contains("provisioning-user-1.yaml");
        assertThat(firstHeading(pdf, 4)).contains("stopped.yaml");
        assertThat(page(pdf, 2)).doesNotContain("provisioning-user-1.yaml");
    }

    @Test
    @DisplayName("SQL evidence with 2,000 rows keeps the last row")
    void longSqlResultKeepsLastRow() throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            rows.add(Map.of("uid", "user-" + i, "mail", "user-" + i + "@lab.example.com"));
        }
        SqlEvidence sql = SqlEvidence.query("db", "SELECT uid, mail FROM users", new Object[0], rows);
        String last = String.valueOf(sql.rows().getLast().get("uid"));
        StepResult step = new StepResult(0, "db.query", Map.of(), true, 5, "ok", null, null, null, sql);

        String text = text(detailed(folderOf(List.of(new ScenarioResult("S", "s.yaml", true, 5,
                List.of(step), List.of(), null, null)))));

        assertThat(text).contains(last + "@lab.example.com");
    }

    @Test
    @DisplayName("a SQL row wider than a line is written as column: value lines, every value present")
    void wideSqlRowBecomesRecord() throws IOException {
        String longMail = "x".repeat(150) + "@lab.example.com";
        SqlEvidence sql = SqlEvidence.query("db", "SELECT uid, mail FROM users", new Object[0],
                List.of(Map.of("uid", "wide-uid-1", "mail", longMail)));
        StepResult step = new StepResult(0, "db.query", Map.of(), true, 5, "ok", null, null, null, sql);

        String text = text(detailed(folderOf(List.of(new ScenarioResult("S", "s.yaml", true, 5,
                List.of(step), List.of(), null, null)))));

        // the long value wraps at the line's end, so compare without the wrap's space
        assertThat(text).contains("uid: wide-uid-1").doesNotContain("uid | mail");
        assertThat(text.replace(" ", "")).contains("mail:" + longMail);
    }

    @Test
    @DisplayName("an HTTP body near the size limit continues across pages and keeps its last line")
    void longHttpBodyKeepsLastLine() throws IOException {
        StringBuilder body = new StringBuilder();
        int i = 0;
        while (body.length() < 16_000) {
            body.append("line-").append(i++).append(" some response text\n");
        }
        body.append("THE-LAST-BODY-LINE");
        HttpEvidence http = new HttpEvidence("GET", "http://idm/x", Map.of(), null, 200, Map.of(), body.toString(),
                12, null);
        StepResult step = new StepResult(0, "rest.get", Map.of(), true, 5, "ok", null, null, null, http);

        byte[] pdf = detailed(folderOf(List.of(new ScenarioResult("S", "s.yaml", true, 5,
                List.of(step), List.of(), null, null))));

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(2);
        }
        assertThat(text(pdf)).contains("THE-LAST-BODY-LINE");
    }

    @Test
    @DisplayName("a scenario with no steps and a step with no evidence both render, with the failure summary")
    void scenarioWithoutStepsAndStepWithoutEvidence() throws IOException {
        ScenarioResult noSteps = new ScenarioResult("Setup died", "broken.yaml", false, 10, List.of(), List.of(),
                "could not resolve NOSTEPS-CAUSE", null);
        StepResult bare = new StepResult(0, "ldap.create", Map.of(), true, 5, "ok", null, null);
        ScenarioResult noEvidence = new ScenarioResult("Plain", "plain.yaml", true, 5, List.of(bare), List.of(), null,
                null);

        String text = text(detailed(folderOf(List.of(noSteps, noEvidence))));

        assertThat(text).contains("broken.yaml").contains("NOSTEPS-CAUSE").contains("plain.yaml")
                .contains("ldap.create");
    }
}
