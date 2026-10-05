# Per-step Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** HTTP, REST, database and LDAP steps record what they sent and received (masked and size-limited); the JSON results and the HTML report show it under each step.

**Architecture:** A sealed `Evidence` interface with three records (`HttpEvidence`, `SqlEvidence`, `LdapEvidence`) in the engine's `spi` package; each record's canonical constructor masks and truncates, so unmasked evidence cannot exist. `StepOutcome` and `StepResult` gain an optional `evidence` component; the engine keeps the last attempt's. Jackson writes it with a `"kind"` discriminator; a new `EvidenceHtml` renders it in the report's existing per-step slot.

**Tech Stack:** Java 21 (records, sealed interfaces, pattern-matching switch), Jackson (already in the engine), JUnit 5, AssertJ, WireMock, H2, UnboundID.

**Ticket:** ITF-37. **Spec:** `docs/specs/2026-10-05-step-evidence-design.md`. **Follow-up:** ITF-40 (SOAP).

## Global Constraints

- The engine module stays Spring-free (`NoSpringDependencyTest`).
- Evidence is masked and truncated in each record's canonical constructor; masked values are `***`.
- Secret names (shared `Secrets` class): a name is a secret when, lower-cased, it contains `password`, `passwd`, `pwd`, `secret`, `token`, `authorization`, `credential`, `apikey`, `api_key`, `api-key` or `cookie`.
- Size limits, constants in code: HTTP body 16 384 characters each (`… [truncated, N more characters]`); 20 database rows (`totalRows` keeps the real count; report shows `… N more rows`); 1 024 characters per database value; 20 values per LDAP attribute (`… N more values`).
- SQL parameters masked when bound to a secret column found by `INSERT … (cols) VALUES (?, …)` or `col = ?` / `<>` / `!=` / `LIKE ?` (incl. `SET col = ?`), ignoring case and `"`/`` ` ``/`[]` quoting and `?` inside `'…'` literals; any other parameter recorded as given.
- No evidence when nothing was sent (malformed parameter, unknown target/database/directory, unsupported method), and none from a step that threw. Only the last attempt of a `wait:` step keeps evidence. Cleanup entries record none.
- JSON: `"evidence": { "kind": "http" | "sql" | "ldap", … }`, key omitted when null. HTML: `<details class="evidence">`, open when the step failed; no evidence → the existing empty `<div class="evidence"></div>`.
- Secrets never appear in step messages, reports or logs (ITF-23 rule).
- Commit trailer: a blank line, `ITF-37`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven: always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. If `./mvnw` finds no JVM: `export JAVA_HOME=~/.sdkman/candidates/java/21.0.11-zulu`.

**Controller clarifications beyond the spec:**
- Database row values under secret column names are masked too (a `SELECT *` returning `PASSWORD` must not leak) — same rule as everywhere else.
- `SqlEvidence` for a SQL error (the database answered with an error) has `rows`, `totalRows` and `updateCount` all null; the step message carries the error. An unknown database (`CONFIGURATION_ERROR`) records none.
- `ldap.verify` / `ldap.delete` on a missing entry record `LdapEvidence` with `present = false`.
- The LDAP summary line reads `<operation> <dn> · N attributes` (e.g. `create cn=jdoe,ou=users · 6 attributes`).
- The corpus has no `http.*`/`rest.*` steps and no secrets, so the runner-level test asserts SQL and LDAP evidence; HTTP evidence and masking are covered by step and unit tests.

---

## File Structure

| File | Responsibility |
|---|---|
| Create `idm-test-engine/src/main/java/com/idm/test/engine/result/Secrets.java` | The one secret-name rule and key-based masking |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/result/HtmlReportWriter.java` | Use `Secrets`; delegate the evidence slot to `EvidenceHtml`; CSS/JS |
| Create `idm-test-engine/src/main/java/com/idm/test/engine/spi/Evidence.java` | Sealed interface, limits, Jackson type info |
| Create `idm-test-engine/src/main/java/com/idm/test/engine/spi/HttpEvidence.java`, `SqlEvidence.java`, `LdapEvidence.java` | The records |
| Create `idm-test-engine/src/main/java/com/idm/test/engine/spi/EvidenceMasking.java` | Masking and truncation helpers (package-private) |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/spi/StepOutcome.java` | `evidence` component, `withEvidence` |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/result/StepResult.java` | `evidence` component |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java` | Carry the last outcome's evidence |
| Create `idm-test-engine/src/main/java/com/idm/test/engine/result/EvidenceHtml.java` | Render evidence |
| Modify `idm-test-engine/src/main/java/com/idm/test/engine/steps/HttpExchange.java`, `HttpRequestStep.java`, `HttpVerifyStep.java` | HTTP evidence |
| Create `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestExchange.java`; modify `RestRequestStep.java`, `RestVerifyStep.java` | REST evidence |
| Modify `DbExecuteStep.java`, `DbVerifyStep.java` | SQL evidence |
| Modify `LdapTarget.java`, `LdapCreateStep.java`, `LdapUpdateStep.java`, `LdapDeleteStep.java`, `LdapVerifyStep.java` | LDAP evidence |
| Tests: `spi/EvidenceTest.java` (new), `ScenarioEngineTest`, `result/JsonResultWriterTest`, `result/HtmlReportWriterTest`, `steps/HttpStepsTest`, `steps/HttpVerifyStepsTest`, `steps/RestStepsTest`, `steps/DbStepsTest`, `steps/LdapStepsTest`, scenarios `engine/EvidenceReportTest.java` (new) | |
| Docs: `docs/reference/writing-scenarios.md` §9, `docs/architecture/capabilities.md`, `README.md` | |

---

### Task 1: `Secrets` and the evidence records

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/Secrets.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/result/HtmlReportWriter.java` (remove `SECRET_KEYS`, `MASK`, `isSecret`; `mask` delegates)
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/spi/Evidence.java`, `HttpEvidence.java`, `SqlEvidence.java`, `LdapEvidence.java`, `EvidenceMasking.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/spi/EvidenceTest.java`

**Interfaces:**
- Produces:
  - `public final class Secrets { public static final String MASK = "***"; public static boolean isSecret(String name); public static Object maskByKey(Object value); }`
  - `public sealed interface Evidence permits HttpEvidence, SqlEvidence, LdapEvidence` with `int MAX_BODY_CHARS = 16_384, MAX_ROWS = 20, MAX_VALUE_CHARS = 1_024, MAX_ATTRIBUTE_VALUES = 20`
  - `public record HttpEvidence(String method, String url, Map<String, List<String>> requestHeaders, Object requestBody, Integer status, Map<String, List<String>> responseHeaders, Object responseBody, long durationMillis, String error)`; `static Map<String, List<String>> headersOf(Map<String, String>)`
  - `public record SqlEvidence(String database, String sql, List<Object> params, List<Map<String, Object>> rows, Integer totalRows, Integer updateCount)`; `static SqlEvidence query(String database, String sql, Object[] params, List<Map<String, Object>> rows)`, `update(String, String, Object[], int count)`, `failed(String, String, Object[])`
  - `public record LdapEvidence(String directory, String dn, String operation, Map<String, Object> attributes, Boolean present)`; `static LdapEvidence written(String directory, String dn, String operation, Map<String, ?> attributes)`, `read(String directory, String dn, Map<String, ?> attributes)`, `deleted(String directory, String dn)`, `exists(String directory, String dn, boolean present)`, `absent(String directory, String dn, String operation)`

- [ ] **Step 1: Write the failing test**

```java
package com.idm.test.engine.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Evidence masking and size limits")
class EvidenceTest {

    private static HttpEvidence http(Map<String, List<String>> requestHeaders, Object requestBody, Object responseBody) {
        return new HttpEvidence("POST", "https://idm.lab/api", requestHeaders, requestBody, 200,
                Map.of("Set-Cookie", List.of("s=1"), "Content-Type", List.of("application/json")), responseBody, 5, null);
    }

    @Test
    @DisplayName("Secret-named headers are masked, others kept")
    void headers() {
        HttpEvidence evidence = http(Map.of("Authorization", List.of("Bearer abc"), "Accept", List.of("x")), null, null);
        assertThat(evidence.requestHeaders()).containsEntry("Authorization", List.of("***")).containsEntry("Accept", List.of("x"));
        assertThat(evidence.responseHeaders()).containsEntry("Set-Cookie", List.of("***"))
                .containsEntry("Content-Type", List.of("application/json"));
        assertThat(HttpEvidence.headersOf(Map.of("X-Api-Key", "k"))).containsEntry("X-Api-Key", List.of("k"));
    }

    @Test
    @DisplayName("JSON structures and JSON text are masked by key at any depth")
    @SuppressWarnings("unchecked")
    void json() {
        HttpEvidence evidence = http(Map.of(),
                Map.of("user", Map.of("password", "p", "name", "n")),
                "{\"apiToken\":\"t0k\",\"id\":1}");
        Map<String, Object> user = (Map<String, Object>) ((Map<String, Object>) evidence.requestBody()).get("user");
        assertThat(user).containsEntry("password", "***").containsEntry("name", "n");
        assertThat(evidence.responseBody()).isEqualTo("{\"apiToken\":\"***\",\"id\":1}");
    }

