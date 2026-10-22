package com.idm.test.engine.result;

import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.SqlEvidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HTML report")
class HtmlReportWriterTest {

    private static final Instant STARTED = Instant.parse("2026-09-30T00:19:00Z");
    private static final Instant NOW = STARTED.plusSeconds(102);

    private static final RunInfo RUN = new RunInfo("Real environment", STARTED, List.of("dev"),
            "provisioning-*", Map.of("LDAP edirectory", "ldaps://edir.lab:636",
            "Database db-postgres", "jdbc:postgresql://db.lab/idm?password=s3cret"));

    private static ScenarioResult passed() {
        return new ScenarioResult("Grant resources", "provisioning-resources.yaml", true, 41_000,
                List.of(new StepResult(0, "resources.grant", Map.of("user", "cn=u,o=data"), true, 900,
                        "granted", null, null, "Grant AppA")),
                List.of(), null, "Resources reach the app");
    }

    private static ScenarioResult failed() {
        return new ScenarioResult("Provision a user", "provisioning-user-to-idm-1.yaml", false, 13_100,
                List.of(
                        new StepResult(0, "ldap.create", Map.of("dn", "cn=u,ou=users",
                                "attributes", Map.of("userPassword", "Secret#1", "sn", "U")), true, 400,
                                "created cn=u,ou=users", null, null, "Create the test account"),
                        new StepResult(1, "ldap.verify", Map.of("dn", "cn=u,ou=users"), true, 120,
                                "verified", 3, 10_060L, "Wait until the entry exists"),
                        new StepResult(2, "roles.grant", Map.of("user", "cn=u,ou=users,data"), false, 700,
                                "Role service error: Invalid name <cn=u>", null, null, "Grant the Finance roles")),
                List.of(
                        new CleanupResult(0, "ldap.delete", Map.of("dn", "cn=u,ou=users"), CleanupStatus.SUCCEEDED,
                                80, "deleted cn=u,ou=users", "Remove the test account"),
                        new CleanupResult(1, "db.execute", Map.of(), CleanupStatus.SKIPPED, 0,
                                "skipped: [row] never bound", null)),
                null, "A user gets the Finance roles");
    }

    private static ScenarioResult loadFailure() {
        return new ScenarioResult("broken.yaml", "broken.yaml", false, 0, List.of(),
                "broken.yaml, step 1 (ldap.verify): invalid 'wait': bad duration '30x'");
    }

    private static String render(ScenarioResult... results) {
        return HtmlReportWriter.render(RUN, List.of(results), NOW);
    }

    @Test
    @DisplayName("Header shows the runner, profiles, filter, duration and sanitised endpoints")
    void testHeader() {
        String html = render(passed());

        assertThat(html).contains("<title>Real environment run</title>")
                .contains("Real environment run")
                .contains("dev")
                .contains("provisioning-*")
                .contains("1m 42s")
                .contains("LDAP edirectory").contains("ldaps://edir.lab:636")
                .contains("jdbc:postgresql://db.lab/idm?password=***")
                .doesNotContain("s3cret");
    }

    @Test
    @DisplayName("Summary tiles count scenarios, passed and failed")
    void testSummary() {
        String html = render(passed(), failed(), loadFailure());

        assertThat(html).contains("data-tile=\"scenarios\">3<")
                .contains("data-tile=\"passed\">1<")
                .contains("data-tile=\"failed\">2<")
                .contains("Time in scenarios");
    }

    @Test
    @DisplayName("A failed scenario is open, a passed one closed")
    void testOpenState() {
        String html = render(passed(), failed());

        assertThat(html).contains("<details class=\"scenario failed\" open>")
                .contains("<details class=\"scenario passed\">");
    }

    @Test
    @DisplayName("Steps show description, attempts and waited time, message, parameters, and what didn't run")
    void testSteps() {
        String html = render(failed());

        assertThat(html).contains("Create the test account")
                .contains("Wait until the entry exists")
                .contains("3 attempts · 10.1 s")
                .contains("Grant the Finance roles")
                .contains("Role service error: Invalid name &lt;cn=u&gt;")
                .contains("&quot;dn&quot; : &quot;cn=u,ou=users&quot;")
                .contains("Any later steps were not run.")
                .contains("<div class=\"evidence\"></div>");
    }

