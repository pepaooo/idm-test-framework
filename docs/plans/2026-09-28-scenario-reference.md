# Scenario Authoring Reference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A complete authoring reference for the YAML tier at `docs/reference/writing-scenarios.md`, which a consultant can write scenarios from, kept honest by a test that checks it against the step registry.

**Architecture:**
- **Task 1** writes the page from the fact sheet below. Every fact must be verified against the step source before it is written.
- **Task 2** adds `ScenarioReferenceTest`, which parses the page's per-step parameter tables and its worked example, and asserts they match the registry and pass the validator. It also fixes any drift the test finds.
- **Task 3** relinks the existing docs and moves the authoring guide's `http.request` and authentication material into the reference.

**Tech Stack:** Markdown; Java 21, JUnit 5, AssertJ for the test.

**Spec:** `docs/specs/2026-09-28-scenario-reference-design.md` (commit dc84ee9). **Ticket:** ITF-10.

## Global Constraints

- **The page** is `docs/reference/writing-scenarios.md`. It is written for a consultant who doesn't write Java: no Java types, class names or engine internals in the prose.
- **Page sections, in this order:**
  1. File anatomy
  2. Values
  3. Fixtures
  4. Step reference
  5. Waiting
  6. Cleanup
  7. Targets and configuration
  8. What validation rejects at load
  9. Results
  10. A worked example

  Their headings are exactly `## 1. File anatomy` … `## 10. A worked example`. The step reference's groups are `###`-level headings under `## 4. Step reference`.
- **Each step section's heading** is exactly `` #### `<step name>` ``, one per registered step type (13). `set` has no step section; it is documented in "Values".

  > **Spec adjustment:** the spec said `###` for step headings. Because steps are grouped under `###` headings inside `## 4.`, step sections use `####`, and the drift test parses `` ^#### `…` ``. Any heading level at or above that of a step section ends its table search.
- **Each step section's first table** has exactly the header `| Parameter | Required | Meaning |`.
  - The parameter name is in backticks.
  - Required is exactly `yes` or `no`.
  - The rows are the step's required parameters plus its other accepted parameters, **excluding `as` and `wait`**.
  - A rule the lists can't express goes in Meaning, with both parameters marked `no`. Example: `ldap.verify`'s `expect`/`exists`.
- **The worked example** is the first ```` ```yaml ```` fenced block after `## 10. A worked example`, and it must be a complete scenario that passes the loader and validator.
- **The drift test** is `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioReferenceTest.java`, with no Spring context. It reads `../docs/reference/writing-scenarios.md`, relative to the module directory.
- **No production code, configuration, corpus or existing test changes.**
- **Commits** end with a blank line, `ITF-10`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. Green means 0 failures, errors and skips.

---

### Task 1: Write the reference page

**Files:**
- Create: `docs/reference/writing-scenarios.md`

**Interfaces:**
- Produces: the page, in the format from the Global Constraints, which Task 2's test parses.

**Sources to read, and verify every fact against, before writing:**
- the step classes in `idm-test-engine/src/main/java/com/idm/test/engine/steps/`: `requiredParams()`, `acceptedParams()`, `retryable()`, and every `StepOutcome.success(...)` / `failure(...)` / `finalFailure(...)`
- `ScenarioValidator.java` and `ScenarioLoader.java` in `idm-test-engine/src/main/java/com/idm/test/engine/`, for the load-time rejections and their exact messages. Run a scenario with a deliberate mistake to capture real sample messages rather than paraphrasing.
- `FixtureFactory.java`, and `idm-test-utils`' `TestDataGenerator`, for the `random-user` keys
- `WaitSpec.java`, and `application.yml`'s `idm.test.executor` values per profile
- `docs/guides/scenario-authoring.md`: its §2, §3, §5, §6 and the `http.request` and authentication sections are the existing prose to adapt
- `docs/reference/configuration.md`, and `idm-test-scenarios/src/test/resources/scenarios-real/README.md`, to link to
- `idm-test-scenarios/src/test/resources/scenarios-vocabulary-fixture/full-vocabulary.yaml`, for one working example of every step

**Fact sheet.** Confirm each item in the source; where the source disagrees, the source wins, so note it in your report.

