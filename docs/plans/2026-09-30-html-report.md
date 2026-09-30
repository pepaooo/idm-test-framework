# HTML Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every scenario runner writes one self-contained HTML report (`*-report.html`) next to its `*-results.json`, rewritten after each scenario.

**Architecture:**
- **Engine (Spring-free), `com.idm.test.engine.result`:**
  - `ReportUrls` sanitises URLs
  - `RunInfo` describes the run, sanitising its endpoint URLs
  - `HtmlReportWriter` renders the page from `RunInfo` plus the existing `ScenarioResult`s
- **Scenarios module:**
  - `RunInfos` builds a `RunInfo` from the Spring `Environment` and the selected scenarios' needs
  - `ScenarioSupport.buildTests` writes the HTML beside the JSON
  - each runner supplies its name

**Tech Stack:** Java 21, Jackson (already in the engine), plain string building (no template engine, no new dependency), JUnit 5, AssertJ.

**Spec:** `docs/specs/2026-09-30-html-report-design.md` (commit b4d75eb). **Ticket:** ITF-36.

## Global Constraints

- **One self-contained file:** inline CSS and JavaScript; no `src=` or `href=` pointing outside the file; UTF-8.
- **Report path:** the results path with `-results.json` replaced by `-report.html` (`target/scenario-report.html`, `target/real-environment-report.html`, `target/multi-directory-report.html`); any other name gets `.html` appended.
- **Written** wherever the JSON is written: after every scenario, including load-failure results.
- **Escaping:** every piece of text in the page is HTML-escaped (`&`, `<`, `>`, `"`, `'`).
- **Parameter masking in the page:** a map entry at any depth whose key, lower-cased, contains `password`, `passwd`, `secret`, `token`, `authorization` or `credential` renders its value as `***`. The JSON results are unchanged.
- **URL sanitising (`ReportUrls.sanitise`):**
  - `//user:password@` → `//`
  - Oracle `:thin:user/pass@` → `:thin:@`
  - the value of any `?`, `&` or `;` parameter whose name, case-insensitively, contains `password`, `passwd`, `secret`, `token`, `key` or `credential` → `***`
  - `null` stays `null`
- **Layout:**
  - header "`<runner>` run", then meta (start time, duration, profiles, filter), a collapsible Endpoints list, and summary tiles (Scenarios, Passed, Failed, Duration)
  - controls: Expand all, Collapse all, Only failed
  - one `<details class="scenario …">` per scenario, `open` when failed
  - step rows; after a failed step, `Later steps were not run.`
  - a Cleanup list
  - an empty `<div class="evidence"></div>` per step, reserved for ITF-37
- **Look:** light and dark themes via `prefers-color-scheme`; `@media print` shows every section, and a `beforeprint` handler opens all `<details>`.
- **The engine stays Spring-free** (`NoSpringDependencyTest`).
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. Green means 0 failures, errors and skips.
- **Commits** end with a blank line, `ITF-36`, and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: `ReportUrls` and `RunInfo` (engine)

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/ReportUrls.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/RunInfo.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/result/ReportUrlsTest.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/result/RunInfoTest.java`

**Interfaces:**
- Produces:
  - `public static String ReportUrls.sanitise(String url)`
  - `public record RunInfo(String runner, Instant startedAt, List<String> profiles, String scenarioFilter, Map<String, String> endpoints)`: endpoints are sorted by label and sanitised; null values are dropped
  - `public static RunInfo RunInfo.minimal(String runner)`

- [ ] **Step 1: Write the failing tests**

`ReportUrlsTest.java`:

```java
package com.idm.test.engine.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Report URL sanitising")
class ReportUrlsTest {

    @Test
    @DisplayName("User info before the host is removed")
    void testUserInfoRemoved() {
        assertThat(ReportUrls.sanitise("jdbc:postgresql://idm:s3cret@db.lab:5432/idm"))
                .isEqualTo("jdbc:postgresql://db.lab:5432/idm");
        assertThat(ReportUrls.sanitise("https://svc:pw@idm.lab:8543/IDMProv/role/service"))
                .isEqualTo("https://idm.lab:8543/IDMProv/role/service");
        assertThat(ReportUrls.sanitise("jdbc:oracle:thin:scott/tiger@db.lab:1521:HR"))
                .isEqualTo("jdbc:oracle:thin:@db.lab:1521:HR");
    }