    @Test
    @DisplayName("XML elements and attributes with secret names are masked, with or without a prefix")
    void xml() {
        HttpEvidence evidence = http(Map.of(),
                "<s:Envelope><s:Body><login><user>jdoe</user><ns:password>p4ss</ns:password></login></s:Body></s:Envelope>",
                "<auth clientSecret=\"x1\" user=\"u\"/>");
        assertThat((String) evidence.requestBody()).contains("<ns:password>***</ns:password>")
                .contains("<user>jdoe</user>").doesNotContain("p4ss");
        assertThat((String) evidence.responseBody()).contains("clientSecret=\"***\"").contains("user=\"u\"")
                .doesNotContain("x1");
    }

    @Test
    @DisplayName("Form bodies are masked by parameter name")
    void form() {
        HttpEvidence evidence = http(Map.of(), "grant_type=client_credentials&client_secret=abc&scope=x", null);
        assertThat(evidence.requestBody()).isEqualTo("grant_type=client_credentials&client_secret=***&scope=x");
    }

    @Test
    @DisplayName("URLs lose user info and secret query values")
    void url() {
        HttpEvidence evidence = new HttpEvidence("GET", "https://u:pw9@idm.lab/x?token=abc&q=1",
                Map.of(), null, null, null, null, 0, "refused");
        assertThat(evidence.url()).doesNotContain("pw9").doesNotContain("abc").contains("token=***").contains("q=1");
    }

    @Test
    @DisplayName("Bodies are cut at 16 384 characters; an oversized structure becomes text")
    void bodyLimit() {
        HttpEvidence text = http(Map.of(), "x".repeat(Evidence.MAX_BODY_CHARS + 10), null);
        assertThat((String) text.requestBody()).startsWith("x".repeat(Evidence.MAX_BODY_CHARS))
                .endsWith("… [truncated, 10 more characters]");

        HttpEvidence structure = http(Map.of(), Map.of("data", "y".repeat(20_000)), null);
        assertThat(structure.requestBody()).isInstanceOf(String.class);
        assertThat((String) structure.requestBody()).contains("[truncated, ");
    }

    @Test
    @DisplayName("SQL parameters bound to secret columns are masked; others are kept")
    void sqlParameters() {
        assertThat(SqlEvidence.update("db", "INSERT INTO accounts (username, pwd, note) VALUES (?, ?, ?)",
                new Object[]{"jdoe", "S3cret!", "n"}, 1).params()).containsExactly("jdoe", "***", "n");
        assertThat(SqlEvidence.failed("db", "SELECT * FROM t WHERE username = ? AND password = ?",
                new Object[]{"jdoe", "S3cret!"}).params()).containsExactly("jdoe", "***");
        assertThat(SqlEvidence.update("db", "UPDATE t SET \"Password\" = ? WHERE id = ?",
                new Object[]{"S3cret!", 7}, 1).params()).containsExactly("***", 7);
        assertThat(SqlEvidence.failed("db", "SELECT * FROM t WHERE t.api_key LIKE ?",
                new Object[]{"k"}).params()).containsExactly("***");
        assertThat(SqlEvidence.failed("db", "SELECT '?' AS q FROM t WHERE token <> ?",
                new Object[]{"v"}).params()).containsExactly("***");
        assertThat(SqlEvidence.failed("db", "SELECT * FROM t WHERE f(?) > 1",
                new Object[]{"S3cret!"}).params()).containsExactly("S3cret!");
    }

    @Test
    @DisplayName("Rows: at most 20 kept, secret columns masked, long values cut, binary shown as its size")
    void rows() {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(Map.of("id", i));
        }
        SqlEvidence capped = SqlEvidence.query("db", "SELECT id FROM t", new Object[0], many);
        assertThat(capped.rows()).hasSize(20);
        assertThat(capped.totalRows()).isEqualTo(25);

        Map<String, Object> row = new HashMap<>();
        row.put("PASSWORD", "hunter2");
        row.put("NOTE", "z".repeat(1_100));
        row.put("PHOTO", new byte[]{1, 2, 3});
        row.put("EMPTY", null);
        SqlEvidence one = SqlEvidence.query("db", "SELECT * FROM t", new Object[0], List.of(row));
        assertThat(one.rows().get(0)).containsEntry("PASSWORD", "***").containsEntry("PHOTO", "<3 bytes>")
                .containsEntry("EMPTY", null);
        assertThat((String) one.rows().get(0).get("NOTE")).endsWith("… [truncated, 76 more characters]");
    }

    @Test
    @DisplayName("LDAP: secret attributes masked, at most 20 values per attribute")
    void ldap() {
        List<String> members = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            members.add("cn=m" + i);
        }
        LdapEvidence evidence = new LdapEvidence(null, "cn=u,ou=users", "create",
                Map.of("userPassword", "x", "unicodePwd", List.of("y"), "member", members, "sn", "U"), null);
        assertThat(evidence.attributes()).containsEntry("userPassword", "***").containsEntry("unicodePwd", "***")
                .containsEntry("sn", "U");
        List<?> kept = (List<?>) evidence.attributes().get("member");
        assertThat(kept).hasSize(21);
        assertThat(kept.get(20)).isEqualTo("… 5 more values");
    }

    @Test
    @DisplayName("Factories set the operation and presence")
    void ldapFactories() {
        assertThat(LdapEvidence.deleted("ad", "cn=u").operation()).isEqualTo("delete");
        assertThat(LdapEvidence.exists(null, "cn=u", true).present()).isTrue();
        assertThat(LdapEvidence.absent(null, "cn=u", "read").present()).isFalse();
        assertThat(LdapEvidence.read(null, "cn=u", Map.of("cn", "u")).attributes()).containsEntry("cn", "u");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=EvidenceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`HttpEvidence` not found).

- [ ] **Step 3: Implement**

`Secrets.java`:

```java
package com.idm.test.engine.result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one rule for what counts as a secret, shared by the report's parameter masking and by step
 * evidence, so the two can never disagree.
 */
public final class Secrets {

    public static final String MASK = "***";

    private static final List<String> SECRET_NAMES = List.of("password", "passwd", "pwd", "secret", "token",
            "authorization", "credential", "apikey", "api_key", "api-key", "cookie");

    private Secrets() {
    }

    /** Whether a key, header, column or attribute name looks like it holds a secret. */
    public static boolean isSecret(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return SECRET_NAMES.stream().anyMatch(lower::contains);
    }

    /** A copy with every value under a secret-looking key, at any depth, replaced by {@value #MASK}. */
    public static Object maskByKey(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, inner) -> {
                String name = String.valueOf(key);
                copy.put(name, isSecret(name) ? MASK : maskByKey(inner));
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(inner -> copy.add(maskByKey(inner)));
            return copy;
        }
        return value;
    }
}
```

In `HtmlReportWriter`, delete `SECRET_KEYS`, `MASK` and `isSecret`, and make `mask` delegate (keep it: callers and tests use it):

```java
    /** A copy with every value under a secret-looking key, at any depth, replaced by {@code ***}. */
    static Object mask(Object value) {
        return Secrets.maskByKey(value);
    }
```

`Evidence.java`:

```java
package com.idm.test.engine.spi;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What a step sent and received, recorded so the results and the report can show it under the
 * step. Every implementation masks secrets and enforces the size limits in its constructor, so an
 * unmasked or oversized evidence object cannot exist.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HttpEvidence.class, name = "http"),
        @JsonSubTypes.Type(value = SqlEvidence.class, name = "sql"),
        @JsonSubTypes.Type(value = LdapEvidence.class, name = "ldap")})
public sealed interface Evidence permits HttpEvidence, SqlEvidence, LdapEvidence {

    /** Characters kept of each HTTP request or response body. */
    int MAX_BODY_CHARS = 16_384;

    /** Database rows kept. */
    int MAX_ROWS = 20;

    /** Characters kept of one database or LDAP value. */
    int MAX_VALUE_CHARS = 1_024;

    /** Values kept per LDAP attribute. */
    int MAX_ATTRIBUTE_VALUES = 20;
}
```

`EvidenceMasking.java`:

```java
package com.idm.test.engine.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idm.test.engine.result.Secrets;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masking and truncation for the evidence records' constructors. */
final class EvidenceMasking {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A leaf element: {@code <ns:name attrs>text</ns:name>}. */
    private static final Pattern XML_ELEMENT =
            Pattern.compile("<((?:[\\w.-]+:)?([\\w.-]+))(\\s[^<>]*)?>([^<]*)</\\1\\s*>");
    private static final Pattern XML_ATTRIBUTE =
            Pattern.compile("([\\w.:-]+)(\\s*=\\s*)(\"[^\"]*\"|'[^']*')");
    private static final Pattern FORM =
            Pattern.compile("[^\\s=&]+=[^&\\s]*(?:&[^\\s=&]+=[^&\\s]*)*");
    private static final Pattern FORM_PAIR = Pattern.compile("([^=&]+)=([^&]*)");
    private static final Pattern INSERT = Pattern.compile(
            "(?is)\\binsert\\s+into\\s+[\\w.]+\\s*\\(([^)]*)\\)\\s*values\\s*\\(([^)]*)\\)");
    private static final Pattern COMPARISON = Pattern.compile("(?is)([\\w.]+)\\s*(?:=|<>|!=|\\blike)\\s*$");

    private EvidenceMasking() {
    }

    static Map<String, List<String>> singleValued(Map<String, String> headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, value) -> result.put(name, List.of(value == null ? "" : value)));
        }
        return result;
    }

    static Map<String, List<String>> headers(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, List<String>> masked = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            int count = values == null || values.isEmpty() ? 1 : values.size();
            masked.put(name, Secrets.isSecret(name)
                    ? Collections.nCopies(count, Secrets.MASK)
                    : Collections.unmodifiableList(new ArrayList<>(values == null ? List.of() : values)));
        });
        return Collections.unmodifiableMap(masked);
    }

    static Object body(Object body) {
        if (body == null) {
            return null;
        }
        Object masked = body instanceof String text ? text(text) : Secrets.maskByKey(body);
        if (masked instanceof String text) {
            return truncate(text, Evidence.MAX_BODY_CHARS);
        }
        String serialised = json(masked);
        return serialised.length() > Evidence.MAX_BODY_CHARS ? truncate(serialised, Evidence.MAX_BODY_CHARS) : masked;
    }

    /** JSON, XML or form text with secret values masked; any other text unchanged. */
    static String text(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON.writeValueAsString(Secrets.maskByKey(JSON.readValue(trimmed, Object.class)));
            } catch (JsonProcessingException notJson) {
                return text;
            }
        }
        if (trimmed.startsWith("<")) {
            return xml(text);
        }
        if (FORM.matcher(trimmed).matches()) {
            return form(text);
        }
        return text;
    }

    private static String xml(String text) {
        String elements = XML_ELEMENT.matcher(text).replaceAll(match -> Matcher.quoteReplacement(
                Secrets.isSecret(match.group(2))
                        ? "<" + match.group(1) + (match.group(3) == null ? "" : match.group(3)) + ">"
                                + Secrets.MASK + "</" + match.group(1) + ">"
                        : match.group()));
        return XML_ATTRIBUTE.matcher(elements).replaceAll(match -> {
            String name = match.group(1);
            String local = name.substring(name.indexOf(':') + 1);
            if (name.startsWith("xmlns") || !Secrets.isSecret(local)) {
                return Matcher.quoteReplacement(match.group());
            }
            char quote = match.group(3).charAt(0);
            return Matcher.quoteReplacement(name + match.group(2) + quote + Secrets.MASK + quote);
        });
    }

    private static String form(String text) {
        return FORM_PAIR.matcher(text).replaceAll(match -> {
            String name;
            try {
                name = URLDecoder.decode(match.group(1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                name = match.group(1);
            }
            return Matcher.quoteReplacement(Secrets.isSecret(name) ? match.group(1) + "=" + Secrets.MASK : match.group());
        });
    }

    static String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "… [truncated, " + (text.length() - limit) + " more characters]";
    }

    /** Numbers, booleans and null as they are; binary as its size; anything else as text, cut at the value limit. */
    static Object value(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes>";
        }
        return truncate(String.valueOf(value), Evidence.MAX_VALUE_CHARS);
    }

    static List<Object> parameters(String sql, List<Object> params) {
        if (params == null) {
            return List.of();
        }
        Set<Integer> secret = secretParameters(sql);
        List<Object> masked = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            masked.add(secret.contains(i) ? Secrets.MASK : value(params.get(i)));
        }
        return Collections.unmodifiableList(masked);
    }

    /** Indexes of the {@code ?} placeholders bound to a secret-named column. */
    static Set<Integer> secretParameters(String sql) {
        if (sql == null) {
            return Set.of();
        }
        // Quote characters become spaces so positions stay the same.
        String plain = sql.replaceAll("[\"`\\[\\]]", " ");
        List<Integer> positions = placeholders(plain);
        Set<Integer> secret = new HashSet<>();

        Matcher insert = INSERT.matcher(plain);
        while (insert.find()) {
            String[] columns = insert.group(1).split(",");
            int offset = insert.start(2);
            int from = 0;
            String[] values = insert.group(2).split(",", -1);
            for (int column = 0; column < values.length; column++) {
                String value = values[column];
                if (value.strip().equals("?") && column < columns.length && Secrets.isSecret(columns[column].strip())) {
                    int index = positions.indexOf(offset + from + value.indexOf('?'));
                    if (index >= 0) {
                        secret.add(index);
                    }
                }
                from += value.length() + 1;
            }
        }

        for (int i = 0; i < positions.size(); i++) {
            Matcher comparison = COMPARISON.matcher(plain.substring(0, positions.get(i)));
            if (comparison.find()) {
                String column = comparison.group(1);
                if (Secrets.isSecret(column.substring(column.lastIndexOf('.') + 1))) {
                    secret.add(i);
                }
            }
        }
        return secret;
    }

    private static List<Integer> placeholders(String sql) {
        List<Integer> positions = new ArrayList<>();
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (c == '?' && !quoted) {
                positions.add(i);
            }
        }
        return positions;
    }

    static List<Map<String, Object>> rows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), Evidence.MAX_ROWS))) {
            Map<String, Object> copy = new LinkedHashMap<>();
            row.forEach((column, value) -> copy.put(column, Secrets.isSecret(column) ? Secrets.MASK : value(value)));
            kept.add(Collections.unmodifiableMap(copy));
        }
        return Collections.unmodifiableList(kept);
    }

    static Map<String, Object> attributes(Map<String, Object> attributes) {
        if (attributes == null) {
            return Map.of();
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        attributes.forEach((name, value) -> masked.put(name,
                Secrets.isSecret(name) ? Secrets.MASK : attributeValue(value)));
        return Collections.unmodifiableMap(masked);
    }

    private static Object attributeValue(Object value) {
        if (value instanceof Collection<?> values) {
            List<Object> kept = new ArrayList<>();
            values.stream().limit(Evidence.MAX_ATTRIBUTE_VALUES).forEach(v -> kept.add(value(v)));
            if (values.size() > Evidence.MAX_ATTRIBUTE_VALUES) {
                kept.add("… " + (values.size() - Evidence.MAX_ATTRIBUTE_VALUES) + " more values");
            }
            return Collections.unmodifiableList(kept);
        }
        return value(value);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
```

`HttpEvidence.java`:

```java
package com.idm.test.engine.spi;

import com.idm.test.engine.result.ReportUrls;

import java.util.List;
import java.util.Map;

/**
 * One HTTP exchange as a step made it. Bodies are text for http.* and JSON structures for rest.*.
 * The constructor masks secret headers and body values, sanitises the URL and cuts long bodies.
 *
 * @param status   the response status, or null when there was no response
 * @param error    why there was no response, or null
 */
public record HttpEvidence(String method, String url, Map<String, List<String>> requestHeaders, Object requestBody,
                           Integer status, Map<String, List<String>> responseHeaders, Object responseBody,
                           long durationMillis, String error) implements Evidence {

    public HttpEvidence {
        url = url == null ? null : ReportUrls.sanitise(url);
        requestHeaders = EvidenceMasking.headers(requestHeaders);
        requestBody = EvidenceMasking.body(requestBody);
        responseHeaders = EvidenceMasking.headers(responseHeaders);
        responseBody = EvidenceMasking.body(responseBody);
    }

    /** Request headers as the steps hold them, one value per name. */
    public static Map<String, List<String>> headersOf(Map<String, String> headers) {
        return EvidenceMasking.singleValued(headers);
    }
}
```

`SqlEvidence.java`:

```java
package com.idm.test.engine.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * One SQL statement as a step ran it. The constructor masks parameters bound to secret-named
 * columns and values in secret-named columns, keeps at most {@link #MAX_ROWS} rows and cuts long
 * values.
 *
 * @param rows        the rows returned (db.verify), or null
 * @param totalRows   how many rows the query returned before truncation, or null
 * @param updateCount the rows a db.execute statement changed, or null
 */
public record SqlEvidence(String database, String sql, List<Object> params, List<Map<String, Object>> rows,
                          Integer totalRows, Integer updateCount) implements Evidence {

    public SqlEvidence {
        params = EvidenceMasking.parameters(sql, params);
        rows = EvidenceMasking.rows(rows);
    }

    public static SqlEvidence query(String database, String sql, Object[] params, List<Map<String, Object>> rows) {
        return new SqlEvidence(database, sql, listOf(params), rows, rows.size(), null);
    }

    public static SqlEvidence update(String database, String sql, Object[] params, int count) {
        return new SqlEvidence(database, sql, listOf(params), null, null, count);
    }

    /** The statement reached the database, which answered with an error. */
    public static SqlEvidence failed(String database, String sql, Object[] params) {
        return new SqlEvidence(database, sql, listOf(params), null, null, null);
    }

    private static List<Object> listOf(Object[] params) {
        return params == null ? List.of() : new ArrayList<>(Arrays.asList(params));
    }
}
```

`LdapEvidence.java`:

```java
package com.idm.test.engine.spi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One LDAP operation as a step made it. The constructor masks secret-named attributes
 * ({@code userPassword}, {@code unicodePwd}, …) and keeps at most
 * {@link #MAX_ATTRIBUTE_VALUES} values per attribute.
 *
 * @param directory the {@code directory:} the step named, or null for the default
 * @param operation create, update, delete, read or exists
 * @param present   whether the entry was there, for exists and for a missing entry; else null
 */
public record LdapEvidence(String directory, String dn, String operation, Map<String, Object> attributes,
                           Boolean present) implements Evidence {

    public LdapEvidence {
        attributes = EvidenceMasking.attributes(attributes);
    }

    public static LdapEvidence written(String directory, String dn, String operation, Map<String, ?> attributes) {
        return new LdapEvidence(directory, dn, operation, copy(attributes), null);
    }

    public static LdapEvidence read(String directory, String dn, Map<String, ?> attributes) {
        return new LdapEvidence(directory, dn, "read", copy(attributes), null);
    }

    public static LdapEvidence deleted(String directory, String dn) {
        return new LdapEvidence(directory, dn, "delete", Map.of(), null);
    }

    public static LdapEvidence exists(String directory, String dn, boolean present) {
        return new LdapEvidence(directory, dn, "exists", Map.of(), present);
    }

    /** The operation found no entry at the DN. */
    public static LdapEvidence absent(String directory, String dn, String operation) {
        return new LdapEvidence(directory, dn, operation, Map.of(), false);
    }

    private static Map<String, Object> copy(Map<String, ?> attributes) {
        return attributes == null ? Map.of() : new LinkedHashMap<>(attributes);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='EvidenceTest,HtmlReportWriterTest,NoSpringDependencyTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass, 0 failures/errors/skips. If a masking test fails, fix the implementation, not the expectation (the expectations are the spec).

- [ ] **Step 5: Commit**

```bash
git add idm-test-engine
git commit -m "feat: evidence records that mask secrets and cap their size as they are built

ITF-37

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Evidence through `StepOutcome`, the engine and the JSON results

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/spi/StepOutcome.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/result/StepResult.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java` (the two `return new StepResult(...)` after a step ran, and `bind`)
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java`, `idm-test-engine/src/test/java/com/idm/test/engine/result/JsonResultWriterTest.java`

**Interfaces:**
- Consumes: `Evidence`, `LdapEvidence.exists(...)`, `SqlEvidence.query(...)` (Task 1).
- Produces: `StepOutcome(boolean success, String message, Object value, boolean isFinal, Evidence evidence)` with `StepOutcome withEvidence(Evidence evidence)`; existing 3- and 4-arg constructors and factories unchanged. `StepResult(..., String description, Evidence evidence)` (10 components); the existing 8- and 9-arg constructors kept, evidence null.

- [ ] **Step 1: Write the failing tests**

Add to `ScenarioEngineTest` (imports `com.idm.test.engine.spi.Evidence`, `com.idm.test.engine.spi.LdapEvidence`):

```java
    private static final Evidence FIRST = LdapEvidence.exists(null, "cn=first", false);
    private static final Evidence LAST = LdapEvidence.exists(null, "cn=last", true);

    @Test
    @DisplayName("A step's evidence is recorded in its result, failed or not")
    void testEvidenceRecorded() {
        StepType probe = new StepType() {
            public String name() { return "probe"; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return StepOutcome.failure("no").withEvidence(LAST);
            }
        };

        ScenarioResult result = engineWith(probe).run(scenario(new StepSpec("probe", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.steps().get(0).evidence()).isEqualTo(LAST);
    }

    @Test
    @DisplayName("Under wait:, only the last attempt's evidence is kept")
    void testOnlyLastAttemptEvidence() {
        AtomicInteger calls = new AtomicInteger();
        StepType probe = new StepType() {
            public String name() { return "probe"; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return calls.getAndIncrement() < 2
                        ? StepOutcome.failure("not yet").withEvidence(FIRST)
                        : StepOutcome.success("arrived").withEvidence(LAST);
            }
        };

        ScenarioResult result = engineWith(probe).run(scenario(new StepSpec("probe",
                Map.of("dn", "x", "wait", Map.of("timeout", "5s", "poll", "1s")))), "t.yaml");

        assertThat(result.steps().get(0).attempts()).isEqualTo(3);
        assertThat(result.steps().get(0).evidence()).isEqualTo(LAST);
    }

    @Test
    @DisplayName("A step that throws records no evidence")
    void testThrowingStepHasNoEvidence() {
        StepType probe = new StepType() {
            public String name() { return "probe"; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                throw new IllegalStateException("boom");
            }
        };

        ScenarioResult result = engineWith(probe).run(scenario(new StepSpec("probe", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.steps().get(0).evidence()).isNull();
    }

    @Test
    @DisplayName("A missing as: capture keeps the step's evidence")
    void testMissingCaptureKeepsEvidence() {
        StepType probe = new StepType() {
            public String name() { return "probe"; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return StepOutcome.success("ok").withEvidence(LAST);
            }
        };

        ScenarioResult result = engineWith(probe).run(scenario(
                new StepSpec("probe", Map.of("dn", "x", "as", "v"))), "t.yaml");

        assertThat(result.steps().get(0).success()).isFalse();
        assertThat(result.steps().get(0).evidence()).isEqualTo(LAST);
    }
```

If the validator or engine rejects `as` on the probe in `testMissingCaptureKeepsEvidence` for a reason unrelated to evidence (check how other `as:` tests in this file declare it), adapt the step spec to that convention; the assertion stays the same.

Add to `JsonResultWriterTest` (imports `com.idm.test.engine.spi.SqlEvidence`):

```java
    @Test
    @DisplayName("Writes a step's evidence with its kind, and leaves the key out when there is none")
    void testWritesEvidence(@TempDir Path dir) throws Exception {
        StepResult withEvidence = new StepResult(0, "db.verify", Map.of(), true, 5, "ok", null, null, null,
                SqlEvidence.query("hr", "SELECT status FROM users WHERE username = ?", new Object[]{"jdoe"},
                        List.of(Map.of("STATUS", "ACTIVE"))));
        StepResult without = new StepResult(1, "ldap.delete", Map.of(), true, 5, "ok", null, null, null);
        ScenarioResult scenario = new ScenarioResult("S", "s.yaml", true, 10, List.of(withEvidence, without), null);

        Path target = dir.resolve("r.json");
        JsonResultWriter.write(List.of(scenario), target);

        String json = Files.readString(target);
        assertThat(json).contains("\"kind\" : \"sql\"").contains("\"database\" : \"hr\"")
                .contains("\"totalRows\" : 1").contains("\"STATUS\" : \"ACTIVE\"");
        assertThat(json.split("\"evidence\"", -1)).hasSize(2);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='ScenarioEngineTest,JsonResultWriterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`withEvidence`, `evidence()` not found).

- [ ] **Step 3: Implement**

`StepOutcome.java` — replace the record header and constructors (keep the factories as they are; they call the 3- and 4-arg constructors). Update the Javadoc `@param` list with `@param evidence what the step sent and received, or null (see Evidence)`:

```java
public record StepOutcome(boolean success, String message, Object value, boolean isFinal, Evidence evidence) {

    /** An outcome without evidence. */
    public StepOutcome(boolean success, String message, Object value, boolean isFinal) {
        this(success, message, value, isFinal, null);
    }

    /** An outcome that waiting may still change: every success, and an ordinary failure. */
    public StepOutcome(boolean success, String message, Object value) {
        this(success, message, value, false);
    }

    /** This outcome, carrying what the step sent and received. */
    public StepOutcome withEvidence(Evidence evidence) {
        return new StepOutcome(success, message, value, isFinal, evidence);
    }
```

`StepResult.java` — add the component and keep both old constructors (imports `com.fasterxml.jackson.annotation.JsonInclude`, `com.idm.test.engine.spi.Evidence`); add `@param evidence what the step sent and received on its last attempt, or null` to the Javadoc:

```java
public record StepResult(
        int index,
        String type,
        Map<String, Object> resolvedParams,
        boolean success,
        long durationMillis,
        String message,
        Integer attempts,
        Long waitedMillis,
        String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) Evidence evidence) {

    /** A result without evidence. */
    public StepResult(int index, String type, Map<String, Object> resolvedParams, boolean success,
                      long durationMillis, String message, Integer attempts, Long waitedMillis, String description) {
        this(index, type, resolvedParams, success, durationMillis, message, attempts, waitedMillis, description, null);
    }

    /** A result for a step without a description. */
    public StepResult(int index, String type, Map<String, Object> resolvedParams, boolean success,
                      long durationMillis, String message, Integer attempts, Long waitedMillis) {
        this(index, type, resolvedParams, success, durationMillis, message, attempts, waitedMillis, null);
    }
}
```

`ScenarioEngine.java`:
- the no-wait path's return becomes
  ```java
            return new StepResult(index, step.type(), reportable(resolved), outcome.success(),
                    System.currentTimeMillis() - started, outcome.message(), null, null, description,
                    outcome.evidence());
  ```
- the wait path's final return becomes
  ```java
        return new StepResult(index, step.type(), reportable(resolved), success, elapsed, message,
                attempts.get(), waitedMillis, description, outcome == null ? null : outcome.evidence());
  ```
- in `bind`, the missing-capture failure keeps the evidence:
  ```java
            return StepOutcome.failure("step '" + stepType + "' declares as: '" + asName
                    + "' but produced no value to capture").withEvidence(outcome.evidence());
  ```

All other `new StepResult(...)` calls (failures before the step ran, a thrown exception) stay as they are, without evidence.

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures/errors/skips.

- [ ] **Step 5: Commit**

```bash
git add idm-test-engine
git commit -m "feat: carry a step's last evidence into its result and the JSON results

ITF-37

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Evidence in the HTML report

**Files:**
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/result/EvidenceHtml.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/result/HtmlReportWriter.java` (`appendStep`'s last line, `CSS`, `JS`)
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/result/HtmlReportWriterTest.java`

**Interfaces:**
- Consumes: `StepResult.evidence()` (Task 2); the evidence records (Task 1); `HtmlReportWriter.esc(String)` and `HtmlReportWriter.duration(long)` (package-private statics).
- Produces: `static void EvidenceHtml.append(StringBuilder html, StepResult step)`; `static String EvidenceHtml.summary(Evidence evidence)`.

- [ ] **Step 1: Write the failing tests**

Add to `HtmlReportWriterTest` (imports `com.idm.test.engine.spi.*` records, `java.util.ArrayList`):

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=HtmlReportWriterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the new tests fail (no `<details class="evidence"`).

- [ ] **Step 3: Implement**

`EvidenceHtml.java`:

```java
package com.idm.test.engine.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.SqlEvidence;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.idm.test.engine.result.HtmlReportWriter.duration;
import static com.idm.test.engine.result.HtmlReportWriter.esc;

/** Renders a step's evidence into its slot in the report; the evidence is already masked and cut. */
final class EvidenceHtml {

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper COMPACT = new ObjectMapper();

    private EvidenceHtml() {
    }

    static void append(StringBuilder html, StepResult step) {
        Evidence evidence = step.evidence();
        if (evidence == null) {
            html.append("<div class=\"evidence\"></div>\n");
            return;
        }
        html.append("<details class=\"evidence\"").append(step.success() ? "" : " open")
                .append("><summary>Evidence <span class=\"muted\">").append(esc(summary(evidence)));
        if (step.attempts() != null && step.attempts() > 1) {
            html.append(" (last attempt)");
        }
        html.append("</span></summary>\n");
        switch (evidence) {
            case HttpEvidence http -> appendHttp(html, http);
            case SqlEvidence sql -> appendSql(html, sql);
            case LdapEvidence ldap -> appendLdap(html, ldap);
        }
        html.append("</details>\n");
    }

    static String summary(Evidence evidence) {
        return switch (evidence) {
            case HttpEvidence http -> http.method() + " " + path(http.url()) + " → "
                    + (http.status() == null ? "no response" : http.status() + " · " + duration(http.durationMillis()));
            case SqlEvidence sql -> "SQL · " + count(sql.params().size(), "parameter") + " · "
                    + (sql.updateCount() != null ? "updated " + count(sql.updateCount(), "row")
                    : sql.totalRows() != null ? count(sql.totalRows(), "row") : "no result");
            case LdapEvidence ldap -> ldapSummary(ldap);
        };
    }

    private static String ldapSummary(LdapEvidence ldap) {
        String head = ldap.operation() + " " + ldap.dn();
        if (Boolean.FALSE.equals(ldap.present())) {
            return head + " · no entry";
        }
        if ("exists".equals(ldap.operation())) {
            return head + " · present";
        }
        if ("delete".equals(ldap.operation())) {
            return head;
        }
        return head + " · " + count(ldap.attributes().size(), "attribute");
    }

    private static void appendHttp(StringBuilder html, HttpEvidence http) {
        html.append("<div class=\"http\"><div class=\"req\"><div class=\"label\">Request</div><pre>")
                .append(esc(http.method() + " " + http.url())).append('\n');
        appendHeaders(html, http.requestHeaders());
        appendBody(html, http.requestBody());
        html.append("</pre></div><div class=\"res\"><div class=\"label\">Response</div><pre>");
        if (http.status() == null) {
            html.append(esc("no response: " + http.error()));
        } else {
            html.append(esc(http.status() + " · " + duration(http.durationMillis()))).append('\n');
            appendHeaders(html, http.responseHeaders());
            appendBody(html, http.responseBody());
        }
        html.append("</pre></div></div>\n");
    }

    private static void appendHeaders(StringBuilder html, Map<String, List<String>> headers) {
        if (headers == null) {
            return;
        }
        headers.forEach((name, values) -> html.append(esc(name + ": " + String.join(", ", values))).append('\n'));
    }

    private static void appendBody(StringBuilder html, Object body) {
        if (body == null || body instanceof String text && text.isEmpty()) {
            return;
        }
        html.append('\n').append(esc(pretty(body)));
    }

    /** JSON pretty-printed; any other text as it is. */
    private static String pretty(Object body) {
        try {
            if (body instanceof String text) {
                String trimmed = text.strip();
                if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                    return text;
                }
                return PRETTY.writeValueAsString(PRETTY.readValue(trimmed, Object.class));
            }
            return PRETTY.writeValueAsString(body);
        } catch (JsonProcessingException notJson) {
            return String.valueOf(body);
        }
    }

    private static void appendSql(StringBuilder html, SqlEvidence sql) {
        html.append("<pre>").append(esc(sql.sql())).append("</pre>\n");
        if (!sql.params().isEmpty()) {
            html.append("<div class=\"sql-params\">params: ").append(esc(compact(sql.params()))).append("</div>\n");
        }
        if (sql.updateCount() != null) {
            html.append("<div>updated ").append(esc(count(sql.updateCount(), "row"))).append("</div>\n");
            return;
        }
        if (sql.rows() == null) {
            return;
        }
        if (sql.rows().isEmpty()) {
            html.append("<div>no rows</div>\n");
            return;
        }
        html.append("<table class=\"rows\"><tr>");
        sql.rows().get(0).keySet().forEach(column -> html.append("<th>").append(esc(column)).append("</th>"));
        html.append("</tr>\n");
        for (Map<String, Object> row : sql.rows()) {
            html.append("<tr>");
            row.values().forEach(value -> html.append("<td>").append(esc(String.valueOf(value))).append("</td>"));
            html.append("</tr>\n");
        }
        html.append("</table>\n");
        int more = sql.totalRows() - sql.rows().size();
        if (more > 0) {
            html.append("<div class=\"more\">… ").append(more).append(" more rows</div>\n");
        }
    }

    private static void appendLdap(StringBuilder html, LdapEvidence ldap) {
        html.append("<div class=\"dn\">").append(esc(ldap.dn())).append(" · ").append(esc(ldap.operation()));
        if (ldap.directory() != null) {
            html.append(" · directory ").append(esc(ldap.directory()));
        }
        if (ldap.present() != null) {
            html.append(ldap.present() ? " · present" : " · absent");
        }
        html.append("</div>\n");
        if (ldap.attributes().isEmpty()) {
            return;
        }
        html.append("<table class=\"rows\">\n");
        ldap.attributes().forEach((name, value) -> {
            html.append("<tr><th>").append(esc(name)).append("</th><td>");
            if (value instanceof Collection<?> values) {
                html.append(String.join("<br>", values.stream().map(v -> esc(String.valueOf(v))).toList()));
            } else {
                html.append(esc(String.valueOf(value)));
            }
            html.append("</td></tr>\n");
        });
        html.append("</table>\n");
    }

    private static String path(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getRawPath() == null || uri.getRawPath().isEmpty()) {
                return url;
            }
            return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException | NullPointerException e) {
            return String.valueOf(url);
        }
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String compact(Object value) {
        try {
            return COMPACT.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
```

In `HtmlReportWriter.appendStep`, replace the last line `html.append("<div class=\"evidence\"></div>\n</li>\n");` with:

```java
        EvidenceHtml.append(html, step);
        html.append("</li>\n");
```

Append to the `CSS` text block, before the `@media print` line:

```
            details.evidence{margin-left:22px}details.evidence>summary{cursor:pointer}
            details.evidence pre{background:var(--card);padding:8px;border-radius:6px;overflow:auto;\
            white-space:pre-wrap;word-break:break-word;margin:4px 0}
            .http{display:grid;grid-template-columns:1fr 1fr;gap:8px}.http .label{font-size:12px;color:var(--muted)}
            @media (max-width:700px){.http{grid-template-columns:1fr}}
            table.rows{border-collapse:collapse;margin:4px 0}table.rows th,table.rows td{border:1px solid var(--line);\
            padding:2px 6px;text-align:left;vertical-align:top}
            .more,.sql-params,.dn{color:var(--muted);font-size:12px}
```

In `JS`, change `setAll` to:

```
            function setAll(open){document.querySelectorAll('details.scenario, details.evidence').forEach(function(d){d.open=open;});}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures/errors/skips.

- [ ] **Step 5: Commit**

```bash
git add idm-test-engine
git commit -m "feat: show each step's evidence in the HTML report

ITF-37

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: HTTP and REST steps record their exchanges

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/HttpExchange.java` (`send`)
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/HttpRequestStep.java`, `HttpVerifyStep.java`
- Create: `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestExchange.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestRequestStep.java`, `RestVerifyStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/HttpStepsTest.java`, `HttpVerifyStepsTest.java`, `RestStepsTest.java`

**Interfaces:**
- Consumes: `HttpEvidence` and `HttpEvidence.headersOf(Map<String, String>)` (Task 1); `StepOutcome.withEvidence` (Task 2).
- Produces: `record HttpExchange.Sent(OperationResult<TextResponse> result, HttpEvidence evidence)` returned by `HttpExchange.send(IdmRestConnector, Prepared)`; `RestExchange.send(IdmRestConnector, String method, String url, Object body, Map<String, String> headers)` returning `RestExchange.Sent(OperationResult<RestResponse> result, HttpEvidence evidence)` (evidence null for an unsupported method).

- [ ] **Step 1: Write the failing tests**

`HttpStepsTest` (imports `com.idm.test.engine.spi.HttpEvidence`):

```java
    @Test
    @DisplayName("Records the request and response as evidence, with secrets masked")
    void testEvidence() {
        stubFor(post(urlEqualTo("/users")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":7,\"token\":\"t0k\"}")));

        StepOutcome outcome = STEP.execute(params("target", "mock", "method", "POST", "path", "/users",
                "headers", Map.of("Authorization", "Bearer abc", "Content-Type", "application/json"),
                "body", "{\"userId\":\"jdoe\",\"password\":\"p\"}"), false);

        HttpEvidence evidence = (HttpEvidence) outcome.evidence();
        assertThat(evidence.method()).isEqualTo("POST");
        assertThat(evidence.url()).isEqualTo("http://localhost:" + WIREMOCK.port() + "/users");
        assertThat(evidence.requestHeaders()).containsEntry("Authorization", List.of("***"));
        assertThat((String) evidence.requestBody()).contains("\"userId\":\"jdoe\"").contains("\"password\":\"***\"");
        assertThat(evidence.status()).isEqualTo(201);
        assertThat((String) evidence.responseBody()).contains("\"token\":\"***\"").doesNotContain("t0k");
        assertThat(evidence.error()).isNull();
    }

    @Test
    @DisplayName("A connection error records the request and the error, with no response")
    void testEvidenceOnConnectionError() {
        StepOutcome outcome = STEP.execute(params("target", "down", "method", "GET", "path", "/x"), false);

        assertThat(outcome.success()).isFalse();
        HttpEvidence evidence = (HttpEvidence) outcome.evidence();
        assertThat(evidence.status()).isNull();
        assertThat(evidence.error()).isNotBlank();
    }

    @Test
    @DisplayName("Nothing sent, no evidence: unknown target or unsupported method")
    void testNoEvidenceWhenNothingSent() {
        assertThat(STEP.execute(params("target", "nope", "method", "GET", "path", "/x"), false).evidence()).isNull();
        assertThat(STEP.execute(params("target", "mock", "method", "BREW", "path", "/x"), false).evidence()).isNull();
    }
```

`HttpVerifyStepsTest`:

```java
    @Test
    @DisplayName("A failed check keeps the response it judged as evidence")
    void testEvidenceOnFailedCheck() {
        stubJson(200, "{\"department\":\"HR\"}");

        StepResult step = run(params("check", List.of("js:{ response.json.department === 'Finance' }")));

        assertThat(step.success()).isFalse();
        com.idm.test.engine.spi.HttpEvidence evidence = (com.idm.test.engine.spi.HttpEvidence) step.evidence();
        assertThat(evidence.status()).isEqualTo(200);
        assertThat((String) evidence.responseBody()).contains("HR");
    }
```

`RestStepsTest` (imports `com.idm.test.engine.spi.HttpEvidence`):

```java
    @Test
    @DisplayName("rest.request records the JSON request and response as structures, secrets masked")
    @SuppressWarnings("unchecked")
    void testRequestEvidence() {
        stubFor(post(urlEqualTo("/users")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":1,\"apiKey\":\"k9\"}")));

        StepOutcome outcome = new RestRequestStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "method", "POST", "path", "/users",
                "body", Map.of("userId", "jdoe", "password", "p")), false);

        HttpEvidence evidence = (HttpEvidence) outcome.evidence();
        assertThat((Map<String, Object>) evidence.requestBody()).containsEntry("userId", "jdoe").containsEntry("password", "***");
        assertThat(evidence.status()).isEqualTo(201);
        assertThat(String.valueOf(evidence.responseBody())).contains("***").doesNotContain("k9");
    }

    @Test
    @DisplayName("A failed rest.verify keeps the response body it compared")
    void testVerifyEvidenceOnMismatch() {
        stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"status\":\"PENDING\"}")));

        StepOutcome outcome = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth()).execute(Map.of(
                "target", "idm", "path", "/users/1",
                "expect", Map.of("body", Map.of("status", "ACTIVE"))), false);

        assertThat(outcome.success()).isFalse();
        assertThat(String.valueOf(((HttpEvidence) outcome.evidence()).responseBody())).contains("PENDING");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='HttpStepsTest,HttpVerifyStepsTest,RestStepsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the new tests fail (`evidence()` is null).

- [ ] **Step 3: Implement**

`HttpExchange` — replace `send` (imports `com.idm.test.engine.spi.HttpEvidence`):

```java
    /** A request that went out: the connector's result and the evidence of the exchange. */
    record Sent(OperationResult<TextResponse> result, HttpEvidence evidence) {
    }

    static Sent send(IdmRestConnector connector, Prepared request) {
        long started = System.currentTimeMillis();
        OperationResult<TextResponse> result =
                connector.exchangeText(request.method(), request.url(), request.body(), request.headers());
        long millis = System.currentTimeMillis() - started;
        TextResponse response = result.isSuccess() ? result.getData().orElse(null) : null;
        HttpEvidence evidence = new HttpEvidence(request.method(), request.url(),
                HttpEvidence.headersOf(request.headers()), request.body(),
                response == null ? null : response.status(),
                response == null ? null : response.headers(),
                response == null ? null : response.body(),
                millis, response == null ? result.getMessage() : null);
        return new Sent(result, evidence);
    }
```

`HttpRequestStep.execute`, from the send on:

```java
        HttpExchange.Sent sent = HttpExchange.send(connector, request);
        OperationResult<TextResponse> result = sent.result();
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("http.request failed for " + request.url() + ": " + result.getMessage())
                    .withEvidence(sent.evidence());
        }
        TextResponse response = result.getData().get();
        return StepOutcome.success(request.method() + " " + request.url() + " returned " + response.status(),
                HttpExchange.capture(response)).withEvidence(sent.evidence());
```

`HttpVerifyStep.execute`: after `prepare`, send and delegate the judging (everything that followed `HttpExchange.send` before) to a new private method, attaching evidence once:

```java
        HttpExchange.Sent sent = HttpExchange.send(connector, request);
        return judge(checks, request, sent.result(), captureRequested).withEvidence(sent.evidence());
    }

    /** Evaluates the checks against the response; every outcome is about the exchange just made. */
    private StepOutcome judge(List<DeferredExpression> checks, HttpExchange.Prepared request,
                              OperationResult<TextResponse> result, boolean captureRequested) {
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("http.verify failed for " + request.url() + ": " + result.getMessage());
        }
        // ... the existing body from `TextResponse response = result.getData().get();` to the final
        //     `return captureRequested ? ... : ...;`, moved here unchanged
    }
```

(The comment marks where the existing lines move verbatim; do not leave the comment in the code. Make `judge` static if nothing in it uses instance fields.)

`RestExchange.java`:

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.model.RestResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.HttpEvidence;

import java.util.Locale;
import java.util.Map;

/** Sends a rest.* request and records the exchange as evidence. */
final class RestExchange {

    /** The connector's result and the evidence, null when the method was refused before sending. */
    record Sent(OperationResult<RestResponse> result, HttpEvidence evidence) {
    }

    private RestExchange() {
    }

    static Sent send(IdmRestConnector connector, String method, String url, Object body, Map<String, String> headers) {
        long started = System.currentTimeMillis();
        OperationResult<RestResponse> result = connector.exchange(method, url, body, headers);
        long millis = System.currentTimeMillis() - started;
        String upper = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (!IdmRestConnector.SUPPORTED_METHODS.contains(upper)) {
            return new Sent(result, null);
        }
        RestResponse response = result.isSuccess() ? result.getData().orElse(null) : null;
        return new Sent(result, new HttpEvidence(upper, url, HttpEvidence.headersOf(headers), body,
                response == null ? null : response.status(),
                response == null ? null : response.headers(),
                response == null ? null : response.body(),
                millis, response == null ? result.getMessage() : null));
    }
}
```

`RestRequestStep.execute`: replace the `connector.exchange(...)` call and what follows:

```java
        RestExchange.Sent sent = RestExchange.send(connector, String.valueOf(params.get("method")), url, body, headers);
        OperationResult<RestResponse> result = sent.result();

        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "rest.request failed for " + url + ": " + result.getMessage()).withEvidence(sent.evidence());
        }

        RestResponse response = result.getData().get();

        return StepOutcome.success(
                String.valueOf(params.get("method")) + " " + url + " returned " + response.status(),
                captureOf(response)).withEvidence(sent.evidence());
```

`RestVerifyStep.execute`: replace the `connector.exchange(method, url, null, headers)` call and everything after it in `execute` with:

```java
        RestExchange.Sent sent = RestExchange.send(connector, method, url, null, headers);
        return judge(sent.result(), expect, method, url, captureRequested).withEvidence(sent.evidence());
    }

    private StepOutcome judge(OperationResult<RestResponse> result, Map<String, Object> expect, String method,
                              String url, boolean captureRequested) {
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure(
                    "rest.verify failed for " + url + ": " + result.getMessage());
        }

        RestResponse response = result.getData().get();

        if (expect.containsKey(STATUS)) {
            Object expectedStatus = expect.get(STATUS);
            if (!ValueComparison.equal(expectedStatus, response.status())) {
                return StepOutcome.failure("rest.verify on " + url + " expected status "
                        + expectedStatus + " but got " + response.status());
            }
        }

        if (expect.containsKey(BODY)) {
            String mismatch = mismatchIn(
                    StepValues.toMap(expect.get(BODY)), response.body(), "");
            if (mismatch != null) {
                return StepOutcome.failure("rest.verify on " + url + " " + mismatch);
            }
        }

        String message = method + " " + url + " verified, status " + response.status();
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("status", response.status());
        capture.put("headers", response.headers());
        capture.put("body", response.body());

        return captureRequested
                ? StepOutcome.success(message, capture)
                : StepOutcome.success(message);
    }
```

(Make `judge` static if `mismatchIn` is static.)

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures/errors/skips (includes `HttpAuthStepsTest`, which asserts no secret is reported). Known flake: `SoapStepsTest` port clashes — if it alone errors, re-run once and report both runs.

- [ ] **Step 5: Commit**

```bash
git add idm-test-engine
git commit -m "feat: http.* and rest.* steps record their requests and responses as evidence

ITF-37

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Database and LDAP steps record evidence

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/DbExecuteStep.java`, `DbVerifyStep.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapTarget.java`, `LdapCreateStep.java`, `LdapUpdateStep.java`, `LdapDeleteStep.java`, `LdapVerifyStep.java`
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/DbStepsTest.java`, `LdapStepsTest.java`

**Interfaces:**
- Consumes: `SqlEvidence.query/update/failed`, `LdapEvidence.written/read/deleted/exists/absent` (Task 1); `StepOutcome.withEvidence` (Task 2); `EdirectoryConnector.readAttributes(String)` (returns `OperationResult<Map<String, Object>>`, `ErrorType.NOT_FOUND` for a missing entry) and `LdapValueMatcher.match(String, Object, Object)` (idm-connector-ldap).
- Produces: `static String LdapTarget.requested(Map<String, Object> params)` — the `directory:` value, or null.

- [ ] **Step 1: Write the failing tests**

`DbStepsTest` (imports `com.idm.test.engine.spi.SqlEvidence`, `java.util.ArrayList`):

```java
    @Test
    @DisplayName("db.execute records the statement, masked parameters and the update count")
    void testExecuteEvidence() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS accounts (username VARCHAR(50), pwd VARCHAR(50))");

        StepOutcome outcome = new DbExecuteStep(connector).execute(Map.of(
                "database", DB,
                "statement", "INSERT INTO accounts (username, pwd) VALUES (?, ?)",
                "params", List.of("jdoe", "S3cret!")), false);

        SqlEvidence evidence = (SqlEvidence) outcome.evidence();
        assertThat(evidence.database()).isEqualTo(DB);
        assertThat(evidence.params()).containsExactly("jdoe", "***");
        assertThat(evidence.updateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("db.verify records at most 20 rows and the real row count, on success and on failure")
    void testVerifyEvidence() {
        for (int i = 0; i < 25; i++) {
            jdbc.update("INSERT INTO sap_users (username, status) VALUES (?, 'ACTIVE')", "u" + i);
        }

        StepOutcome outcome = new DbVerifyStep(connector).execute(Map.of(
                "database", DB,
                "query", "SELECT username FROM sap_users",
                "expect", Map.of("rowCount", 3)), false);

        assertThat(outcome.success()).isFalse();
        SqlEvidence evidence = (SqlEvidence) outcome.evidence();
        assertThat(evidence.rows()).hasSize(20);
        assertThat(evidence.totalRows()).isEqualTo(25);
    }

    @Test
    @DisplayName("A SQL error records the statement; an unknown database records nothing")
    void testEvidenceOnErrors() {
        StepOutcome sqlError = new DbVerifyStep(connector).execute(Map.of(
                "database", DB, "query", "SELECT * FROM no_such_table", "expect", Map.of("rowCount", 0)), false);
        assertThat(sqlError.success()).isFalse();
        assertThat(((SqlEvidence) sqlError.evidence()).rows()).isNull();

        StepOutcome unknown = new DbVerifyStep(connector).execute(Map.of(
                "database", "nope", "query", "SELECT 1", "expect", Map.of("rowCount", 1)), false);
        assertThat(unknown.evidence()).isNull();
    }
```

`LdapStepsTest` — replace the two mock tests `testVerifySkipsReadWhenNoCaptureRequested` and `testVerifyFailsWhenCaptureReadFailsAfterSuccessfulVerify` (ldap.verify now reads once per attempt and compares that read itself), change the last line of `testVerifyRejectsMalformedMatchers` from `verify(mockConnector, never()).verifyAttributes(any(), any());` to `verify(mockConnector, never()).readAttributes(any());`, and add the evidence tests (imports `com.idm.test.engine.spi.LdapEvidence`, `static org.mockito.Mockito.times`):

```java
    @Test
    @DisplayName("ldap.verify reads the entry once per attempt and compares that read")
    void testVerifyReadsOnce() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        when(mockConnector.readAttributes(any()))
                .thenReturn(OperationResult.success(Map.of("givenName", "Ada")));

        StepOutcome outcome = new LdapVerifyStep(only(mockConnector)).execute(Map.of(
                "dn", "cn=x,ou=users", "expect", Map.of("givenName", "Ada")), false);

        assertThat(outcome.success()).as(outcome.message()).isTrue();
        assertThat(outcome.value()).isNull();
        verify(mockConnector, times(1)).readAttributes(any());
        verify(mockConnector, never()).verifyAttributes(any(), any());
    }

    @Test
    @DisplayName("ldap.verify fails with the read's message, and no capture or evidence, when the read fails")
    void testVerifyFailsWhenReadFails() {
        EdirectoryConnector mockConnector = mock(EdirectoryConnector.class);
        when(mockConnector.readAttributes(any()))
                .thenReturn(OperationResult.<Map<String, Object>>failure("connection dropped"));

        StepOutcome outcome = new LdapVerifyStep(only(mockConnector)).execute(Map.of(
                "dn", "cn=x,ou=users", "expect", Map.of("givenName", "Ada")), true);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.value()).isNull();
        assertThat(outcome.evidence()).isNull();
        assertThat(outcome.message()).contains("cn=x,ou=users").contains("connection dropped");
    }

    @Test
    @DisplayName("LDAP steps record what they wrote, read or deleted, with userPassword masked")
    void testLdapEvidence() {
        String dn = "cn=ada.evidence,ou=users";
        StepOutcome created = new LdapCreateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("cn", "ada.evidence", "sn", "Lovelace", "userPassword", "S3cret!")), false);
        LdapEvidence written = (LdapEvidence) created.evidence();
        assertThat(written.operation()).isEqualTo("create");
        assertThat(written.attributes()).containsEntry("userPassword", "***").containsEntry("sn", "Lovelace");

        StepOutcome updated = new LdapUpdateStep(directories).execute(Map.of("dn", dn,
                "attributes", Map.of("title", "Engineer")), false);
        assertThat(((LdapEvidence) updated.evidence()).operation()).isEqualTo("update");

        StepOutcome mismatch = new LdapVerifyStep(directories).execute(Map.of("dn", dn,
                "expect", Map.of("title", "Manager")), false);
        assertThat(mismatch.success()).isFalse();
        LdapEvidence read = (LdapEvidence) mismatch.evidence();
        assertThat(read.operation()).isEqualTo("read");
        assertThat(read.attributes()).containsEntry("title", "Engineer");

        StepOutcome exists = new LdapVerifyStep(directories).execute(Map.of("dn", dn, "exists", true), false);
        assertThat(((LdapEvidence) exists.evidence()).present()).isTrue();

        StepOutcome deleted = new LdapDeleteStep(directories).execute(Map.of("dn", dn), false);
        assertThat(((LdapEvidence) deleted.evidence()).operation()).isEqualTo("delete");

        StepOutcome missing = new LdapVerifyStep(directories).execute(Map.of("dn", dn,
                "expect", Map.of("title", "Engineer")), false);
        assertThat(((LdapEvidence) missing.evidence()).present()).isFalse();

        StepOutcome deleteMissing = new LdapDeleteStep(directories).execute(Map.of("dn", dn), false);
        assertThat(((LdapEvidence) deleteMissing.evidence()).present()).isFalse();
    }

    @Test
    @DisplayName("Final failures before any I/O record no evidence")
    void testNoEvidenceBeforeIo() {
        assertThat(new LdapVerifyStep(directories).execute(Map.of("dn", "cn=x,ou=users"), false).evidence()).isNull();
        assertThat(new LdapCreateStep(directories).execute(Map.of("dn", "cn=x,ou=users",
                "attributes", Map.of("cn", "x", "sn", "X", "ou", List.of())), false).evidence()).isNull();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='DbStepsTest,LdapStepsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the new tests fail (no evidence; `readAttributes` never called by ldap.verify).

- [ ] **Step 3: Implement**

`DbExecuteStep.execute` (imports `com.idm.test.core.model.ErrorType`, `com.idm.test.engine.spi.SqlEvidence`):

```java
        OperationResult<Integer> result = connector.executeUpdate(database, statement, arguments);

        if (!result.isSuccess()) {
            StepOutcome failure = StepOutcome.failure(
                    "db.execute failed on '" + database + "': " + result.getMessage());
            // An unknown database was never contacted, so there is nothing to show.
            return result.getErrorType().filter(ErrorType.CONFIGURATION_ERROR::equals).isPresent()
                    ? failure
                    : failure.withEvidence(SqlEvidence.failed(database, statement, arguments));
        }

        int affected = result.getData().orElse(0);
        String message = "executed on '" + database + "', " + affected + " row(s) affected";

        return StepOutcome.success(message, affected)
                .withEvidence(SqlEvidence.update(database, statement, arguments, affected));
```

`DbVerifyStep.execute`: keep the final-failure branch for `CONFIGURATION_ERROR` without evidence; the ordinary SQL-error failure gets `.withEvidence(SqlEvidence.failed(database, query, arguments))`. After `List<Map<String, Object>> rows = result.getData().orElse(List.of());` add

```java
        SqlEvidence evidence = SqlEvidence.query(database, query, arguments, rows);
```

and append `.withEvidence(evidence)` to every `return` after that line (the rowCount failure, the row-count-not-one failure, the column mismatch failure, and the final success — both branches of the `captureRequested` ternary, e.g. `(captureRequested ? StepOutcome.success(message, rows) : StepOutcome.success(message)).withEvidence(evidence)`).

`LdapTarget` — add:

```java
    /** The {@code directory:} the step named, or null when it relies on the default. */
    static String requested(Map<String, Object> params) {
        Object directory = params.get("directory");
        return directory == null ? null : String.valueOf(directory);
    }
```

`LdapCreateStep.execute` (import `com.idm.test.engine.spi.LdapEvidence`): after the `LdapLists` check,

```java
        LdapEvidence evidence = LdapEvidence.written(LdapTarget.requested(params), dn, "create", attributes);
        LdapUser user = toLdapUser(dn, attributes);
        OperationResult<LdapUser> result = connector.create(user);

        if (!result.isSuccess()) {
            return StepOutcome.failure("ldap.create failed for " + where + ": " + result.getMessage())
                    .withEvidence(evidence);
        }

        return StepOutcome.success("created " + where, dn).withEvidence(evidence);
```

`LdapUpdateStep.execute`: same pattern after its `LdapLists` check — `LdapEvidence evidence = LdapEvidence.written(LdapTarget.requested(params), dn, "update", attributes);` attached to the connector failure and the success.

`LdapDeleteStep.execute`:
- the "could not check whether … exists" failure: unchanged (no evidence)
- the "found no entry … to delete" failure: `.withEvidence(LdapEvidence.absent(LdapTarget.requested(params), dn, "delete"))`
- the delete failure and the success: `.withEvidence(LdapEvidence.deleted(LdapTarget.requested(params), dn))`

`LdapVerifyStep` (imports `com.idm.test.core.model.ErrorType`, `com.idm.test.connector.ldap.client.LdapValueMatcher`, `com.idm.test.engine.spi.LdapEvidence`):
- pass `LdapTarget.requested(params)` into `verifyExistence` as a new first parameter `String directory`, and attach `.withEvidence(LdapEvidence.exists(directory, dn, present))` to its mismatch failure and its success (not to the "could not check" failure).
- replace everything in `execute` from `Map<String, Object> expected = …` to the end with:

```java
        Map<String, Object> expected = (Map<String, Object>) params.get("expect");
        String directory = LdapTarget.requested(params);

        // One read per attempt: it is compared, recorded as evidence and, when asked for,
        // captured — so neither evidence nor as: costs the directory a second read.
        OperationResult<Map<String, Object>> entry = connector.readAttributes(dn);
        if (!entry.isSuccess() || entry.getData().isEmpty()) {
            StepOutcome failure = StepOutcome.failure("ldap.verify failed for " + where + ": " + entry.getMessage());
            return entry.getErrorType().filter(ErrorType.NOT_FOUND::equals).isPresent()
                    ? failure.withEvidence(LdapEvidence.absent(directory, dn, "read"))
                    : failure;
        }

        Map<String, Object> actual = entry.getData().get();
        LdapEvidence evidence = LdapEvidence.read(directory, dn, actual);

        for (Map.Entry<String, Object> attribute : expected.entrySet()) {
            String mismatch = LdapValueMatcher.match(
                    attribute.getKey(), attribute.getValue(), actual.get(attribute.getKey()));
            if (mismatch != null) {
                return StepOutcome.failure("ldap.verify failed for " + where + ": " + mismatch)
                        .withEvidence(evidence);
            }
        }

        if (!captureRequested) {
            return StepOutcome.success("verified " + where).withEvidence(evidence);
        }

        // Every value of every attribute: a multi-valued one is a list, so it can be written
        // back by an ldap.update in cleanup.
        Map<String, Object> captured = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        captured.putAll(actual);
        captured.put("dn", dn);
        return StepOutcome.success("verified " + where, captured).withEvidence(evidence);
```

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures/errors/skips. Known flakes: `LdapStepsTest` "closed connection", `SoapStepsTest` port clash — re-run once and report both runs if one alone fails.

- [ ] **Step 5: Commit**

```bash
git add idm-test-engine
git commit -m "feat: database and LDAP steps record their statements, rows and entries as evidence

ITF-37

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Runner-level check and docs

**Files:**
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/EvidenceReportTest.java`
- Modify: `docs/reference/writing-scenarios.md` (§9 Results), `docs/architecture/capabilities.md` (the `HTML report` row), `README.md` (the sentence about the HTML report, ~line 181)

**Interfaces:**
- Consumes: everything above; `ScenarioSupport.buildTests(String dir, List<String> files, ScenarioValidator, ScenarioEngine, Path results, Function<List<Scenario>, RunInfo>)` and `ScenarioSupport.registry(...)` (as used in `YamlScenarioValidationTest`); `RunInfo.minimal(String)`.

- [ ] **Step 1: Write the test**

```java
package com.idm.test.scenarios.engine;

import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.spi.StepType;
import com.idm.test.scenarios.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A real run of two corpus scenarios writes their database and LDAP evidence to both outputs. */
@DisplayName("Evidence in a runner's results and report")
class EvidenceReportTest extends BaseIntegrationTest {

    @Test
    @DisplayName("The JSON results and the HTML report hold SQL and LDAP evidence")
    void testEvidenceWritten(@TempDir Path dir) throws Throwable {
        Map<String, StepType> registry = ScenarioSupport.registry(
                ldapDirectories, databaseConnector, roleServiceConnector,
                resourceServiceConnector, idmRestConnector, restProperties, restAuthenticator);
        ScenarioEngine engine = new ScenarioEngine(registry, new FixtureFactory(testDataGenerator), testExecutor,
                executorProperties.getDefaultTimeout(), executorProperties.getDefaultPollInterval(),
                new ExpressionEvaluator());
        Path results = dir.resolve("scenario-results.json");

        List<DynamicTest> tests = ScenarioSupport.buildTests("scenarios",
                List.of("provisioning-user-to-database.yaml", "provisioning-user-to-ldap.yaml"),
                new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS), engine, results,
                scenarios -> RunInfo.minimal("Embedded"));
        for (DynamicTest test : tests) {
            test.getExecutable().execute();
        }

        String json = Files.readString(results);
        assertThat(json).contains("\"kind\" : \"sql\"").contains("\"kind\" : \"ldap\"");
        String html = Files.readString(dir.resolve("scenario-report.html"));
        assertThat(html).contains("<details class=\"evidence\"").contains("SQL · ");
    }
}
```

- [ ] **Step 2: Run it**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=EvidenceReportTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. (If either corpus file no longer uses `db.*` / `ldap.*`, pick two that do with `grep -l` and say so in the report.)

- [ ] **Step 3: Docs**

`docs/reference/writing-scenarios.md`, §9 Results — after the "**The HTML report.**" bullets (after the **Printing** bullet), add:

```markdown
**Evidence.** Under each step the report shows what the step sent and received, in an
**Evidence** section that is open on a failed step. The JSON results hold the same evidence under
the step's `evidence` key (`"kind": "http"`, `"sql"` or `"ldap"`).

| Step | Evidence |
|---|---|
| `http.request`, `http.verify`, `rest.request`, `rest.verify` | method, URL, request headers and body, status, response headers and body, time; on a connection error, the request and the error |
| `db.verify` | the SQL, its parameters and the rows returned |
| `db.execute` | the SQL, its parameters and the number of rows changed |
| `ldap.create`, `ldap.update` | the DN and the attributes written |
| `ldap.delete` | the DN |
| `ldap.verify` | with `expect`, every attribute read; with `exists`, whether the entry was there |

- **Last attempt only:** a step with `wait:` keeps the evidence of its last attempt.
- **Nothing sent, nothing shown:** a malformed parameter or an unknown target, database or
  directory records no evidence, nor does a cleanup entry.
- **Masking**, before anything is recorded, using the same names as the parameter masking above:
  headers with such names (`Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, …); values under
  such keys in JSON bodies; such elements and attributes in XML or SOAP bodies; such `name=value`
  pairs in form bodies; such LDAP attributes (`userPassword`, `unicodePwd`); database columns
  with such names; and SQL parameters bound to such a column in `INSERT … (cols) VALUES (?, …)`
  or `col = ?` / `<>` / `!=` / `LIKE ?`. Any other SQL parameter is shown as given, so don't put a
  secret in `params` of a statement written another way, and a secret inside free text appears as
  written.
- **Size limits:** bodies are cut at 16 384 characters, at most 20 rows are kept (the report
  says how many more there were), each database value is cut at 1 024 characters, and an LDAP
  attribute shows at most 20 values.
```

`docs/architecture/capabilities.md`, the `HTML report` row's last column: replace `Per-step evidence (HTTP/SOAP, LDAP, SQL) is ITF-37` with `Per-step evidence for HTTP/REST, SQL and LDAP steps, masked and size-limited, in the report and the JSON (ITF-37); SOAP envelopes are ITF-40`.

`README.md`, after the sentence about the HTML report (~line 181), add: ` Each step's evidence — the HTTP exchange, the SQL and its rows, or the LDAP entry — is shown under it, with secrets masked.` (on the same line, continuing the paragraph).

- [ ] **Step 4: Full suite and corpus**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures/errors/skips. Then

```bash
grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
grep -c '"status" : "FAILED"' idm-test-scenarios/target/multi-directory-results.json
```

Expected: a total, then `0` and `0`. If Docker is available: `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false` passes 21/21.

- [ ] **Step 5: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/EvidenceReportTest.java docs/reference/writing-scenarios.md docs/architecture/capabilities.md README.md
git commit -m "docs: describe step evidence, and check a real run writes it

ITF-37

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