| Step | Kind | Required | Optional | `as:` captures | Notes |
|---|---|---|---|---|---|
| `ldap.create` | action | `dn` | `attributes` | the DN string | DN is relative to `idm.test.ldap.base-dn`; the directory schema decides which attributes are allowed |
| `ldap.update` | action | `dn`, `attributes` | — | the DN string | replaces the listed attributes' values; nothing is restored automatically |
| `ldap.delete` | action | `dn` | — | the DN string | fails if the entry doesn't exist (`found no entry at …`) |
| `ldap.verify` | assertion | `dn` | `expect`, `exists` (exactly one of them) | with `expect`: a map of the entry's attributes (a follow-up read); with `exists`: nothing | `expect` compares only the listed attributes; a null or empty `expect`, or both/neither of `expect`/`exists`, is a final failure |
| `db.execute` | action | `database`, `statement` | `params` (list, bound to `?`) | the affected row count | 0 rows affected is success, so a cleanup `DELETE` is safe |
| `db.verify` | assertion | `database`, `query`, `expect` | `params` | the rows (a list of maps) | `expect` keys: `rowCount` and/or `row` (a column → value map; needs exactly one row); an unknown database or malformed `expect` is final; SQL errors retry |
| `roles.grant` | action | `user`, `roles` (list) | `reason` | the list of assignment DNs; pass it to `roles.revoke` | SOAP: full DNs as IdM knows them; needs `idm.test.soap.url` |
| `roles.revoke` | action | `assignments` | — | (check the source) | takes the assignment DNs captured from `roles.grant`, not user + role |
| `resources.grant` | action | `user`, `resource` | `reason` | the request id (empty text if none) | SOAP: full DNs; needs `idm.test.soap.resource-url`; a successful grant is a request, and provisioning happens later |
| `resources.revoke` | action | `user`, `resource` | `reason` | (check the source) | |
| `rest.request` | action | `target`, `method`, `path` | `body`, `bodyFrom` (never both), `headers` | `{status, headers, body}`, with the body parsed as JSON | any HTTP status is a successful step; the body is sent as JSON |
| `rest.verify` | assertion | `target`, `path`, `expect` | `method` (default `GET`), `headers` | `{status, headers, body}` | `expect` keys: `status` and/or `body` (a subset match, nested); an API's 401/403 retries; unknown target, malformed `expect` or misconfigured/rejected credentials are final |
| `http.request` | action | `target`, `method`, `path` | `headers`, `body` (string only) | `{status, headers, body}`, with lower-case header names and the body as text | never fails on status; defaults to `Content-Type: text/plain; charset=UTF-8` with a body, and `Accept: */*` |

Every step also accepts `as:` (all of them) and `wait:` (assertions only); document those once, in "Values" and "Waiting".

- [ ] **Step 1: Draft the page**

Write all 10 sections, following the spec §3. For each of the 13 step sections, in this order:
1. the heading, `` #### `ldap.create` ``
2. one purpose line, including the kind in bold: **Action.** or **Assertion (accepts `wait:`).**
3. the parameter table, in the exact format
4. a **Captures:** line
5. a **Fails when:** paragraph (for assertions, say which failures end a `wait:` at once and which are retried)
6. one short example, in a ```` ```yaml ```` block

Group the step sections under these headings inside `## 4. Step reference`:
- `### LDAP`
- `### Databases`
- `### SOAP: roles and resources`
- `### REST (JSON)`
- `### Raw HTTP (any body, REST or SOAP)`

In "Targets and configuration", **link** rather than repeat: to `configuration.md` (the REST authentication table, databases, `#tls-certificates-ldaps-https`) and to the real-environment README (what to configure per repository). Keep one short paragraph per topic. Include the DN note: LDAP steps take DNs relative to `idm.test.ldap.base-dn`, while SOAP steps take full DNs as IdM knows them.

**The worked example**, verbatim as the first YAML block after `## 10. A worked example`, followed by a short walkthrough of each step:

```yaml
name: Provision an employee end to end
description: Create the directory entry, grant a role, and check the SAP account, the IdM REST view and the directory
fixtures:
  user:
    generate: random-user
    values:
      department: Finance
steps:
  - set:
      displayName: "js:{ user.firstName + ' ' + user.lastName.toUpperCase() }"

  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      attributes:
        cn: ${user.userId}
        sn: ${user.lastName}
        givenName: ${user.firstName}
        mail: ${user.email}
        description: ${displayName}
      as: createdDn

  - roles.grant:
      user: "cn=${user.userId},ou=users,o=data"
      roles:
        - "cn=SAP_USER,cn=Level10,cn=RoleDefs,cn=RoleConfig,cn=AppConfig,cn=User Application Driver,cn=driverset1,o=system"
      reason: Automated provisioning test
      as: assignments

  - db.verify:
      database: sap
      query: "SELECT status FROM sap_users WHERE username = ?"
      params: ["${user.userId}"]
      expect:
        row: { status: ACTIVE }
      wait: { timeout: 5m, poll: 10s }

  - rest.verify:
      target: idm
      path: "/users/${user.userId}"
      expect:
        status: 200
        body: { department: Finance }
      wait: { timeout: 2m, poll: 5s }

  - ldap.verify:
      dn: "${createdDn}"
      expect:
        mail: ${user.email}
        description: ${displayName}
      wait: true

cleanup:
  - roles.revoke:
      assignments: "${assignments}"
  - ldap.delete:
      dn: "${createdDn}"
```

The walkthrough must point out that `ldap.create` uses a DN relative to the base DN while `roles.grant` uses the full DN IdM knows (`…,o=data` here is illustrative), and that the role DN is an example the reader replaces with their own.

- [ ] **Step 2: Self-check the page**

- Confirm every step's table rows against its `requiredParams()` and `acceptedParams()`, excluding `as`/`wait`.
- Confirm there is no Java type name in the prose: grep the page for `StepType`, `OperationResult`, `StepOutcome`, `Map<`, `List<` and `.java`. Links to source files are allowed only in a single "Where the rules live" footnote at the end, if at all.
- Validate the worked example against the real validator. Copy it into `idm-test-scenarios/src/test/resources/scenarios/zz-reference-check.yaml`, run `./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioValidationTest -Dsurefire.failIfNoSpecifiedTests=false`, or better, load and validate it in a scratch test. Then **delete that file**. The corpus must stay at 20 files. Task 2 automates this check.

- [ ] **Step 3: Commit**

```bash
git add docs/reference/writing-scenarios.md
git commit -m "docs: add the YAML scenario authoring reference

ITF-10
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: The drift test

**Files:**
- Create: `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioReferenceTest.java`
- Modify (only if the test finds drift): `docs/reference/writing-scenarios.md`

**Interfaces:**
- Consumes:
  - the page from Task 1
  - `YamlScenarioTest.registry(EdirectoryConnector, MultiDatabaseConnector, RoleServiceConnector, ResourceServiceConnector, IdmRestConnector, RestProperties, RestAuthenticator)`, a package-private static method returning `Map<String, StepType>`
  - `ScenarioLoader.load(InputStream, String)`
  - `new ScenarioValidator(Map<String, StepType>, Set<String>).validate(Scenario, String)`
  - `FixtureFactory.SUPPORTED_GENERATORS`

- [ ] **Step 1: Write the test**

```java
package com.idm.test.scenarios.engine;

import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.spi.StepType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Keeps docs/reference/writing-scenarios.md honest: every registered step has exactly one
 * section, each section's parameter table matches what the step declares, and the worked
 * example passes the real loader and validator. Adding a step or parameter without documenting
 * it fails here, naming what to document. Captures and prose are not checked.
 */
@DisplayName("Scenario authoring reference")
class ScenarioReferenceTest {

    static final Path PAGE = Path.of("..", "docs", "reference", "writing-scenarios.md");
    /** Documented once for every step, in "Values" and "Waiting", not per step. */
    static final Set<String> COMMON_PARAMETERS = Set.of("as", "wait");

    private static final Pattern STEP_HEADING = Pattern.compile("^#### `([a-z]+\\.[a-z]+)`\\s*$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|\\s*`([A-Za-z]+)`\\s*\\|\\s*(yes|no)\\s*\\|.*\\|\\s*$");
    private static final String TABLE_HEADER = "| Parameter | Required | Meaning |";

    private static List<String> lines;
    private static Map<String, StepType> registry;