    @Test
    @DisplayName("Cleanup entries show their status, description and message")
    void testCleanup() {
        String html = render(failed());

        assertThat(html).contains("SUCCEEDED").contains("Remove the test account")
                .contains("SKIPPED").contains("skipped: [row] never bound");
    }

    @Test
    @DisplayName("A load failure shows its error")
    void testLoadFailure() {
        String html = render(loadFailure());

        assertThat(html).contains("broken.yaml").contains("invalid &#39;wait&#39;: bad duration &#39;30x&#39;");
    }

    @Test
    @DisplayName("Text is escaped, so a description or message can't inject markup")
    void testEscaping() {
        ScenarioResult hostile = new ScenarioResult("x", "x.yaml", false, 1,
                List.of(new StepResult(0, "a", Map.of(), false, 1, "<script>alert(1)</script>", null, null,
                        "<img src=x onerror=alert(2)>")),
                List.of(), null, null);

        String html = render(hostile);

        assertThat(html).doesNotContain("<script>alert(1)</script>")
                .doesNotContain("<img src=x")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    @DisplayName("Secret-looking parameter keys are masked at any depth")
    void testSecretParametersMasked() {
        ScenarioResult secret = new ScenarioResult("x", "x.yaml", true, 1,
                List.of(new StepResult(0, "http.request",
                        Map.of("headers", Map.of("Authorization", "Bearer abc.def"),
                                "body", "{}", "client_secret", "zzz"),
                        true, 1, "ok", null, null, null)),
                List.of(), null, null);

        String html = render(secret, failed());

        assertThat(html).doesNotContain("abc.def").doesNotContain("zzz").doesNotContain("Secret#1")
                .contains("&quot;Authorization&quot; : &quot;***&quot;");
    }

    @Test
    @DisplayName("API key, cookie and pwd parameters are masked")
    void testApiKeysAndCookiesMasked() {
        ScenarioResult secret = new ScenarioResult("x", "x.yaml", true, 1,
                List.of(new StepResult(0, "http.request",
                        Map.of("headers", Map.of("X-API-Key", "k-111", "Cookie", "sid=c-222",
                                        "Set-Cookie", "sid=c-333"),
                                "pwd", "p-444"),
                        true, 1, "ok", null, null, null)),
                List.of(), null, null);

        String html = render(secret);

        assertThat(html).doesNotContain("k-111").doesNotContain("c-222").doesNotContain("c-333")
                .doesNotContain("p-444")
                .contains("&quot;X-API-Key&quot; : &quot;***&quot;")
                .contains("&quot;Cookie&quot; : &quot;***&quot;")
                .contains("&quot;Set-Cookie&quot; : &quot;***&quot;")
                .contains("&quot;pwd&quot; : &quot;***&quot;");
    }

    @Test
    @DisplayName("The file is self-contained: nothing is loaded from outside")
    void testSelfContained() {
        String html = render(passed(), failed());

        Matcher external = Pattern.compile("(?i)(src|href)\\s*=\\s*[\"']?(https?:|//)").matcher(html);
        assertThat(external.find()).as("external src/href").isFalse();
        assertThat(html).contains("<style>").contains("<script>").contains("prefers-color-scheme: dark")
                .contains("@media print").contains("beforeprint");
    }

    @Test
    @DisplayName("write creates the file, and its parent directory, as UTF-8")
    void testWrite(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("nested/real-environment-report.html");

        HtmlReportWriter.write(RUN, List.of(passed()), target);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).contains("<!DOCTYPE html>").contains("✔");
    }

    private static StepResult withEvidence(boolean success, Integer attempts, Evidence evidence) {
        return new StepResult(0, "probe", Map.of(), success, 10, "msg", attempts,
                attempts == null ? null : 1_000L, null, evidence);
    }

