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
}