    @Test
    @DisplayName("Credential-looking parameters are masked, others kept")
    void testCredentialParametersMasked() {
        assertThat(ReportUrls.sanitise("jdbc:postgresql://db.lab/idm?user=idm&password=s3cret&ssl=true"))
                .isEqualTo("jdbc:postgresql://db.lab/idm?user=idm&password=***&ssl=true");
        assertThat(ReportUrls.sanitise("https://api.lab/v1?api_key=abc123&Token=xyz"))
                .isEqualTo("https://api.lab/v1?api_key=***&Token=***");
        assertThat(ReportUrls.sanitise("jdbc:sqlserver://db.lab:1433;databaseName=idm;password=s3cret"))
                .isEqualTo("jdbc:sqlserver://db.lab:1433;databaseName=idm;password=***");
        assertThat(ReportUrls.sanitise("ldaps://edir.lab:636")).isEqualTo("ldaps://edir.lab:636");
    }

    @Test
    @DisplayName("Text that isn't a URL is masked the same way, and null stays null")
    void testNonUrl() {
        assertThat(ReportUrls.sanitise("not a url;secret=x")).isEqualTo("not a url;secret=***");
        assertThat(ReportUrls.sanitise(null)).isNull();
    }
}
```

`RunInfoTest.java`:

```java
package com.idm.test.engine.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RunInfo")
class RunInfoTest {

    @Test
    @DisplayName("Endpoints are sanitised, sorted by label, and null URLs dropped")
    void testEndpoints() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("REST idm", "https://svc:pw@idm.lab/api");
        endpoints.put("Database sap", "jdbc:postgresql://db.lab/sap?password=x");
        endpoints.put("Role service", null);

        RunInfo run = new RunInfo("Real environment", Instant.EPOCH, List.of("dev"), null, endpoints);