    private static String renderStep(StepResult step) {
        return render(new ScenarioResult("S", "s.yaml", step.success(), 10, List.of(step), List.of(), null, null));
    }

    private static HttpEvidence httpEvidence(Integer status, Object responseBody) {
        return new HttpEvidence("GET", "https://hr.lab/api/users/jdoe?x=1",
                Map.of("Authorization", List.of("Bearer abc")), null, status,
                status == null ? null : Map.of("Content-Type", List.of("application/json")), responseBody, 84,
                status == null ? "Connection refused" : null);
    }

    @Test
    @DisplayName("HTTP evidence: open on a failed step, request and response side by side, secrets masked")
    void testHttpEvidence() {
        String html = renderStep(withEvidence(false, 12, httpEvidence(200, "{\"status\":\"PENDING\"}")));

        assertThat(html).contains("<details class=\"evidence\" open>")
                .contains("GET /api/users/jdoe?x=1 → 200 · 84 ms (last attempt)")
                .contains("<div class=\"http\">")
                .contains("Authorization: ***").doesNotContain("Bearer abc")
                .contains("&quot;status&quot; : &quot;PENDING&quot;");
    }

    @Test
    @DisplayName("Evidence on a passed step is closed")
    void testPassedEvidenceClosed() {
        String html = renderStep(withEvidence(true, null, httpEvidence(200, "ok")));

        assertThat(html).contains("<details class=\"evidence\">").doesNotContain("class=\"evidence\" open");
    }

    @Test
    @DisplayName("No response shows the error")
    void testNoResponse() {
        String html = renderStep(withEvidence(false, null, httpEvidence(null, null)));

        assertThat(html).contains("GET /api/users/jdoe?x=1 → no response").contains("no response: Connection refused");
    }