    @BeforeAll
    static void load() throws IOException {
        lines = Files.readAllLines(PAGE, StandardCharsets.UTF_8);
        registry = YamlScenarioTest.registry(null, null, null, null, null,
                new RestProperties(), RestAuthenticator.withoutOAuth());
    }

    /** step name → (parameter → required?), from each step section's first table. */
    static Map<String, Map<String, Boolean>> documentedSteps(List<String> lines) {
        Map<String, Map<String, Boolean>> steps = new TreeMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher heading = STEP_HEADING.matcher(lines.get(i));
            if (!heading.matches()) {
                continue;
            }
            Map<String, Boolean> parameters = new LinkedHashMap<>();
            boolean inTable = false;
            for (int j = i + 1; j < lines.size() && !lines.get(j).startsWith("#"); j++) {
                String line = lines.get(j).trim();
                if (!inTable) {
                    inTable = line.equals(TABLE_HEADER);
                    continue;
                }
                if (line.startsWith("|---")) {
                    continue;
                }
                Matcher row = TABLE_ROW.matcher(line);
                if (!row.matches()) {
                    break;
                }
                parameters.put(row.group(1), row.group(2).equals("yes"));
            }
            steps.put(heading.group(1), parameters);
        }
        return steps;
    }

    @Test
    @DisplayName("Every registered step has a section, and every section is a registered step")
    void testCoverage() {
        Set<String> documented = documentedSteps(lines).keySet();
        Set<String> registered = new TreeSet<>(registry.keySet());

        Set<String> undocumented = new TreeSet<>(registered);
        undocumented.removeAll(documented);
        Set<String> unregistered = new TreeSet<>(documented);
        unregistered.removeAll(registered);

        assertThat(undocumented).as("registered steps with no #### section in " + PAGE).isEmpty();
        assertThat(unregistered).as("documented steps that are not registered").isEmpty();
    }

    @Test
    @DisplayName("Each step's parameter table matches its required and accepted parameters")
    void testParameters() {
        documentedSteps(lines).forEach((name, documented) -> {
            StepType step = registry.get(name);
            if (step == null) {
                return; // reported by testCoverage
            }
            Set<String> required = new TreeSet<>(step.requiredParams());
            Set<String> all = new TreeSet<>(step.acceptedParams());
            all.addAll(required);
            all.removeAll(COMMON_PARAMETERS);

            Set<String> documentedRequired = new TreeSet<>();
            documented.forEach((parameter, isRequired) -> {
                if (isRequired) {
                    documentedRequired.add(parameter);
                }
            });

            assertThat(documented.keySet()).as(name + ": documented parameters vs declared").containsExactlyInAnyOrderElementsOf(all);
            assertThat(documentedRequired).as(name + ": parameters marked 'yes' vs required").isEqualTo(required);
        });
    }

    @Test
    @DisplayName("The worked example is a complete scenario that passes the loader and validator")
    void testWorkedExample() {
        String yaml = workedExample(lines);
        ScenarioValidator validator = new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS);

        assertThatCode(() -> {
            Scenario scenario = ScenarioLoader.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "worked-example.yaml");
            validator.validate(scenario, "worked-example.yaml");
        }).doesNotThrowAnyException();
    }

    /** The first ```yaml block after the "## 10. A worked example" heading. */
    static String workedExample(List<String> lines) {
        int start = lines.indexOf("## 10. A worked example");
        assertThat(start).as("heading '## 10. A worked example' in " + PAGE).isNotNegative();
        StringBuilder yaml = new StringBuilder();
        boolean inBlock = false;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!inBlock) {
                inBlock = line.trim().equals("```yaml");
                continue;
            }
            if (line.trim().equals("```")) {
                return yaml.toString();
            }
            yaml.append(line).append('\n');
        }
        throw new AssertionError("no ```yaml block after '## 10. A worked example' in " + PAGE);
    }
}
```

Loading `YamlScenarioTest` initializes `BaseIntegrationTest`'s static fields, which start an embedded LDAP and WireMock (the ITF-17 side effect). That's acceptable here, and ITF-17 will remove it.

- [ ] **Step 2: Run it**

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=ScenarioReferenceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 3 tests pass. If a table disagrees with the code, the message names the step and the difference. Fix **the page**, never the step code, and say so in the report.

- [ ] **Step 3: Falsification**

Make each change below, re-run, confirm that the named test fails, then revert:
- Delete the `` #### `db.execute` `` heading line. `testCoverage` fails, naming `db.execute`.
- Change `ldap.create`'s `attributes` row from `no` to `yes`. `testParameters` fails.
- Add a row `` | `bogus` | no | x | `` to `rest.request`. `testParameters` fails.
- Misspell `expect` as `expct` in the worked example's `db.verify`. `testWorkedExample` fails.

- [ ] **Step 4: Commit**

```bash
git add idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioReferenceTest.java docs/reference/writing-scenarios.md
git commit -m "test: keep the authoring reference in step with the step registry