        assertThat(run.endpoints()).containsExactly(
                Map.entry("Database sap", "jdbc:postgresql://db.lab/sap?password=***"),
                Map.entry("REST idm", "https://idm.lab/api"));
    }

    @Test
    @DisplayName("minimal has the runner name, a start time and nothing else")
    void testMinimal() {
        RunInfo run = RunInfo.minimal("Scenarios");

        assertThat(run.runner()).isEqualTo("Scenarios");
        assertThat(run.startedAt()).isNotNull();
        assertThat(run.profiles()).isEmpty();
        assertThat(run.scenarioFilter()).isNull();
        assertThat(run.endpoints()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='ReportUrlsTest,RunInfoTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation fails (`ReportUrls`, `RunInfo` don't exist).

- [ ] **Step 3: Implement**

`ReportUrls.java`:

```java
package com.idm.test.engine.result;

import java.util.regex.Pattern;

/**
 * Makes a connection URL safe to show in a report: no user name or password before the host,
 * and no value for a parameter whose name looks like a credential. The report is shared more
 * widely than the configuration it came from.
 */
public final class ReportUrls {

    private static final Pattern USER_INFO = Pattern.compile("(//)[^/?#@]*@");
    private static final Pattern ORACLE_THIN_CREDENTIALS = Pattern.compile("(:thin:)[^@/]*/[^@]*@");
    private static final Pattern CREDENTIAL_PARAMETER = Pattern.compile(
            "(?i)([?&;][^=&;?#]*(?:password|passwd|secret|token|key|credential)[^=&;?#]*=)[^&;#]*");

    private ReportUrls() {
    }

    /** @return {@code url} without credentials, or null when {@code url} is null */
    public static String sanitise(String url) {
        if (url == null) {
            return null;
        }
        String text = USER_INFO.matcher(url).replaceFirst("$1");
        text = ORACLE_THIN_CREDENTIALS.matcher(text).replaceFirst("$1@");
        return CREDENTIAL_PARAMETER.matcher(text).replaceAll("$1***");
    }
}
```

`RunInfo.java`:

```java
package com.idm.test.engine.result;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a report says about the run itself.
 *
 * @param runner         who ran it, e.g. {@code Real environment}, {@code Containers}, {@code Embedded}
 * @param startedAt      when the run started
 * @param profiles       the active Spring profiles, empty when none
 * @param scenarioFilter the {@code -Dscenario} value, or null
 * @param endpoints      label to URL for what the selected scenarios use; sanitised with
 *                       {@link ReportUrls#sanitise}, sorted by label, null URLs dropped
 */
public record RunInfo(String runner, Instant startedAt, List<String> profiles, String scenarioFilter,
                      Map<String, String> endpoints) {

    public RunInfo {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        Map<String, String> safe = new TreeMap<>();
        if (endpoints != null) {
            endpoints.forEach((label, url) -> {
                if (url != null) {
                    safe.put(label, ReportUrls.sanitise(url));
                }
            });
        }
        endpoints = Collections.unmodifiableMap(safe);
    }

    /** A run described only by its runner's name, starting now. */
    public static RunInfo minimal(String runner) {
        return new RunInfo(runner, Instant.now(), List.of(), null, Map.of());
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='ReportUrlsTest,RunInfoTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass.

- [ ] **Step 5: Falsify**

One at a time, make the change, run the two test classes, confirm the named test fails, then revert:
- remove the `USER_INFO` replacement → `testUserInfoRemoved` fails
- drop `key` from the parameter pattern → `testCredentialParametersMasked` fails
- skip `ReportUrls.sanitise` in `RunInfo` → `testEndpoints` fails

Record each result in the report.

- [ ] **Step 6: Commit**

```bash
git add idm-test-engine/src/main/java/com/idm/test/engine/result/ReportUrls.java idm-test-engine/src/main/java/com/idm/test/engine/result/RunInfo.java idm-test-engine/src/test/java/com/idm/test/engine/result/ReportUrlsTest.java idm-test-engine/src/test/java/com/idm/test/engine/result/RunInfoTest.java
git commit -m "feat: describe a run for reports, with credentials removed from its endpoint URLs

ITF-36

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `HtmlReportWriter` (engine)

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/HtmlReportWriter.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/result/HtmlReportWriterTest.java`

**Interfaces:**
- Consumes (Task 1): `RunInfo` (`runner()`, `startedAt()`, `profiles()`, `scenarioFilter()`, `endpoints()`).
- Consumes (existing): `ScenarioResult(name, sourceName, success, durationMillis, steps, cleanup, failureDetail, description)` with `failureSummary()`; `StepResult(index, type, resolvedParams, success, durationMillis, message, attempts, waitedMillis, description)`; `CleanupResult(index, type, resolvedParams, status, durationMillis, message, description)`; `CleanupStatus` `SUCCEEDED`/`FAILED`/`SKIPPED`.
- Produces: `public static void HtmlReportWriter.write(RunInfo run, List<ScenarioResult> results, Path target)`; package-private `static String render(RunInfo run, List<ScenarioResult> results, Instant now)`.

- [ ] **Step 1: Write the failing tests**

`HtmlReportWriterTest.java`:

```java
package com.idm.test.engine.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
                .contains("data-tile=\"failed\">2<");
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
                .contains("Later steps were not run.")
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
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=HtmlReportWriterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation fails (`HtmlReportWriter` doesn't exist).

- [ ] **Step 3: Implement `HtmlReportWriter`**

```java
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private static final List<String> SECRET_KEYS =
            List.of("password", "passwd", "secret", "token", "authorization", "credential");
    private static final String MASK = "***";
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
                .append(tile("duration", "Duration", duration(total)))
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
                    html.append("<li class=\"not-run\">Later steps were not run.</li>\n");
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

    /** A copy with every value under a secret-looking key, at any depth, replaced by {@value #MASK}. */
    static Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, inner) -> {
                String name = String.valueOf(key);
                copy.put(name, isSecret(name) ? MASK : mask(inner));
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(inner -> copy.add(mask(inner)));
            return copy;
        }
        return value;
    }

    private static boolean isSecret(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return SECRET_KEYS.stream().anyMatch(lower::contains);
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
            @media print{.controls{display:none}details>*{display:block}details.scenario{break-inside:avoid-page}}
            """;

    private static final String JS = """
            function setAll(open){document.querySelectorAll('details.scenario').forEach(function(d){d.open=open;});}
            function onlyFailed(button){var on=button.getAttribute('aria-pressed')!=='true';
            button.setAttribute('aria-pressed',String(on));
            document.querySelectorAll('details.scenario.passed').forEach(function(d){d.hidden=on;});}
            window.addEventListener('beforeprint',function(){document.querySelectorAll('details').forEach(function(d){d.open=true;});});
            """;
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=HtmlReportWriterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass. If an assertion depends on Jackson's pretty-print spacing (`"dn" : "…"`) and Jackson prints differently, report it with the actual output; don't weaken the escaping assertion.

- [ ] **Step 5: Falsify**

One at a time, make the change, run `HtmlReportWriterTest`, confirm the named test fails, then revert:
- make `esc` return its input → `testEscaping` fails
- make `isSecret` return false → `testSecretParametersMasked` fails
- always emit `open` → `testOpenState` fails
- drop the `not-run` line → `testSteps` fails

Record each result in the report.

- [ ] **Step 6: Run the module**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips (includes `NoSpringDependencyTest`).

- [ ] **Step 7: Commit**

```bash
git add idm-test-engine/src/main/java/com/idm/test/engine/result/HtmlReportWriter.java idm-test-engine/src/test/java/com/idm/test/engine/result/HtmlReportWriterTest.java
git commit -m "feat: render a run's results as one self-contained HTML page

ITF-36

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Write the report from every runner

**Files:**
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RunInfos.java`
- Test: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/RunInfosTest.java`
- Modify: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioSupport.java`
- Modify: `YamlScenarioTest.java`, `MultiDirectoryScenarioTest.java`, `RealEnvironmentScenarios.java`, `ContainerScenarios.java` (all in `…/scenarios/engine/`)
- Modify (tests): `YamlScenarioValidationTest.java`, `RealEnvironmentLdapOnlyTest.java`

**Interfaces:**
- Consumes (Tasks 1–2): `RunInfo(String, Instant, List<String>, String, Map<String, String>)`, `RunInfo.minimal(String)`, `HtmlReportWriter.write(RunInfo, List<ScenarioResult>, Path)`.
- Consumes (existing): `ScenarioNeeds.of(List<Scenario>)` with `directories()` (containing `ScenarioNeeds.DEFAULT_DIRECTORY` for steps without `directory:`), `databases()`, `roles()`, `resources()`, `restTargets()`; `LdapDirectories.DEFAULT`, `LdapDirectories.resolveDefault(String, Set<String>)`; `LdapProperties.getUrl()`; `DatabaseProperties.DatabaseConfig.getJdbcUrl()`; `RestProperties.RestApiConfig.getBaseUrl()`.
- Produces:
  - `static RunInfo RunInfos.of(String runner, Environment environment, List<Scenario> scenarios)`
  - `ScenarioSupport.buildTests(String, List<String>, ScenarioValidator, ScenarioEngine, Path, Function<List<Scenario>, RunInfo>)`
  - `ScenarioSupport.buildTests(String, ScenarioValidator, ScenarioEngine, Path, Function<List<Scenario>, RunInfo>)`
  - `static Path ScenarioSupport.reportPath(Path resultsPath)`
  - `String RealEnvironmentScenarios.runnerName()` (package-private, overridable)

- [ ] **Step 1: Write the failing tests**

`RunInfosTest.java`:

```java
package com.idm.test.scenarios.engine;

import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.result.RunInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RunInfos")
class RunInfosTest {

    private static List<Scenario> scenarios(String yaml) {
        return List.of(ScenarioLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "t.yaml"));
    }

    private static MockEnvironment lab() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("idm.test.ldap.url", "ldap://old.lab:389");
        environment.setProperty("idm.test.directories.ad.url", "ldaps://ad.lab:636");
        environment.setProperty("idm.test.default-directory", "ad");
        environment.setProperty("idm.test.databases.db-postgres.jdbc-url", "jdbc:postgresql://idm:pw@db.lab/idm");
        environment.setProperty("idm.test.databases.unused.jdbc-url", "jdbc:postgresql://db.lab/unused");
        environment.setProperty("idm.test.soap.url", "https://idm.lab:8543/IDMProv/role/service");
        environment.setProperty("idm.test.soap.resource-url", "https://idm.lab:8543/IDMProv/resource/service");
        environment.setProperty("idm.test.rest.idm.base-url", "https://idm.lab/api");
        return environment;
    }

    @Test
    @DisplayName("Lists only the endpoints the scenarios use, sanitised, with the default directory resolved")
    void testUsedEndpoints() {
        RunInfo run = RunInfos.of("Real environment", lab(), scenarios("""
                name: t
                steps:
                  - ldap.verify:
                      dn: cn=x,ou=users
                      exists: true
                  - db.verify:
                      database: db-postgres
                      query: SELECT 1
                      expect: { rowCount: 1 }
                  - resources.grant:
                      user: cn=u
                      resource: cn=r
                """));

        assertThat(run.runner()).isEqualTo("Real environment");
        assertThat(run.profiles()).containsExactly("dev");
        assertThat(run.endpoints()).containsOnlyKeys("LDAP ad", "Database db-postgres", "Resource service");
        assertThat(run.endpoints().get("LDAP ad")).isEqualTo("ldaps://ad.lab:636");
        assertThat(run.endpoints().get("Database db-postgres")).isEqualTo("jdbc:postgresql://db.lab/idm");
    }

    @Test
    @DisplayName("The old idm.test.ldap block is the directory 'default'; roles and REST targets are listed when used")
    void testOldBlockRolesAndRest() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("idm.test.ldap.url", "ldap://old.lab:389");
        environment.setProperty("idm.test.soap.url", "https://idm.lab:8543/IDMProv/role/service");
        environment.setProperty("idm.test.rest.idm.base-url", "https://idm.lab/api");

        RunInfo run = RunInfos.of("Embedded", environment, scenarios("""
                name: t
                steps:
                  - ldap.delete:
                      dn: cn=x,ou=users
                  - roles.grant:
                      user: cn=u
                      roles: [cn=r]
                  - rest.request:
                      target: idm
                      method: GET
                      path: /x
                """));

        assertThat(run.endpoints()).containsOnlyKeys("LDAP default", "Role service", "REST idm");
        assertThat(run.endpoints().get("LDAP default")).isEqualTo("ldap://old.lab:389");
    }
}
```

In `YamlScenarioValidationTest.testFactoryHandlesMixOfValidAndInvalidFiles`, after the existing JSON assertions, add:

```java
        String report = java.nio.file.Files.readString(tempDir.resolve("scenario-report.html"));
        assertThat(report).contains("broken-wait.yaml").contains("valid-user.yaml")
                .contains("<details class=\"scenario failed\" open>");