    @Test
    @DisplayName("SQL evidence: statement, parameters, rows table and the rows left out")
    void testSqlEvidence() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(Map.of("ID", i));
        }
        String html = renderStep(withEvidence(true, null,
                SqlEvidence.query("hr", "SELECT id FROM t WHERE username = ?", new Object[]{"jdoe"}, rows)));

        assertThat(html).contains("SQL · 1 parameter · 25 rows")
                .contains("SELECT id FROM t WHERE username = ?")
                .contains("params: [&quot;jdoe&quot;]")
                .contains("<table class=\"rows\">")
                .contains("… 5 more rows");
        assertThat(renderStep(withEvidence(true, null, SqlEvidence.update("hr", "DELETE FROM t", new Object[0], 3))))
                .contains("SQL · 0 parameters · updated 3 rows");
    }

    @Test
    @DisplayName("LDAP evidence: operation, DN and an attribute table")
    void testLdapEvidence() {
        String html = renderStep(withEvidence(true, null, LdapEvidence.written(null, "cn=u,ou=users", "create",
                Map.of("sn", "U", "member", List.of("cn=a", "cn=b")))));

        assertThat(html).contains("create cn=u,ou=users · 2 attributes")
                .contains("cn=a<br>cn=b");
        assertThat(renderStep(withEvidence(false, null, LdapEvidence.absent(null, "cn=x", "read"))))
                .contains("read cn=x · no entry");
    }

    @Test
    @DisplayName("Evidence text is escaped")
    void testEvidenceEscaped() {
        String html = renderStep(withEvidence(false, null, httpEvidence(500, "<script>alert(1)</script>")));

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert");
    }

    @Test
    @DisplayName("A step without evidence keeps the empty slot; Expand all covers evidence")
    void testNoEvidenceSlot() {
        String html = renderStep(withEvidence(true, null, null));

        assertThat(html).contains("<div class=\"evidence\"></div>")
                .contains("details.scenario, details.evidence");
    }

    private static final RunInfo RUN_ES = RUN.withLocale(java.util.Locale.of("es"));

    @Test
    @DisplayName("A Spanish run's report is in Spanish: lang, labels, dates, statuses, evidence")
    void testSpanishReport() {
        String html = HtmlReportWriter.render(RUN_ES, List.of(passed(), failed()), NOW);

        assertThat(html).contains("<html lang=\"es\">")
                .contains("Ejecución: Real environment")
                .containsPattern("Inicio \\d{2}/\\d{2}/\\d{4} ")
                .contains("Duración 1m 42s")
                .contains(">Escenarios<").contains(">Correctos<").contains(">Fallidos<")
                .contains("Expandir todo").contains("Solo fallidos")
                .contains("Los pasos posteriores no se ejecutaron.")
                .contains("<h3>Limpieza</h3>")
                .contains(">CORRECTA<").contains(">OMITIDA<")
                .contains("3 intentos · 10,1 s")
                .contains("Parámetros")
                .doesNotContain("Expand all").doesNotContain("Only failed");
    }

    @Test
    @DisplayName("Author text and messages without a key are shown as written in a Spanish report")
    void testSpanishReportKeepsUnkeyedText() {
        String html = HtmlReportWriter.render(RUN_ES, List.of(failed()), NOW);

        assertThat(html).contains("Grant the Finance roles")
                .contains("Role service error: Invalid name &lt;cn=u&gt;")
                .contains("A user gets the Finance roles");
    }

    @Test
    @DisplayName("Durations use the locale's decimal separator")
    void testDurationLocale() {
        assertThat(HtmlReportWriter.duration(10_100, java.util.Locale.of("es"))).isEqualTo("10,1 s");
        assertThat(HtmlReportWriter.duration(10_100, java.util.Locale.ENGLISH)).isEqualTo("10.1 s");
        assertThat(HtmlReportWriter.duration(850, java.util.Locale.of("es"))).isEqualTo("850 ms");
        assertThat(HtmlReportWriter.duration(102_000, java.util.Locale.of("es"))).isEqualTo("1m 42s");
    }

    private static String renderStepEs(StepResult step) {
        return HtmlReportWriter.render(RUN_ES, List.of(
                new ScenarioResult("S", "s.yaml", step.success(), 10, List.of(step), List.of(), null, null)), NOW);
    }

    @Test
    @DisplayName("HTTP evidence in Spanish")
    void testSpanishHttpEvidence() {
        String html = renderStepEs(withEvidence(false, 12, httpEvidence(200, "ok")));
        assertThat(html).contains("Evidencia <span")
                .contains("(último intento)")
                .contains("Solicitud").contains("Respuesta");
        assertThat(renderStepEs(withEvidence(false, null, httpEvidence(null, null))))
                .contains("→ sin respuesta").contains("sin respuesta: Connection refused");
    }

    @Test
    @DisplayName("SQL evidence in Spanish")
    void testSpanishSqlEvidence() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(Map.of("ID", i));
        }
        assertThat(renderStepEs(withEvidence(true, null,
                SqlEvidence.query("hr", "SELECT id FROM t WHERE username = ?", new Object[]{"jdoe"}, rows))))
                .contains("SQL · 1 parámetro · 25 filas")
                .contains("parámetros: [&quot;jdoe&quot;]")
                .contains("… 5 filas más");
        assertThat(renderStepEs(withEvidence(true, null, SqlEvidence.update("hr", "DELETE FROM t", new Object[0], 3))))
                .contains("SQL · 0 parámetros · actualización: 3 filas");
        assertThat(renderStepEs(withEvidence(true, null,
                SqlEvidence.query("hr", "SELECT 1", new Object[0], List.of()))))
                .contains("sin filas");
    }

    @Test
    @DisplayName("LDAP evidence in Spanish")
    void testSpanishLdapEvidence() {
        assertThat(renderStepEs(withEvidence(true, null, LdapEvidence.written("east", "cn=u,ou=users", "create",
                Map.of("sn", "U", "member", List.of("cn=a", "cn=b"), "x", "y")))))
                .contains("create cn=u,ou=users · 3 atributos")
                .contains("directorio east");
        assertThat(renderStepEs(withEvidence(false, null, LdapEvidence.absent(null, "cn=x", "read"))))
                .contains("read cn=x · sin entrada").contains("ausente");
        assertThat(renderStepEs(withEvidence(true, null, LdapEvidence.exists(null, "cn=x", true))))
                .contains("exists cn=x · presente");
    }

    @Test
    @DisplayName("Hostile text is escaped in every evidence field")
    void testEscapingEverywhere() {
        String h = "<script>alert(1)</script>";
        HttpEvidence http = new HttpEvidence("GET", "https://hr.lab/x", Map.of("X-Note", List.of(h)), h, 200,
                Map.of(), "ok", 5, null);
        SqlEvidence sql = SqlEvidence.query("hr", "SELECT '" + h + "'", new Object[]{h},
                List.of(Map.of(h, h)));
        LdapEvidence ldap = LdapEvidence.written(null, "cn=" + h, "create", Map.of(h, List.of(h, "b")));
        for (Evidence evidence : List.of(http, sql, ldap)) {
            assertThat(renderStep(withEvidence(false, null, evidence))).doesNotContain("<script>alert")
                    .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        }
    }

    @Test
    @DisplayName("Rows with a missing total, differing keys or no rows still render")
    void testRowsEdgeCases() {
        SqlEvidence noTotal = new SqlEvidence("hr", "SELECT 1", List.of(), List.of(Map.of("A", 1)), null, null);
        assertThat(renderStep(withEvidence(true, null, noTotal))).contains("<table class=\"rows\">")
                .doesNotContain("more rows");

        Map<String, Object> first = new java.util.LinkedHashMap<>();
        first.put("A", 1);
        first.put("B", 2);
        Map<String, Object> second = new java.util.LinkedHashMap<>();
        second.put("B", 3);
        second.put("C", 4);
        String html = renderStep(withEvidence(true, null,
                SqlEvidence.query("hr", "SELECT *", new Object[0], List.of(first, second))));
        assertThat(html).contains("<tr><th>A</th><th>B</th><th>C</th></tr>")
                .contains("<tr><td>1</td><td>2</td><td></td></tr>")
                .contains("<tr><td></td><td>3</td><td>4</td></tr>");

        assertThat(renderStep(withEvidence(true, null,
                SqlEvidence.query("hr", "SELECT 1", new Object[0], List.of())))).contains("no rows");
    }

    @Test
    @DisplayName("Structure body, unparseable URL, null header values and failed SQL")
    void testOtherEvidenceShapes() {
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        headers.put("X-Null", null);
        HttpEvidence http = new HttpEvidence("POST", "not a url", headers, Map.of("a", 1), 201, null, null, 3, null);
        assertThat(renderStep(withEvidence(true, null, http))).contains("POST not a url → 201")
                .contains("&quot;a&quot; : 1").contains("X-Null: ");

        assertThat(renderStep(withEvidence(false, null, SqlEvidence.failed("hr", "SELECT x", new Object[0]))))
                .contains("SQL · 0 parameters · no result");
    }

    @Test
    @DisplayName("LDAP exists and delete summaries")
    void testLdapSummaries() {
        assertThat(renderStep(withEvidence(true, null, LdapEvidence.exists(null, "cn=u", true))))
                .contains("exists cn=u · present");
        assertThat(renderStep(withEvidence(true, null, LdapEvidence.exists(null, "cn=u", false))))
                .contains("exists cn=u · no entry");
        assertThat(renderStep(withEvidence(true, null, LdapEvidence.deleted(null, "cn=u"))))
                .contains("delete cn=u<");
    }

    private static ScenarioResult keyedFailure() {
        return new ScenarioResult("Provision a user", "keyed.yaml", false, 3_000,
                List.of(new StepResult(0, "ldap.verify", Map.of("dn", "cn=u,ou=users"), false, 2_000,
                        "Connection refused: ldap://edir.lab:636 (last observed value after retrying)", 3, 2_000L,
                        "Wait until the entry exists", null,
                        "engine.wait.lastObserved", List.of("Connection refused: ldap://edir.lab:636"))),
                List.of(new CleanupResult(0, "ldap.delete", Map.of(), CleanupStatus.FAILED, 10,
                        "gone (not retried: waiting cannot change this)", null,
                        "engine.wait.notRetried", List.of("gone"))),
                null, null);
    }

    @Test
    @DisplayName("A keyed message is shown in Spanish with the external text inside it unchanged")
    void testKeyedMessageWithExternalTextInSpanish() {
        String html = HtmlReportWriter.render(RUN_ES, List.of(keyedFailure()), NOW);

        assertThat(html)
                .contains("<div class=\"message\">Connection refused: ldap://edir.lab:636"
                        + " (último valor observado tras reintentar)</div>")
                .contains("gone (sin reintentos: esperar no puede cambiar este resultado)")
                .contains("<p class=\"failure\">" + HtmlReportWriter.esc(keyedFailure().failureSummary(java.util.Locale.of("es"))))
                .contains("keyed.yaml, paso 1 (ldap.verify: &quot;Wait until the entry exists&quot;): "
                        + "Connection refused: ldap://edir.lab:636 (último valor observado tras reintentar)"
                        + " — se dejó de reintentar tras 3 intentos en 2000 ms")
                .doesNotContain("last observed value");
    }

    @Test
    @DisplayName("A keyed message is shown in English exactly as recorded")
    void testKeyedMessageInEnglish() {
        String html = render(keyedFailure());

        assertThat(html)
                .contains("<div class=\"message\">Connection refused: ldap://edir.lab:636"
                        + " (last observed value after retrying)</div>")
                .contains("gone (not retried: waiting cannot change this)")
                .contains("<p class=\"failure\">" + HtmlReportWriter.esc(keyedFailure().failureSummary()) + "</p>");
    }

    private static ScenarioResult withKind(String file, String kind) {
        return new ScenarioResult(file, file, false, 10,
                List.of(new StepResult(0, "ldap.verify", Map.of(), false, 5, "went wrong", null, null, null,
                        null, null, null, kind)),
                List.of(), null, null);
    }

    private static ScenarioResult cleanupOnly() {
        return new ScenarioResult("c.yaml", "c.yaml", false, 10,
                List.of(new StepResult(0, "ldap.create", Map.of(), true, 5, "created", null, null, null)),
                List.of(new CleanupResult(0, "ldap.delete", Map.of(), CleanupStatus.FAILED, 1, "could not delete",
                        null, null, null, "FINDING")),
                null, null);
    }

    @Test
    @DisplayName("A finding and a setup problem are labelled and coloured apart, and counted apart")
    void testFailureKinds() {
        String html = render(passed(), withKind("f.yaml", "FINDING"), withKind("s.yaml", "SETUP"), cleanupOnly(),
                loadFailure());

        assertThat(html).contains("<li class=\"step failed finding\">")
                .contains("<li class=\"step failed setup\">")
                .contains("<span class=\"kind finding\">Finding</span>")
                .contains("<span class=\"kind setup\">Setup problem</span>")
                .contains("data-tile=\"findings\">2<")
                .contains("data-tile=\"setup\">2<")
                .contains(">Findings<").contains(">Setup problems<")
                .contains("--finding:#e07b25").contains("--finding:#f2994a");
        assertThat(html.split("<span class=\"kind finding\">Finding</span>", -1))
                .as("f.yaml's summary and step, c.yaml's summary and cleanup entry").hasSize(5);
    }

    @Test
    @DisplayName("The failure kind labels are in Spanish too")
    void testSpanishFailureKinds() {
        String html = HtmlReportWriter.render(RUN_ES,
                List.of(withKind("f.yaml", "FINDING"), withKind("s.yaml", "SETUP")), NOW);

        assertThat(html).contains("<span class=\"kind finding\">Hallazgo</span>")
                .contains("<span class=\"kind setup\">Problema de configuración</span>")
                .contains(">Hallazgos<").contains(">Problemas de configuración<");
    }

    /** As the engine records a scenario stopped during a wait: the interrupted step is SETUP. */
    private static ScenarioResult stoppedDuringWait() {
        return new ScenarioResult("Stopped one", "stopped.yaml", false, 2_000,
                List.of(new StepResult(0, "ldap.verify", Map.of("dn", "cn=u,ou=users"), false, 1_500,
                        "Stopped — cleanup did not run", 2, 1_400L, null, null, "engine.run.stopped", List.of(),
                        "SETUP")),
                List.of(), null, null, null, null, true,
                List.of(new CleanupResult(0, "ldap.delete", Map.of("dn", "cn=u,ou=users"), CleanupStatus.SKIPPED, 0,
                        "Stopped — cleanup did not run", null)));
    }

    @Test
    @DisplayName("A stopped scenario is labelled Stopped, not a setup problem, and counted in its own tile")
    void testStoppedScenario() {
        String html = render(passed(), stoppedDuringWait());

        assertThat(html).contains("<span class=\"kind stopped\">Stopped</span>")
                .doesNotContain("<span class=\"kind setup\">")
                .contains("data-tile=\"stopped\">1<")
                .contains("data-tile=\"setup\">0<")
                .contains("data-tile=\"findings\">0<")
                .contains(">Stopped<")
                .contains(".kind.stopped{color:var(--finding)}");
        assertThat(html.split("<span class=\"kind stopped\">Stopped</span>", -1))
                .as("the scenario's summary and its interrupted step").hasSize(3);
    }

    @Test
    @DisplayName("The stopped label and tile are in Spanish too")
    void testSpanishStoppedScenario() {
        String html = HtmlReportWriter.render(RUN_ES, List.of(stoppedDuringWait()), NOW);

        assertThat(html).contains("<span class=\"kind stopped\">Detenido</span>")
                .doesNotContain("Problema de configuración</span>")
                .contains(">Detenidos<");
    }

    /** A FINDING step failed, then the stop came before the cleanup. */
    private static ScenarioResult stoppedAfterFinding() {
        return new ScenarioResult("Found then stopped", "found.yaml", false, 2_000,
                List.of(new StepResult(0, "ldap.verify", Map.of("dn", "cn=u,ou=users"), false, 1_500,
                        "sn: expected X, was Y", 3, 1_400L, null, null, null, null, "FINDING")),
                List.of(), null, null, null, null, true,
                List.of(new CleanupResult(0, "ldap.delete", Map.of("dn", "cn=u,ou=users"), CleanupStatus.SKIPPED, 0,
                        "Stopped — cleanup did not run", null)));
    }

    /** Hand-built: an earlier FINDING step failed and a later step carries the stop's key. */
    private static ScenarioResult findingThenInterrupted() {
        return new ScenarioResult("Found, then interrupted", "both.yaml", false, 2_000,
                List.of(new StepResult(0, "ldap.verify", Map.of(), false, 100, "sn: expected X, was Y", null, null,
                                null, null, null, null, "FINDING"),
                        new StepResult(1, "ldap.verify", Map.of(), false, 100, "Stopped — cleanup did not run", null,
                                null, null, null, "engine.run.stopped", List.of(), "SETUP")),
                List.of(), null, null, null, null, true, List.of());
    }

    @Test
    @DisplayName("A scenario stopped after its step found a problem is labelled and counted as a finding")
    void testStoppedAfterFinding() {
        String html = render(stoppedAfterFinding(), findingThenInterrupted());

        assertThat(html).contains("data-tile=\"findings\">2<")
                .contains("data-tile=\"stopped\">0<")
                .contains("data-tile=\"setup\">0<")
                .contains("<summary><span class=\"icon ko\">");
        assertThat(html.split("<summary>[^\\n]*<span class=\"kind finding\">Finding</span>", -1))
                .as("both scenarios' summaries say Finding").hasSize(3);
        assertThat(html).doesNotContainPattern("<summary>[^\\n]*kind stopped");
    }
}