ITF-10
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Relink the existing docs

**Files:**
- Modify:
  - `docs/guides/scenario-authoring.md`
  - `README.md`
  - `docs/README.md`
  - `docs/architecture/capabilities.md`

- [ ] **Step 1: The authoring guide**

- Replace the whole of `## 4. Supported steps` (its table, the two paragraphs after it, and the `### HTTP requests with a raw body (REST and SOAP)` and `### Authentication` subsections, up to but not including `## 5. Waiting`) with:

  ```markdown
  ## 4. Steps

  The framework has 13 step types across LDAP, databases, the SOAP role and resource services,
  REST and raw HTTP. Every step's parameters, what `as:` captures, how it fails and an example
  are in the [authoring reference](../reference/writing-scenarios.md#4-step-reference), which a
  test keeps in step with the code.

  For calling a SOAP service or any API with a raw body, see
  [`http.request`](../reference/writing-scenarios.md#httprequest); for how REST targets
  authenticate, see [targets and configuration](../reference/writing-scenarios.md#7-targets-and-configuration).
  ```

  Check that the two anchors exist in the rendered page's heading slugs (GitHub style: lower-case, punctuation removed, spaces become hyphens). `#### \`http.request\`` becomes `#httprequest`. Adjust the anchors if they differ.
- Keep §1–3 and §5–8 as they are. At the top of the guide, below the first paragraph, add: `For a complete lookup of every key, step and rule, see the [authoring reference](../reference/writing-scenarios.md).`

- [ ] **Step 2: README, docs index and capability matrix**

- **`README.md`,** just below the "Step vocabulary" table: replace the paragraph that begins "Every step also accepts `as:`" with the same text plus one sentence at the end: `The [authoring reference](docs/reference/writing-scenarios.md) documents every parameter, capture and failure mode.` In "Documentation" → "Reference", add `- [Writing scenarios](docs/reference/writing-scenarios.md) — every step, parameter and rule of the YAML tier` as the first bullet.
- **`docs/README.md`:** add the same link to its reference list, in the reading path for test authors (read the file to find the right list).
- **`docs/architecture/capabilities.md`:** in the "YAML discovery and execution" row's limitation cell, append ` A complete authoring reference, checked against the step registry by a test, is in docs/reference/writing-scenarios.md (ITF-10).`

- [ ] **Step 3: Check links and the full suite**

Run this link check from the repository root. Every relative link in the changed files must resolve:

```bash
python3 - <<'EOF'
import re, os
files = ['README.md', 'docs/README.md', 'docs/guides/scenario-authoring.md',
         'docs/reference/writing-scenarios.md', 'docs/architecture/capabilities.md']
bad = []
for f in files:
    s = open(f).read(); d = os.path.dirname(f)
    for l in re.findall(r'\]\(([^)\s]+)\)', s) + re.findall(r'^\[[^\]]+\]:\s*(\S+)', s, re.M):
        if l.startswith(('http', '#', 'mailto')):
            continue
        p = os.path.normpath(os.path.join(d, l.split('#')[0]))
        if not os.path.exists(p):
            bad.append((f, l))
print(bad or 'all links resolve')
EOF
```

Expected: `all links resolve`.

Then run `./mvnw clean test`. Expected: BUILD SUCCESS, 0 failures, errors and skips; `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`; and `ls idm-test-scenarios/src/test/resources/scenarios/*.yaml | wc -l` prints `20`. Report the total test count.

- [ ] **Step 4: Commit**

```bash
git add docs/guides/scenario-authoring.md README.md docs/README.md docs/architecture/capabilities.md
git commit -m "docs: point the guide, README and index at the authoring reference

ITF-10
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