```

In `RealEnvironmentLdapOnlyTest.stopDirectory()`, before `ldap.close()`, add a check that the run wrote a report naming the directory it used (import `java.nio.file.Files`, `java.nio.file.Path`, and the static `assertThat` if missing):

```java
        if (ldap != null) {
            String report = Files.readString(Path.of("target", "real-environment-report.html"));
            assertThat(report).contains("Real environment run").contains("LDAP default").contains(ldap.getUrl());
        }
```

(`stopDirectory` then needs `throws Exception`.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='RunInfosTest,YamlScenarioValidationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation fails (`RunInfos` doesn't exist).

- [ ] **Step 3: Implement `RunInfos`**

```java
package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.connector.ldap.config.LdapProperties;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.result.RunInfo;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the report's description of a run from the Spring environment and the scenarios it
 * selected: its profiles, its -Dscenario filter, and the endpoints those scenarios use (the same
 * needs the real-environment guards check). URLs are sanitised by {@link RunInfo} itself.
 */
final class RunInfos {

    private RunInfos() {
    }

    static RunInfo of(String runner, Environment environment, List<Scenario> scenarios) {
        ScenarioNeeds needs = ScenarioNeeds.of(scenarios);
        Binder binder = Binder.get(environment);
        Map<String, String> endpoints = new LinkedHashMap<>();

        directoryUrls(environment, binder, needs.directories())
                .forEach((name, url) -> endpoints.put("LDAP " + name, url));

        Map<String, DatabaseProperties.DatabaseConfig> databases = binder
                .bind("idm.test.databases", Bindable.mapOf(String.class, DatabaseProperties.DatabaseConfig.class))
                .orElse(Map.of());
        for (String database : needs.databases()) {
            DatabaseProperties.DatabaseConfig config = databases.get(database);
            endpoints.put("Database " + database, config == null ? null : config.getJdbcUrl());
        }

        if (needs.roles()) {
            endpoints.put("Role service", environment.getProperty("idm.test.soap.url"));
        }
        if (needs.resources()) {
            endpoints.put("Resource service", environment.getProperty("idm.test.soap.resource-url"));
        }

        Map<String, RestProperties.RestApiConfig> targets = binder
                .bind("idm.test.rest", Bindable.mapOf(String.class, RestProperties.RestApiConfig.class))
                .orElse(Map.of());
        for (String target : needs.restTargets()) {
            RestProperties.RestApiConfig config = targets.get(target);
            endpoints.put("REST " + target, config == null ? null : config.getBaseUrl());
        }

        return new RunInfo(runner, Instant.now(), List.of(environment.getActiveProfiles()),
                System.getProperty(ScenarioSupport.SCENARIO_PROPERTY), endpoints);
    }

