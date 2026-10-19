package com.idm.test.runner;

import com.idm.test.engine.result.CleanupResult;
import com.idm.test.engine.result.CleanupStatus;
import com.idm.test.engine.result.JsonResultWriter;
import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.Check;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.SqlEvidence;
import com.idm.test.engine.spi.WaitObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecretMask")
class SecretMaskTest {

    @Test
    @DisplayName("Should mask every value of four characters or more, longest first, and leave shorter ones")
    void testMask() {
        SecretMask mask = new SecretMask(List.of("abcd", "abcdef", "1", "", "xy"));

        assertThat(mask.apply("pw=abcdef and abcd, port 1 xy")).isEqualTo("pw=**** and ****, port 1 xy");
        assertThat(mask.apply(null)).isNull();
    }

    @Test
    @DisplayName("Should mask a stack trace, causes included")
    void testStackTrace() {
        SecretMask mask = new SecretMask(List.of("s3cr3t"));
        Exception e = new IllegalStateException("outer", new NumberFormatException("For input string: \"s3cr3t\""));

        assertThat(mask.stackTrace(e)).contains("NumberFormatException", "****").doesNotContain("s3cr3t");
    }

    @Test
    @DisplayName("Should mask secrets.env values, the environment variable that overrides one, and secret-named variables")
    void testOfEffectiveValues() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("IDM_TEST_TENANT_KEY", "env-wins-1");
        environment.put("CI_DEPLOY_TOKEN", "tok-123456");
        environment.put("IDM_TEST_LDAP_PASSWORD", "abc");
        environment.put("HOME_FOLDER", "/home/someone");
        environment.put("PWD", "/work/ws");
        environment.put("OLDPWD", "/work");
        SecretMask mask = SecretMask.of(Map.of("IDM_TEST_TENANT_KEY", "file-loses", "IDM_TEST_REF", "${CI_DEPLOY_TOKEN}x"),
                environment);

        assertThat(mask.apply("a env-wins-1 b file-loses c tok-123456 d abc e /home/someone f /work/ws g /work"))
                .isEqualTo("a **** b **** c **** d abc e /home/someone f /work/ws g /work");
        // A secrets.env value that refers to another resolves as Spring resolves it.
        assertThat(mask.apply("tok-123456x")).isEqualTo("****");
    }

    @Test
    @DisplayName("Should mask every text of a result, evidence and message arguments included, and keep names")
    void testResult(@TempDir Path temp) throws Exception {
        String secret = "Zq9xLeak";
        SecretMask mask = new SecretMask(List.of(secret));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", "/t-" + secret);
        params.put("nested", Map.of("list", List.of("x" + secret)));
        params.put("count", 3);
        StepResult http = new StepResult(0, "rest.verify", params, false, 5, "failed at " + secret, null, null,
                "desc " + secret, new HttpEvidence("GET", "http://h/" + secret, Map.of("X-Trace", List.of(secret)),
                        "body " + secret, null, null, Map.of("id", secret), 4, "error " + secret),
                "step.key", List.of("http://h/" + secret, Message.of("inner", secret, 7)), "SETUP",
                List.of(new WaitObservation(1, 0, "saw " + secret), new WaitObservation(4, 30, "then " + secret)));
        StepResult sql = new StepResult(1, "db.verify", Map.of(), true, 1, null, null, null, null,
                SqlEvidence.query("db", "SELECT '" + secret + "'", new Object[] {secret},
                        List.of(Map.of("col", secret))), null, null);
        StepResult ldap = new StepResult(2, "ldap.verify", Map.of(), true, 1, null, null, null, null,
                LdapEvidence.read(null, "cn=" + secret, Map.of("sn", secret)), null, null);
        CleanupResult cleanup = new CleanupResult(0, "ldap.delete", Map.of("dn", secret), CleanupStatus.FAILED, 1,
                "cleanup " + secret, "about " + secret, "cleanup.key", List.of(secret), "FINDING");
        CleanupResult skipped = new CleanupResult(0, "ldap.delete", Map.of("dn", "cn=" + secret),
                CleanupStatus.SKIPPED, 0, "Stopped — cleanup did not run", "Remove cn=" + secret,
                "engine.run.stopped", List.of(), null);
        ScenarioResult result = new ScenarioResult("s.yaml", "s.yaml", false, 9, List.of(http, sql, ldap),
                List.of(cleanup), "detail " + secret, "scenario " + secret, "detail.key", List.of(secret),
                true, List.of(skipped));

        ScenarioResult masked = mask.result(result);
        Path json = temp.resolve("results.json");
        JsonResultWriter.write(List.of(masked), json);

        String written = Files.readString(json);
        assertThat(written).doesNotContain(secret).contains("****", "s.yaml", "rest.verify", "step.key", "inner");
        assertThat(masked.steps().get(0).resolvedParams()).containsEntry("count", 3);
        assertThat(masked.steps().get(0).failureKind()).isEqualTo("SETUP");
        assertThat(masked.cleanup().get(0).failureKind()).isEqualTo("FINDING");
        assertThat(masked.steps().get(0).waitHistory()).extracting(WaitObservation::message)
                .containsExactly("saw ****", "then ****");
        assertThat(masked.steps().get(0).waitHistory()).extracting(WaitObservation::attempt).containsExactly(1, 4);
        assertThat(masked.stopped()).isTrue();
        assertThat(masked.skippedCleanup()).singleElement().satisfies(entry -> {
            assertThat(entry.resolvedParams()).containsEntry("dn", "cn=****");
            assertThat(entry.description()).isEqualTo("Remove cn=****");
            assertThat(entry.status()).isEqualTo(CleanupStatus.SKIPPED);
        });
        assertThat(masked.failureSummary()).doesNotContain(secret);
        assertThat(mask.result(null)).isNull();
        assertThat(new SecretMask(List.of()).result(result)).isSameAs(result);
    }

    @Test
    @DisplayName("Should mask each check's name, expected and found text, and keep whether it matched")
    void testChecks() {
        String secret = "Zq9xLeak";
        SecretMask mask = new SecretMask(List.of(secret));
        StepResult step = new StepResult(0, "http.verify", Map.of(), false, 5, "m", 2, 10L, null, null, null, null,
                "FINDING", null, List.of(new Check("js:{ response.json.id === '" + secret + "' }", "true",
                        "error near " + secret, false), new Check("sn", "x" + secret, null, true)));
        ScenarioResult result = new ScenarioResult("s.yaml", "s.yaml", false, 9, List.of(step), List.of(), null);

        List<Check> checks = mask.result(result).steps().get(0).checks();

        assertThat(checks).containsExactly(
                new Check("js:{ response.json.id === '****' }", "true", "error near ****", false),
                new Check("sn", "x****", null, true));
    }

    @Test
    @DisplayName("Should mask the report header's endpoint URLs")
    void testRunInfo() {
        SecretMask mask = new SecretMask(List.of("Zq9xLeak"));
        RunInfo run = new RunInfo("Workspace", Instant.EPOCH, List.of("lab"), "users/*",
                Map.of("REST target idm", "http://h/tenant-Zq9xLeak"));

        assertThat(mask.runInfo(run).endpoints()).containsExactly(Map.entry("REST target idm", "http://h/tenant-****"));
        assertThat(mask.runInfo(run).profiles()).containsExactly("lab");
    }
}