    /** Directory name to URL for the directories used, the default one resolved as the guards resolve it. */
    private static Map<String, String> directoryUrls(Environment environment, Binder binder, Set<String> used) {
        Map<String, LdapProperties> named = binder
                .bind("idm.test.directories", Bindable.mapOf(String.class, LdapProperties.class))
                .orElse(Map.of());
        String legacyUrl = environment.getProperty("idm.test.ldap.url");
        Set<String> names = new TreeSet<>(named.keySet());
        if (named.isEmpty() || (legacyUrl != null && !legacyUrl.isBlank())) {
            names.add(LdapDirectories.DEFAULT);
        }
        Optional<String> defaultName;
        try {
            defaultName = LdapDirectories.resolveDefault(environment.getProperty("idm.test.default-directory"), names);
        } catch (IllegalStateException unknownDefault) {
            defaultName = Optional.empty();
        }

        Map<String, String> urls = new LinkedHashMap<>();
        for (String requested : used) {
            String name = ScenarioNeeds.DEFAULT_DIRECTORY.equals(requested) ? defaultName.orElse(null) : requested;
            if (name == null) {
                continue;
            }
            LdapProperties config = named.get(name);
            urls.put(name, config != null ? config.getUrl()
                    : LdapDirectories.DEFAULT.equals(name) ? legacyUrl : null);
        }
        return urls;
    }
}
```

- [ ] **Step 4: Write the report in `ScenarioSupport.buildTests`**

- Add the imports `com.idm.test.engine.result.HtmlReportWriter`, `com.idm.test.engine.result.RunInfo`, `java.util.function.Function`.
- Keep the two existing `buildTests` signatures, delegating with `scenarios -> RunInfo.minimal("Scenarios")`.
- Add `buildTests(String scenarioDir, ScenarioValidator validator, ScenarioEngine engine, Path resultsPath, Function<List<Scenario>, RunInfo> runInfo)`, delegating to the six-argument version with `scenarioFiles(scenarioDir)`.
- Restructure the full method into two passes: first load and validate every file (keeping each file's `Scenario` or its error message, in order), then build `RunInfo run = runInfo.apply(loadedScenarios)`, then create the dynamic tests. The per-file behaviour and messages stay exactly as they are today:

```java
    static List<DynamicTest> buildTests(
            String scenarioDir, List<String> files, ScenarioValidator validator, ScenarioEngine engine,
            Path resultsPath, Function<List<Scenario>, RunInfo> runInfo) throws URISyntaxException {

        Map<String, Scenario> loaded = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (String file : files) {
            try {
                Scenario scenario = ScenarioLoader.load(resource(scenarioDir, file), file);
                validator.validate(scenario, file);
                loaded.put(file, scenario);
            } catch (RuntimeException e) {
                failures.put(file, e.getMessage());
            }
        }
        RunInfo run = runInfo.apply(List.copyOf(loaded.values()));

        List<ScenarioResult> collected = Collections.synchronizedList(new ArrayList<>());
        List<DynamicTest> tests = new ArrayList<>();
        for (String file : files) {
            if (loaded.containsKey(file)) {
                Scenario scenario = loaded.get(file);
                tests.add(DynamicTest.dynamicTest(file, () -> {
                    ScenarioResult result = engine.run(scenario, file);
                    collected.add(result);
                    writeResults(collected, resultsPath, run);

                    assertThat(result.success())
                            .as(result.failureSummary())
                            .isTrue();
                }));
            } else {
                String message = failures.get(file);
                tests.add(DynamicTest.dynamicTest(file, () -> {
                    ScenarioResult result = new ScenarioResult(file, file, false, 0L, List.of(), message);
                    collected.add(result);
                    writeResults(collected, resultsPath, run);
                    fail(message);
                }));
            }
        }
        return tests;
    }

    /** The JSON results and, beside them, the HTML report, both of everything run so far. */
    private static void writeResults(List<ScenarioResult> collected, Path resultsPath, RunInfo run) {
        List<ScenarioResult> snapshot = List.copyOf(collected);
        JsonResultWriter.write(snapshot, resultsPath);
        HtmlReportWriter.write(run, snapshot, reportPath(resultsPath));
    }

    /** {@code x-results.json} becomes {@code x-report.html}; any other name gets {@code .html} appended. */
    static Path reportPath(Path resultsPath) {
        String name = resultsPath.getFileName().toString();
        String suffix = "-results.json";
        String report = name.endsWith(suffix)
                ? name.substring(0, name.length() - suffix.length()) + "-report.html"
                : name + ".html";
        return resultsPath.resolveSibling(report);
    }
```

Keep the existing method javadoc on the six-argument method, adding one sentence: "Every result is written as JSON and, beside it, as an HTML report (ITF-36)." Add `LinkedHashMap` and `Map` imports if missing.

- [ ] **Step 5: Give every runner its name**

- **`YamlScenarioTest`:** add `@Autowired private Environment environment;` (import `org.springframework.beans.factory.annotation.Autowired` and `org.springframework.core.env.Environment`), and pass `scenarios -> RunInfos.of("Embedded", environment, scenarios)` as the new last argument of its `buildTests` call.
- **`MultiDirectoryScenarioTest`:** the same, with `"Embedded (two directories)"`.
- **`RealEnvironmentScenarios`:** add

  ```java
      /** The runner's name in the report; ContainerScenarios overrides it. */
      String runnerName() {
          return "Real environment";
      }
  ```

  and pass `scenarios -> RunInfos.of(runnerName(), environment, scenarios)` to its `buildTests` call.
- **`ContainerScenarios`:** override `runnerName()` to return `"Containers"`.
- **`YamlScenarioValidationTest`** keeps the old signature (report runner "Scenarios").

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest='RunInfosTest,YamlScenarioValidationTest,RealEnvironmentLdapOnlyTest,ScenarioSupportIsolationTest,RealEnvironmentScenariosStructureTest,ContainerScenariosStructureTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass.

- [ ] **Step 7: Falsify**

One at a time, make the change, run the tests from Step 6, confirm the named test fails, then revert:
- skip `HtmlReportWriter.write` in `writeResults` → `YamlScenarioValidationTest` and `RealEnvironmentLdapOnlyTest` fail
- in `RunInfos`, list every configured database instead of `needs.databases()` → `testUsedEndpoints` fails
- map the default marker to `LdapDirectories.DEFAULT` always → `testUsedEndpoints` fails

Record each result in the report.

- [ ] **Step 8: Run the module**

Run: `./mvnw test -pl idm-test-scenarios -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips; `target/scenario-report.html` and `target/multi-directory-report.html` exist under `idm-test-scenarios`; the FAILED counts in both results JSON files are 0.

- [ ] **Step 9: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine
git commit -m "feat: write an HTML report beside every runner's JSON results

ITF-36

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Documentation and the full suite

**Files:**
- Modify: `docs/reference/writing-scenarios.md` ("9. Results")
- Modify: `docs/reference/running-tests.md`
- Modify: `idm-test-scenarios/src/test/resources/scenarios-real/README.md`
- Modify: `README.md`
- Modify: `docs/architecture/capabilities.md`

**Interfaces:**
- Consumes: the report files `target/scenario-report.html`, `target/real-environment-report.html`, `target/multi-directory-report.html` (under `idm-test-scenarios/`), and the behaviour in the Global Constraints.

- [ ] **Step 1: Update the docs**

**`docs/reference/writing-scenarios.md`, "9. Results":** after the table of results files, add:

```markdown
**The HTML report.** Beside each results file, the run writes a report you can open in any
browser, attach or e-mail: `scenario-report.html`, `real-environment-report.html` or
`multi-directory-report.html`. It is one file with nothing loaded from outside, and it is
rewritten after every scenario, so an interrupted run still has one.

- **Header:** the runner, start time, duration, active profiles, the `-Dscenario` filter, and the
  endpoints the selected scenarios use. User names and passwords in URLs are removed, and
  credential-looking URL parameters are shown as `***`.
- **Summary:** scenarios, passed, failed, total duration; Expand all, Collapse all, Only failed.
- **Each scenario:** open when it failed. Each step shows its description, duration (attempts and
  time waited for `wait:` steps), message and parameters; cleanup entries follow with their
  status. Parameter values under keys that look secret (`password`, `secret`, `token`,
  `authorization`, …) are shown as `***` in the report; the JSON keeps them as recorded.
- **Printing** or saving as PDF expands every scenario.
```

**`docs/reference/running-tests.md`:** where it describes the results or a real run's output, add one line: "Each run also writes an HTML report beside its results file, e.g. `idm-test-scenarios/target/real-environment-report.html`; see the authoring reference, section 9."

**`idm-test-scenarios/src/test/resources/scenarios-real/README.md`:** where it mentions `real-environment-results.json`, add: "Open `target/real-environment-report.html` for a readable report of the run (see the authoring reference, section 9)."

**`README.md`:** where it lists run outputs or results, add the HTML report in one line; if there is no such place, add it under the section that describes `real-environment-results.json` or `scenario-results.json`.

**`docs/architecture/capabilities.md`:** add a row after "YAML step descriptions" in the table's five-column format: `| HTML report | Implemented (ITF-36) | Read and share a run's results | [HtmlReportWriter][report], [ScenarioSupport][support] | One self-contained file per run beside the JSON results, rewritten after each scenario; header with runner, profiles, filter and sanitised endpoints; failed scenarios open; secret-looking parameters masked. Per-step evidence (HTTP/SOAP, LDAP, SQL) is ITF-37 |`, and add the link definitions `[report]: ../../idm-test-engine/src/main/java/com/idm/test/engine/result/HtmlReportWriter.java` and, if not already defined, `[support]: ../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioSupport.java`.

- [ ] **Step 2: Run the full suite**

Run: `./mvnw -q clean test`
Expected: exit 0.

Then:
```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -h -o 'failures="[0-9]*"\|errors="[0-9]*"\|skipped="[0-9]*"' */target/surefire-reports/TEST-*.xml | grep -v '"0"' | wc -l
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json idm-test-scenarios/target/multi-directory-results.json
grep -c '<details class="scenario' idm-test-scenarios/target/scenario-report.html
```
Expected: a test count above 747; `0` non-zero attributes; both FAILED counts `0`; the last grep prints `20` (one section per corpus scenario). Include the raw output in the report. If `EmbeddedLdapServerTest.testLdifImport` or `LdapStepsTest` fails with a closed-connection error (known flakes), re-run once and report both runs.

If `docker info` succeeds, also run `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false`, confirm 20 tests and 0 failures in its Surefire XML, and that `idm-test-scenarios/target/real-environment-report.html` starts its title with `Containers run`. Otherwise report that Docker isn't available.

- [ ] **Step 3: Commit**

```bash
git add docs/reference/writing-scenarios.md docs/reference/running-tests.md idm-test-scenarios/src/test/resources/scenarios-real/README.md README.md docs/architecture/capabilities.md
git commit -m "docs: describe the HTML report and where each run writes it

ITF-36

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
