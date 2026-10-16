# End-User Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `idmtest init | validate | run` works on a workspace folder outside the source tree — from an executable jar or a per-OS ZIP with a bundled Java 25 runtime — producing the same results JSON, HTML report and guards as the JUnit runners.

**Architecture:** The wiring the JUnit runners own today (step registry, engine construction, guards, run description, results writing, scenario listing) moves from `idm-test-scenarios`' test sources into a new main-code module `idm-test-wiring`; the JUnit runners become thin wrappers over it. A new module `idm-test-runner` holds the program: command parsing, workspace and settings, `secrets.env`, driver loading, the three commands, console output, and packaging (Spring Boot jar, jlink runtime, launchers, ZIPs).

**Tech Stack:** Java 25, Spring Boot 4.1.1 (non-web `SpringApplication`), the existing engine and connectors, JUnit 6, AssertJ, UnboundID embedded LDAP, H2, WireMock, Maven (`spring-boot-maven-plugin`, `maven-antrun-plugin`/`exec` for jlink, `maven-assembly-plugin`), GitHub Actions.

**Spec:** `docs/specs/2026-10-15-end-user-runner-design.md` (ITF-55). ADR: `docs/architecture/decisions/0007-end-user-runner-and-local-workbench.md`.

## Global Constraints

- Settings: Spring YAML with the existing `idm.test.*` keys in `<workspace>/config/application-<profile>.yml`, optional `config/application.yml`; loaded with `spring.config.additional-location=optional:file:<workspace>/config/` and `spring.profiles.active=<profile>`.
- Precedence, highest first: command-line options (`--locale` → `idm.test.report.locale`) → real environment variables → `secrets.env` → `config/application-<p>.yml` → `config/application.yml` → the runner's built-in defaults (no lab URLs or credentials).
- `secrets.env`: `KEY=VALUE` lines, `#` comments, blank lines; malformed line → `secrets.env line <n> is not KEY=VALUE` (exit 2), never the line's content; values never printed anywhere (ITF-23).
- Git warning (run continues): `secrets.env is tracked by Git; remove it with 'git rm --cached secrets.env'`.
- Workspace: `--workspace <dir>` else the current folder; must contain `scenarios/`, else `not a workspace: <dir> has no scenarios/ folder (run 'idmtest init <dir>' to create one)` (exit 2).
- Scenario names are relative paths with `/` separators (`users/create.yaml`); `.yaml` and `.yml`; sub-folders allowed; sorted by name; `--scenario` globs match the relative path with or without the extension; no match → `no scenario matches '<glob>'` (exit 2).
- Commands: `init [<dir>]`, `validate [--scenario] [--profile] [--locale] [--workspace]`, `run --profile <p> [--scenario] [--locale] [--workspace] [--verbose]`, plus `--help` and `--version`.
- Missing profile file: `no settings for profile '<p>': config/application-<p>.yml not found` (exit 2). `run` without `--profile`: `run needs --profile <name>` (exit 2).
- `validate` never connects: no LDAP, database, REST or SOAP client or pool is created (no Spring context is started).
- Run folder: `reports/<run-id>/` with `results.json`, `report.html`, `run.log`; `<run-id>` = `yyyyMMdd-HHmmss-<profile>`.
- Console: `✔ <name>  <duration>` / `✖ <name>  <duration> — <failure summary in the run's language>`; summary `<n> scenarios: <p> passed, <f> failed · report: reports/<run-id>/report.html`. Framework logging goes to `run.log` only unless `--verbose`.
- Exit codes: `0` all selected passed/validated; `1` at least one failed or did not validate; `2` the run could not start.
- Drivers: PostgreSQL, Oracle `ojdbc11`, SQL Server `mssql-jdbc` bundled (versions from the Spring Boot BOM); `<install>/lib/drivers/` via `-Dloader.path`; `<workspace>/drivers/` via `DriverLoader` (child class loader + delegating `java.sql.Driver` registered with `DriverManager`). Missing driver: `no JDBC driver for '<url>' (database '<name>'); put the driver jar in <workspace>/drivers/` (exit 2, before any scenario).
- Launcher: Java = `$IDM_TEST_JAVA_HOME/bin/java` if set, else `<install>/runtime/bin/java`; `JAVA_VERSION` from its `release` file must be ≥ 25, else `IDM_TEST_JAVA_HOME points to Java <n> (<path>); idmtest needs Java 25 or newer` (exit 2); then `<java> -Dloader.path=<install>/lib/drivers -jar <install>/lib/idm-test-runner-<version>.jar "$@"`.
- jlink modules: `java.base,java.sql,java.naming,java.xml,java.net.http,java.logging,java.management,java.desktop,java.instrument,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.zipfs`; options `--strip-debug --no-header-files --no-man-pages --compress zip-6`.
- The JUnit runners' behaviour, properties (`-Dscenario`, `IDM_TEST_PROFILE`, `IDM_TEST_LOCALE`) and results are unchanged; the full suite and corpus stay green (1032 tests before this plan; `0` / `0` FAILED).
- The engine stays Spring-free (ADR 0003). No picocli. Messages the runner prints use the ITF-46 catalogs (`runner.*` keys) in the run's language, English unchanged for existing texts.
- Out of scope: signing/notarization, installers, auto-update, publishing releases, keychain/encrypted secrets, a `check` command, ITF-44.
- Commit trailer: a blank line, `ITF-55`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven: always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. `export JAVA_HOME=~/.sdkman/candidates/java/25.0.3-zulu`.
- Full-suite check: `./mvnw clean test`, then
  ```bash
  grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
  grep -h -o 'failures="[0-9]*"\|errors="[0-9]*"\|skipped="[0-9]*"' */target/surefire-reports/TEST-*.xml | sort | uniq -c
  grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json
  grep -c '"status" : "FAILED"' idm-test-scenarios/target/multi-directory-results.json
  ```
  Expected: BUILD SUCCESS, total = 1032 + added tests, only zero failures/errors/skips, `0`, `0`. Known flakes: `EmbeddedLdapServerTest.testLdifImport`, `LdapStepsTest` closed connection, `SoapStepsTest` port clash — re-run once, report both runs.

## Review Focus

- A workspace path containing spaces (`/Users/ana/My Tests`) works for `init`, `validate`, `run`, `secrets.env` and `drivers/` — pinned in Task 5 (`testWorkspaceWithSpaces`) and Task 8 (`testRunInFolderWithSpaces`).
- `secrets.env` with Windows line endings (`\r\n`), a value containing `=` (`IDM_TEST_PW=a=b`), or a UTF-8 BOM parses to the intended values — pinned in Task 5.
- An `--scenario` glob with sub-folders (`users/*`) and a file name with dots (`roles.grant-v2.yaml`) select exactly what the user expects — pinned in Task 2.
- A run interrupted after some scenarios (one throws unexpectedly) still leaves a readable `results.json` and `report.html` covering the scenarios that ran — pinned in Task 8 (`testReportsWrittenAfterEachScenario`).
- `IDM_TEST_JAVA_HOME` pointing at a folder with no `release` file, or at a path with spaces, gives the launcher's clear message rather than a shell error — pinned in Task 10.

---

### Task 1: The `idm-test-wiring` module with registry, engine factory, needs, run info and results recorder

**Files:**
- Modify: `pom.xml` (add `<module>idm-test-wiring</module>` after `idm-connector-idm`; add a `dependencyManagement` entry for `idm-test-wiring` and, later, `idm-test-runner`)
- Create: `idm-test-wiring/pom.xml`
- Create (moved from `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/`, package `com.idm.test.wiring`, made `public`): `StepRegistry.java` (from `ScenarioSupport.registry`), `EngineFactory.java`, `ScenarioNeeds.java`, `RunInfos.java`, `RunRecorder.java` (from `ScenarioSupport.writeResults` / `reportPath`)
- Move tests: `ScenarioNeedsTest`, `RunInfosTest` → `idm-test-wiring/src/test/java/com/idm/test/wiring/`
- Modify: `ScenarioSupport.java`, `YamlScenarioTest.java`, `MultiDirectoryScenarioTest.java`, `RealEnvironmentScenarios.java`, `EvidenceReportTest.java`, `ScenarioReferenceTest.java` and any other caller found with `grep -rn 'ScenarioSupport.registry\|ScenarioNeeds\|RunInfos\|writeResults\|new ScenarioEngine' idm-test-scenarios/src/test/java` — call the moved classes
- Modify: `idm-test-scenarios/pom.xml` (depend on `idm-test-wiring`)

**Interfaces:**
- Produces:
  - `public final class StepRegistry { public static Map<String, StepType> of(LdapDirectories, MultiDatabaseConnector, RoleServiceConnector, ResourceServiceConnector, IdmRestConnector, RestProperties, RestAuthenticator); public static Map<String, StepType> forValidation(); }` — `forValidation()` builds the same 14 step types with `null` collaborators (the validator only reads `name()`, `retryable()`, `requiredParams()`, `acceptedParams()`).
  - `public final class EngineFactory { public static ScenarioValidator validator(Map<String, StepType> registry); public static ScenarioEngine engine(Map<String, StepType> registry, TestDataGenerator generator, TestExecutor executor, TestExecutorProperties properties); }`
  - `public record ScenarioNeeds(...)` with `public static ScenarioNeeds of(List<Scenario>)` — unchanged shape, now public.
  - `public final class RunInfos { public static RunInfo of(String runnerKey, Environment environment, List<Scenario> scenarios); public static Locale locale(Environment environment, String env, String systemProperty); }`
  - `public final class RunRecorder { public static void write(List<ScenarioResult> results, Path resultsJson, Path reportHtml, RunInfo run); public static Path reportPath(Path resultsPath); }` — JSON failure is fatal (rethrown), HTML failure logged as today.

- [ ] **Step 1: Create the module.** `idm-test-wiring/pom.xml` (parent `idm-test-framework`, artifact `idm-test-wiring`) with dependencies: `idm-test-core`, `idm-test-utils`, `idm-test-engine`, `idm-connector-ldap`, `idm-connector-database`, `idm-connector-rest`, `idm-connector-soap`, `spring-boot-starter`; test: `spring-boot-starter-test`, `assertj-core`. Copy the surefire/compiler plugin entries the connector modules use (none beyond the parent's). Register the module in the parent `pom.xml`.

- [ ] **Step 2: Move the classes.** For each class in the Files list: `git mv` the source (and its test) into the new module and package, change `package` to `com.idm.test.wiring`, make the class and the members used from other packages `public`, and keep every line of logic as it is. `StepRegistry.of` is `ScenarioSupport.registry`'s body; `forValidation()` calls `of(null, null, null, null, null, null, null)`. `EngineFactory` holds the `new ScenarioValidator(registry, FixtureFactory.SUPPORTED_GENERATORS)` and `new ScenarioEngine(registry, new FixtureFactory(generator), executor, properties.getDefaultTimeout(), properties.getDefaultPollInterval(), new ExpressionEvaluator())` expressions repeated in today's runners. `RunRecorder.write` is `ScenarioSupport.writeResults` with explicit paths.

- [ ] **Step 3: Repoint the callers** in `idm-test-scenarios` (Files list) to the moved classes; delete the moved methods from `ScenarioSupport`. Add the `idm-test-wiring` dependency to `idm-test-scenarios/pom.xml`.

- [ ] **Step 4: Add a test for `forValidation`** in `idm-test-wiring/src/test/java/com/idm/test/wiring/StepRegistryTest.java`:

```java
package com.idm.test.wiring;

import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.model.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("StepRegistry")
class StepRegistryTest {

    @Test
    @DisplayName("Should register the 14 step types, and validate without any connector")
    void testForValidation() {
        var registry = StepRegistry.forValidation();
        assertThat(registry).hasSize(14).containsKeys("ldap.create", "db.verify", "rest.request", "http.verify",
                "roles.grant", "resources.revoke");

        Scenario scenario = ScenarioLoader.load(new ByteArrayInputStream("""
                name: Probe
                steps:
                  - ldap.verify:
                      dn: cn=x,ou=users
                      attributes: { sn: X }
                """.getBytes(StandardCharsets.UTF_8)), "probe.yaml");
        assertThatCode(() -> EngineFactory.validator(registry).validate(scenario, "probe.yaml"))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 5: Run the module and the full suite.** `./mvnw test -pl idm-test-wiring -am`, then the full-suite check. Expected: 1032 + 1 tests, corpus `0` / `0`; every moved test passes unchanged apart from its package.

- [ ] **Step 6: Commit**

```bash
git add -A pom.xml idm-test-wiring idm-test-scenarios
git commit -m "refactor: move the runners' shared wiring into idm-test-wiring

ITF-55

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Scenario sources — classpath and directory

**Files:**
- Create: `idm-test-wiring/src/main/java/com/idm/test/wiring/ScenarioSource.java`, `ClasspathScenarioSource.java`, `DirectoryScenarioSource.java`, `ScenarioGlob.java`
- Modify: `ScenarioSupport.java` (its `scenarioFiles`, `selectedScenarioFiles`, `select`, `stripYaml`, `resource` move here); callers in `idm-test-scenarios` use `ClasspathScenarioSource`
- Move test: `ScenarioFilterTest` → `idm-test-wiring/src/test/java/com/idm/test/wiring/ScenarioGlobTest.java` (assertions unchanged)
- Test: `idm-test-wiring/src/test/java/com/idm/test/wiring/DirectoryScenarioSourceTest.java`

**Interfaces:**
- Produces:
  - `public interface ScenarioSource { List<String> names(); InputStream open(String name) throws IOException; String describe(); }` — names sorted, `/`-separated relative paths; `describe()` is the folder shown in messages.
  - `public final class ClasspathScenarioSource implements ScenarioSource { public ClasspathScenarioSource(String directory); }`
  - `public final class DirectoryScenarioSource implements ScenarioSource { public DirectoryScenarioSource(Path directory); }` — recursive, `.yaml` and `.yml`, ignores hidden files and folders.
  - `public final class ScenarioGlob { public static List<String> select(List<String> names, String pattern); }` — null/blank pattern → all names; matches the name or the name without `.yaml`/`.yml`; uses `FileSystems.getDefault().getPathMatcher("glob:" + pattern)` over `Path.of(name)`, the same rule as today's `ScenarioSupport.select`. Returns an empty list when nothing matches (callers decide the message).

- [ ] **Step 1: Write the failing tests** `DirectoryScenarioSourceTest`:

```java
package com.idm.test.wiring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DirectoryScenarioSource")
class DirectoryScenarioSourceTest {

    private static void write(Path root, String name) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "name: " + name + "\nsteps: []\n", StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Should list .yaml and .yml files in sub-folders as sorted relative paths, skipping others")
    void testNames(@TempDir Path root) throws Exception {
        write(root, "z.yaml");
        write(root, "users/create.yaml");
        write(root, "users/roles.grant-v2.yml");
        write(root, "notes.txt");
        write(root, ".hidden/x.yaml");

        assertThat(new DirectoryScenarioSource(root).names())
                .containsExactly("users/create.yaml", "users/roles.grant-v2.yml", "z.yaml");
    }

    @Test
    @DisplayName("Should open a scenario by its relative name")
    void testOpen(@TempDir Path root) throws Exception {
        write(root, "users/create.yaml");
        try (var in = new DirectoryScenarioSource(root).open("users/create.yaml")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).startsWith("name: users/create.yaml");
        }
    }

    @Test
    @DisplayName("Globs match relative paths, with or without the extension")
    void testGlobs(@TempDir Path root) throws Exception {
        write(root, "users/create.yaml");
        write(root, "users/delete.yaml");
        write(root, "roles.grant-v2.yaml");
        var names = new DirectoryScenarioSource(root).names();

        assertThat(ScenarioGlob.select(names, "users/*")).containsExactly("users/create.yaml", "users/delete.yaml");
        assertThat(ScenarioGlob.select(names, "roles.grant-v2")).containsExactly("roles.grant-v2.yaml");
        assertThat(ScenarioGlob.select(names, "users/create.yaml")).containsExactly("users/create.yaml");
        assertThat(ScenarioGlob.select(names, "nothing*")).isEmpty();
        assertThat(ScenarioGlob.select(names, null)).isEqualTo(names);
    }
}
```

- [ ] **Step 2: Run to see it fail.** `./mvnw test -pl idm-test-wiring -am -Dtest=DirectoryScenarioSourceTest -Dsurefire.failIfNoSpecifiedTests=false` → compile errors.

- [ ] **Step 3: Implement.**

```java
package com.idm.test.wiring;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Where scenario files come from: the classpath (JUnit runners) or a folder (a workspace). */
public interface ScenarioSource {

    /** Relative names with '/' separators, sorted. */
    List<String> names() throws IOException;

    InputStream open(String name) throws IOException;

    /** The location as shown in messages. */
    String describe();
}
```

```java
package com.idm.test.wiring;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Scenario files under a folder, sub-folders included; hidden files and folders are skipped. */
public final class DirectoryScenarioSource implements ScenarioSource {

    private final Path root;

    public DirectoryScenarioSource(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public List<String> names() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(rel -> !hidden(rel))
                    .map(rel -> rel.toString().replace('\\', '/'))
                    .filter(name -> name.endsWith(".yaml") || name.endsWith(".yml"))
                    .sorted()
                    .toList();
        }
    }

    private static boolean hidden(Path relative) {
        for (Path part : relative) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public InputStream open(String name) throws IOException {
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root)) {
            throw new NoSuchFileException(name);
        }
        return Files.newInputStream(file);
    }

    @Override
    public String describe() {
        return root.toString();
    }
}
```

`ClasspathScenarioSource` is today's `ScenarioSupport.scenarioFiles` / `resource` (top-level only, as today). `ScenarioGlob.select` is today's `ScenarioSupport.select` with the extension stripping extended to `.yml` (today it strips `.yaml` only; keep the `.yaml` behaviour identical). `ScenarioSupport.selectedScenarioFiles(dir)` becomes `ScenarioGlob.select(new ClasspathScenarioSource(dir).names(), System.getProperty(SCENARIO_PROPERTY))` plus its existing "matches nothing" handling.

- [ ] **Step 4: Run** `./mvnw test -pl idm-test-scenarios -am` → PASS, including `ScenarioGlobTest` (moved, unchanged assertions).

- [ ] **Step 5: Full-suite check, then commit**

```bash
git add -A idm-test-wiring idm-test-scenarios
git commit -m "feat: scenario sources for the classpath and for a workspace folder

ITF-55

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Real-environment guards in `idm-test-wiring`

**Files:**
- Create: `idm-test-wiring/src/main/java/com/idm/test/wiring/RealEnvironmentGuards.java` (the guard methods, `Refusal`, the placeholder table and `EMBEDDED_PROFILE` from `RealEnvironmentScenarios`)
- Modify: `RealEnvironmentScenarios.java` (calls `RealEnvironmentGuards`; keeps only the JUnit wiring), `ContainerScenarios.java` if it calls guard methods
- Move test: `RealEnvironmentGuardsTest` → `idm-test-wiring/src/test/java/com/idm/test/wiring/RealEnvironmentGuardsTest.java` (assertions unchanged)

**Interfaces:**
- Consumes: `ScenarioSource`, `ScenarioNeeds`, `ScenarioLoader`.
- Produces: `public final class RealEnvironmentGuards` with
  - `public static void check(Environment environment, ScenarioSource source, List<String> selected, Locale locale, RestProperties rest, DatabaseProperties databases, SoapProperties soap)` — runs, in today's order: not-embedded, scenarios present (`source.names()` non-empty), then on the selected scenarios' needs: endpoints, REST targets, databases, credentials. Throws `RealEnvironmentGuards.Refusal` (public, `IllegalStateException`, `Message detail()`).
  - every individual guard keeps its current signature (now `public static`), so the moved guard tests compile unchanged.
  - `requireScenarios` uses `source.describe()` where it used the classpath directory name; the English text for the classpath case is unchanged.

- [ ] **Step 1:** Move the guard code and its test (as Task 1 Step 2: logic unchanged, package `com.idm.test.wiring`, members `public`).
- [ ] **Step 2:** Add `check(...)` composing the guards exactly in the order `RealEnvironmentScenarios.scenarios()` calls them today; `RealEnvironmentScenarios.scenarios()` calls `RealEnvironmentGuards.check(...)` with a `ClasspathScenarioSource`.
- [ ] **Step 3:** Add a test in the moved `RealEnvironmentGuardsTest`: `check` with a `DirectoryScenarioSource` over an empty temp folder refuses with the "no scenarios" message naming that folder; with one LDAP scenario and `idm.test.ldap.url` unset, refuses with the LDAP message.
- [ ] **Step 4:** `./mvnw test -pl idm-test-scenarios -am`, then the full-suite check (`RealEnvironmentGuardsTest` counts move module; total = previous + Step 3's tests).
- [ ] **Step 5: Commit** — `refactor: real-environment guards in idm-test-wiring, usable on any scenario source` (ITF-55 trailer).

---

### Task 4: The `idm-test-runner` module — command line, help, version, exit codes

**Files:**
- Modify: `pom.xml` (add `<module>idm-test-runner</module>` last)
- Create: `idm-test-runner/pom.xml`
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/IdmTest.java` (the `main`), `CommandLine.java`, `UsageException.java`, `ExitCodes.java`
- Create: `idm-test-runner/src/main/resources/application.yml` (logging only: `logging.level.root: WARN`, `logging.level.com.idm.test: INFO`, `spring.main.banner-mode: off`, `spring.main.web-application-type: none`)
- Create: `idm-test-runner/src/main/resources/idmtest-version.properties` (`version=${project.version}`, filtered)
- Modify: both i18n catalogs (`runner.cli.*` keys, English and Spanish)
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/CommandLineTest.java`, `IdmTestTest.java`

**Interfaces:**
- Produces:
  - `public record CommandLine(String command, Path workspace, String profile, String scenario, String locale, boolean verbose, Path initTarget)` with `public static CommandLine parse(String... args)` throwing `UsageException(Message)` for unknown commands/options or a missing option value. Options: `--workspace <dir>`, `--profile <p>`, `--scenario <glob>`, `--locale <l>`, `--verbose`; `init` takes an optional positional `<dir>`; `--help`/`-h` and `--version` are commands `help` / `version`.
  - `public final class ExitCodes { public static final int OK = 0, FAILED = 1, CANNOT_START = 2; }`
  - `public final class IdmTest { public static void main(String[] args); static int run(String[] args, PrintStream out, PrintStream err); }` — `run` returns the exit code; `main` calls `System.exit(run(args, System.out, System.err))`. In this task: `help` prints usage, `version` prints `idmtest <version>`, any other command prints `not implemented yet` and returns 2 (later tasks replace it).

- [ ] **Step 1: Module.** `idm-test-runner/pom.xml`: dependencies `idm-test-wiring`, `spring-boot-starter`; test: `spring-boot-starter-test`, `assertj-core`. `spring-boot-maven-plugin` with `<mainClass>com.idm.test.runner.IdmTest</mainClass>` and `<layout>ZIP</layout>` (PropertiesLauncher, so `-Dloader.path` works — Task 9). Resource filtering for `idmtest-version.properties` only.

- [ ] **Step 2: Failing tests.** `CommandLineTest`:

```java
package com.idm.test.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommandLine")
class CommandLineTest {

    @Test
    @DisplayName("Should parse run with every option")
    void testRun() {
        CommandLine line = CommandLine.parse("run", "--profile", "lab", "--scenario", "users/*",
                "--locale", "es", "--workspace", "/w s", "--verbose");
        assertThat(line.command()).isEqualTo("run");
        assertThat(line.profile()).isEqualTo("lab");
        assertThat(line.scenario()).isEqualTo("users/*");
        assertThat(line.locale()).isEqualTo("es");
        assertThat(line.workspace()).isEqualTo(Path.of("/w s"));
        assertThat(line.verbose()).isTrue();
    }

    @Test
    @DisplayName("Should default the workspace to the current folder and accept init's target")
    void testDefaults() {
        assertThat(CommandLine.parse("validate").workspace()).isEqualTo(Path.of("").toAbsolutePath());
        assertThat(CommandLine.parse("init", "my-tests").initTarget()).isEqualTo(Path.of("my-tests"));
        assertThat(CommandLine.parse("init").initTarget()).isNull();
        assertThat(CommandLine.parse("--help").command()).isEqualTo("help");
        assertThat(CommandLine.parse().command()).isEqualTo("help");
        assertThat(CommandLine.parse("--version").command()).isEqualTo("version");
    }

    @Test
    @DisplayName("Should reject unknown commands, unknown options and missing values")
    void testErrors() {
        assertThatThrownBy(() -> CommandLine.parse("deploy")).isInstanceOf(UsageException.class)
                .hasMessage("unknown command 'deploy' (expected init, validate or run)");
        assertThatThrownBy(() -> CommandLine.parse("run", "--colour")).isInstanceOf(UsageException.class)
                .hasMessage("unknown option '--colour'");
        assertThatThrownBy(() -> CommandLine.parse("run", "--profile")).isInstanceOf(UsageException.class)
                .hasMessage("option '--profile' needs a value");
    }
}
```

`IdmTestTest`: `run(new String[]{"--version"}, out, err)` returns 0 and prints `idmtest ` followed by the version; `--help` returns 0 and prints the three commands; `deploy` returns 2 and prints the usage error on `err`.

- [ ] **Step 3: Implement.** `UsageException(Message detail)` extends `RuntimeException` with `super(Messages.english(detail))` and a `detail()` accessor. Catalog keys (English exact as in the tests; Spanish translations): `runner.cli.unknownCommand=unknown command ''{0}'' (expected init, validate or run)`, `runner.cli.unknownOption=unknown option ''{0}''`, `runner.cli.missingValue=option ''{0}'' needs a value`, `runner.cli.usage=` (multi-line usage text with `\n` escapes listing the three commands and options), `runner.cli.notImplemented=not implemented yet`. The usage and errors print in the language of `--locale` when given and valid, else English.

- [ ] **Step 4:** `./mvnw test -pl idm-test-runner -am` → PASS. Full-suite check.
- [ ] **Step 5: Commit** — `feat: idmtest command line, help and version` (ITF-55 trailer).

---

### Task 5: Workspace, settings and `secrets.env`

**Files:**
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/Workspace.java`, `SecretsEnv.java`, `RunnerSettings.java`, `GitTracking.java`
- Modify: catalogs (`runner.workspace.*`, `runner.secrets.*`, `runner.profile.*` keys)
- Test: `WorkspaceTest.java`, `SecretsEnvTest.java`, `RunnerSettingsTest.java`, `GitTrackingTest.java`

**Interfaces:**
- Produces:
  - `public record Workspace(Path root)` with `public static Workspace open(Path dir)` (throws `UsageException` with `runner.workspace.notWorkspace`), `Path scenarios()`, `Path config()`, `Path secretsFile()`, `Path drivers()`, `Path reports()`, `Path profileFile(String profile)`, `Path newRunFolder(String profile, java.time.Clock clock)` (creates `reports/<yyyyMMdd-HHmmss>-<profile>/`, adding `-2`, `-3` if it exists).
  - `public final class SecretsEnv { public static Map<String, String> read(Path file); }` — missing file → empty map; strips a UTF-8 BOM; accepts `\r\n`; ignores blank lines and lines starting with `#` (after leading spaces); splits on the first `=`; trims the key, keeps the value as written (no quote processing); a line without `=` or with an empty key → `UsageException(Message.of("runner.secrets.malformed", lineNumber))` whose English is `secrets.env line <n> is not KEY=VALUE`.
  - `public final class RunnerSettings { public static Map<String, Object> defaultProperties(Workspace ws, String profile, String locale); public static PropertySource<?> secrets(Map<String, String> values); }` — `defaultProperties` returns `spring.config.additional-location=optional:file:<config>/` (trailing slash, absolute), `spring.profiles.active=<profile>` when given, `idm.test.report.locale=<locale>` when given. Command-line precedence is achieved by adding the locale as a `SpringApplication` command-line property (`--idm.test.report.locale=<l>`), and `secrets` is inserted with `environment.getPropertySources().addAfter(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, …)` by an `ApplicationContextInitializer` the runner registers (Task 8 wires it).
  - `public final class GitTracking { public static boolean isTracked(Path workspace, Path file); }` — runs `git -C <workspace> ls-files --error-unmatch <relative file>`; returns false when `git` is not on the PATH, the folder is not a repository, or the file is not tracked; never throws; 5-second timeout.
  - Profile check: `public static void requireProfile(Workspace ws, String profile)` on `Workspace` → `runner.profile.missing` (`no settings for profile ''{0}'': config/application-{0}.yml not found`).

- [ ] **Step 1: Failing tests.** `SecretsEnvTest`:

```java
package com.idm.test.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("secrets.env")
class SecretsEnvTest {

    @Test
    @DisplayName("Should read KEY=VALUE lines, skipping comments and blanks, with CRLF, a BOM and '=' in values")
    void testRead(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("secrets.env");
        Files.writeString(file, "﻿# lab secrets\r\nIDM_TEST_LDAP_PASSWORD=s3cr=t\r\n\r\n  # indented comment\r\n  IDM_TEST_DB_PW =x y\r\n",
                StandardCharsets.UTF_8);

        assertThat(SecretsEnv.read(file))
                .containsEntry("IDM_TEST_LDAP_PASSWORD", "s3cr=t")
                .containsEntry("IDM_TEST_DB_PW", "x y")
                .hasSize(2);
    }

    @Test
    @DisplayName("A missing file is empty")
    void testMissing(@TempDir Path dir) {
        assertThat(SecretsEnv.read(dir.resolve("secrets.env"))).isEmpty();
    }

    @Test
    @DisplayName("A malformed line is reported by number, never by content")
    void testMalformed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("secrets.env");
        Files.writeString(file, "A=1\nTOPSECRETVALUE\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SecretsEnv.read(file))
                .isInstanceOf(UsageException.class)
                .hasMessage("secrets.env line 2 is not KEY=VALUE")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("TOPSECRET"));
    }
}
```

`WorkspaceTest`: `open` on a folder without `scenarios/` throws with the exact `not a workspace: <dir> has no scenarios/ folder (run 'idmtest init <dir>' to create one)`; `testWorkspaceWithSpaces` — `open` and `newRunFolder` in `@TempDir`/`"My Tests"` work; `newRunFolder` with a fixed `Clock` at `2026-10-15T14:30:05` local gives `20261015-143005-lab`, and a second call in the same second gives `20261015-143005-lab-2`; `requireProfile` missing → exact message.

`RunnerSettingsTest`: start a minimal non-web `SpringApplication` over an empty `@Configuration`, with `RunnerSettings.defaultProperties(ws, "lab", null)` as default properties and the secrets initializer, over a temp workspace where:
- `config/application.yml`: `idm.test.a: base`, `idm.test.b: base`
- `config/application-lab.yml`: `idm.test.b: profile`, `idm.test.c: ${IDM_TEST_C:profile}`, `idm.test.d: ${IDM_TEST_D:profile}`
- `secrets.env`: `IDM_TEST_C=secret` and `IDM_TEST_D=secret`
- the environment layer: in the test, replace the property source named `StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME` with a `MapPropertySource` of the same name holding `IDM_TEST_D=env` (an initializer registered before the secrets initializer does it).

Assert from the context's `Environment`: `idm.test.a=base`, `idm.test.b=profile`, `idm.test.c=secret`, `idm.test.d=env`. A second case runs with the command-line argument `--idm.test.report.locale=es` and a profile file setting `idm.test.report.locale: en`, and asserts `es`.

`GitTrackingTest`: in a temp folder run `git init`, commit `secrets.env` → `isTracked` true; untracked → false; a non-repository folder → false. Skip the class with `Assumptions.assumeTrue(gitAvailable())` when `git` is not on the PATH.

- [ ] **Step 2:** Run → fail (compile). **Step 3:** Implement as specified. **Step 4:** `./mvnw test -pl idm-test-runner -am` → PASS; full-suite check.
- [ ] **Step 5: Commit** — `feat: workspace, settings precedence and secrets.env for the runner` (ITF-55 trailer).

---

### Task 6: `idmtest init`

**Files:**
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/InitCommand.java`
- Create templates: `idm-test-runner/src/main/resources/com/idm/test/runner/init/` — `example.yaml`, `application-lab.yml`, `secrets.env.example`, `gitignore` (written as `.gitignore`), `README.md`, `drivers-README.md` (written as `drivers/README.md`)
- Modify: `IdmTest` (dispatch `init`), catalogs (`runner.init.*`)
- Test: `InitCommandTest.java`

**Interfaces:**
- Consumes: `CommandLine`, `ExitCodes`.
- Produces: `public final class InitCommand { public static int run(Path target, PrintStream out); }` — creates `target` (default: current folder) and writes the files; never overwrites; prints `created <relative path>` / `skipped <relative path> (already exists)` per file, then `workspace ready: <target>`; returns 0.

- [ ] **Step 1: Templates.**
  - `example.yaml`: a commented scenario using one fixture (`generate: random-user`), `ldap.create` of `cn=${user.userId},ou=users,o=data` with `objectClass`, `cn`, `sn`, `givenName`; `ldap.verify` of `sn` with `wait: { timeout: 30s, poll: 2s }`; `cleanup:` with `ldap.delete`. Each block commented in plain English, pointing at `docs/reference/writing-scenarios.md`.
  - `application-lab.yml`: every common key commented out with its placeholder — `idm.test.ldap.url/base-dn/username/password: ${IDM_TEST_LDAP_PASSWORD}`, `idm.test.databases.<name>.jdbc-url/username/password`, `idm.test.rest.<target>.base-url/auth...`, `idm.test.soap.url/resource-url/username/password`, `idm.test.report.locale` — mirroring the keys documented in `docs/reference/configuration.md` (copy names exactly from there).
  - `secrets.env.example`: `# Copy to secrets.env and fill in. Never commit secrets.env.` then `IDM_TEST_LDAP_PASSWORD=`, `IDM_TEST_SOAP_PASSWORD=` and one database/REST example.
  - `gitignore`: `secrets.env` and `reports/`.
  - `README.md`: the folders, the three commands, where the report goes, the docs link.
  - `drivers-README.md`: `Put extra JDBC driver jars (MySQL, DB2, …) in this folder. PostgreSQL, Oracle and SQL Server are built in.`

- [ ] **Step 2: Failing test** `InitCommandTest`: init into an empty temp folder (with a space in its name) creates exactly `scenarios/example.yaml`, `config/application-lab.yml`, `secrets.env.example`, `drivers/README.md`, `.gitignore`, `README.md`; `.gitignore` contains the lines `secrets.env` and `reports/`; the created `scenarios/example.yaml` passes `idmtest validate` logic (`EngineFactory.validator(StepRegistry.forValidation())` on the loaded file); running init a second time after editing `README.md` keeps the edit, prints `skipped README.md (already exists)` and returns 0.

- [ ] **Step 3–4:** Implement; run `./mvnw test -pl idm-test-runner -am`; full-suite check.
- [ ] **Step 5: Commit** — `feat: idmtest init writes a starter workspace` (ITF-55 trailer).

---

### Task 7: `idmtest validate`

**Files:**
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/ValidateCommand.java`, `ConsoleProgress.java`
- Modify: `IdmTest` (dispatch `validate`), catalogs (`runner.validate.*`, `runner.console.*`)
- Test: `ValidateCommandTest.java`

**Interfaces:**
- Consumes: `Workspace`, `DirectoryScenarioSource`, `ScenarioGlob`, `StepRegistry.forValidation()`, `EngineFactory.validator`, `ReportLocale`, `Messages`.
- Produces:
  - `public final class ValidateCommand { public static int run(CommandLine line, PrintStream out, PrintStream err); }` — no Spring context. Resolves the locale (`ReportLocale.resolve(line.locale(), System.getenv("IDM_TEST_LOCALE"), System.getProperty("idm.test.locale"))`; unsupported → its message on `err`, exit 2), opens the workspace, checks `--profile` if given, selects scenarios (no match → `runner.scenario.noMatch`, exit 2), loads and validates each, prints one line each and a summary; exit 0 or 1.
  - `public final class ConsoleProgress { public ConsoleProgress(PrintStream out, Locale locale); public void passed(String name, long millis); public void failed(String name, long millis, String message); public void validated(String name); public void invalid(String name, String message); public void summary(int total, int passed, int failed, String reportPath /* null for validate */); }` — durations via `HtmlReportWriter.duration(millis, locale)`; summary keys `runner.console.summary` (`{0} scenarios: {1} passed, {2} failed`) and `runner.console.report` (` · report: {0}`); validate summary `runner.console.validated` (`{0} scenarios: {1} valid, {2} invalid`).

- [ ] **Step 1: Failing tests** `ValidateCommandTest` (temp workspace built in the test): one valid and one invalid scenario (unknown step type) → exit 1; output contains `✔ good.yaml` and `✖ bad.yaml — ` followed by the validator's message, and `2 scenarios: 1 valid, 1 invalid`; `--locale es` gives the Spanish summary and validator message; `--scenario good` → exit 0 with one line; `--scenario none*` → exit 2 and `no scenario matches 'none*'`; `--profile lab` without `config/application-lab.yml` → exit 2 with the profile message; `testValidateNeverConnects` — a `config/application-lab.yml` pointing LDAP at `ldap://192.0.2.1:389` (TEST-NET, unroutable) and a database at `jdbc:postgresql://192.0.2.1/x` with `--profile lab` validates in under 5 seconds with exit 0.
- [ ] **Step 2–4:** fail → implement → `./mvnw test -pl idm-test-runner -am` → PASS; full-suite check.
- [ ] **Step 5: Commit** — `feat: idmtest validate checks scenarios without connecting` (ITF-55 trailer).

---

### Task 8: `idmtest run`

**Files:**
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/RunCommand.java`, `RunnerApplication.java` (`@SpringBootApplication(scanBasePackages = "com.idm.test", exclude = {LdapAutoConfiguration.class, DataSourceAutoConfiguration.class})`, excluding `com.idm.test.IdmTestFrameworkApplication` and `com.idm.test.runner` from scanning), `SecretsInitializer.java` (the `ApplicationContextInitializer` from Task 5), `RunLog.java` (programmatic Logback `FileAppender` to `run.log`; console appender detached unless `--verbose`)
- Modify: `IdmTest` (dispatch `run`), catalogs (`runner.run.*`, `runner.git.tracked`)
- Test: `RunCommandTest.java` (embedded LDAP via `idm-test-utils`' `EmbeddedLdapServer`, H2 in-memory, WireMock on a random port)

**Interfaces:**
- Consumes: Tasks 1–7 (`Workspace`, `SecretsEnv`, `RunnerSettings`, `GitTracking`, `DirectoryScenarioSource`, `ScenarioGlob`, `RealEnvironmentGuards.check`, `StepRegistry.of`, `EngineFactory`, `RunInfos.of`, `RunRecorder.write`, `ConsoleProgress`).
- Produces: `public final class RunCommand { public static int run(CommandLine line, PrintStream out, PrintStream err, java.time.Clock clock); }`

Flow (spec §5): require `--profile` (`runner.run.needsProfile`, exit 2) → locale → workspace → `requireProfile` → read `secrets.env` → Git warning (`runner.git.tracked`) → create the run folder and start `RunLog` → start Spring (`new SpringApplicationBuilder(RunnerApplication.class).web(NONE).properties(RunnerSettings.defaultProperties(...)).initializers(new SecretsInitializer(secrets)).run("--idm.test.report.locale=" + locale)` when `--locale` given) → select scenarios (no match → exit 2) → load each (a load/validation failure becomes a failed `ScenarioResult` as `ScenarioSupport` does) → driver check (Task 9 adds it; leave a call to `DriverCheck.check(...)` out until then) → `RealEnvironmentGuards.check(...)` (refusal → its message, exit 2) → run each scenario in name order with `EngineFactory.engine(StepRegistry.of(beans…), …)`, printing progress and calling `RunRecorder.write(results, folder/"results.json", folder/"report.html", RunInfos.of("runner.name.workspace", environment, scenarios))` after each → summary → close the context → exit 0/1. Any unexpected exception during start-up: one line on `err` (stack trace only with `--verbose`), exit 2. Add `runner.name.workspace` = `Workspace` / `Espacio de trabajo` to the catalogs.

- [ ] **Step 1: Failing tests** `RunCommandTest` — build a temp workspace whose `config/application-lab.yml` points `idm.test.ldap.*` at an `EmbeddedLdapServer` started in `@BeforeAll` (base DN and the LDIF the corpus uses: `idm-test-scenarios/src/test/resources/ldap-init.ldif` copied into the runner's test resources), a database `test-db` at `jdbc:h2:mem:runner;DB_CLOSE_DELAY=-1` with the corpus's `schema.sql` copied likewise, and REST target `idm` at the WireMock base URL; passwords referenced as `${IDM_TEST_LDAP_PASSWORD}` and supplied through `secrets.env`.
  - `testPassingRun` — a copy of `idm-test-scenarios/src/test/resources/scenarios/provisioning-user-to-ldap.yaml` → exit 0; `reports/<run-id>/results.json`, `report.html`, `run.log` exist; console ends with `1 scenarios: 1 passed, 0 failed · report: reports/` …; `run.log` contains no value from `secrets.env`.
  - `testFailingRun` — plus a scenario verifying a wrong `sn` with a short wait → exit 1; console has `✖`; `results.json` has both scenarios.
  - `testReportsWrittenAfterEachScenario` — a scenario that fails at load (bad step type) and one that passes: both appear in `results.json` and the report.
  - `testSpanishRun` — `--locale es`: the console summary and the failure line are Spanish; `report.html` has `lang="es"`.
  - `testGuardRefusal` — profile file without `idm.test.ldap.url` and an LDAP scenario → exit 2 with the guard message on `err`, no scenario run.
  - `testMissingProfile`, `testRunNeedsProfile`, `testNoMatchingScenario` → exit 2 with the exact messages.
  - `testGitTrackedWarning` — the workspace is a Git repository with `secrets.env` committed (skip without `git`): the warning is printed and the run continues.
  - `testRunInFolderWithSpaces` — the passing run from a workspace path containing spaces.
- [ ] **Step 2–4:** fail → implement → `./mvnw test -pl idm-test-runner -am` → PASS; full-suite check.
- [ ] **Step 5: Commit** — `feat: idmtest run executes a workspace's scenarios with guards, reports and run log` (ITF-55 trailer).

---

### Task 9: JDBC drivers — bundled, install folder and workspace folder

**Files:**
- Modify: `idm-test-runner/pom.xml` (runtime dependencies `org.postgresql:postgresql`, `com.oracle.database.jdbc:ojdbc11`, `com.microsoft.sqlserver:mssql-jdbc`, versions from the Spring Boot BOM)
- Create: `idm-test-runner/src/main/java/com/idm/test/runner/DriverLoader.java`, `DelegatingDriver.java`, `DriverCheck.java`
- Modify: `RunCommand` (load workspace drivers before starting Spring; `DriverCheck.check` before the guards), catalogs (`runner.driver.*`)
- Test: `DriverLoaderTest.java`, `DriverCheckTest.java`

**Interfaces:**
- Produces:
  - `public final class DriverLoader { public static List<Path> load(Path driversFolder); }` — every `*.jar` (sorted) into one `URLClassLoader` child of the context class loader; each `java.sql.Driver` from `ServiceLoader.load(Driver.class, loader)` registered via `DriverManager.registerDriver(new DelegatingDriver(driver))`; returns the jars loaded; a missing folder → empty list.
  - `final class DelegatingDriver implements java.sql.Driver` — forwards every method to the wrapped driver (`connect`, `acceptsURL`, `getPropertyInfo`, `getMajorVersion`, `getMinorVersion`, `jdbcCompliant`, `getParentLogger`).
  - `public final class DriverCheck { public static void check(DatabaseProperties databases, Set<String> usedDatabases, Path workspaceDrivers, Locale locale); }` — for each used database, `DriverManager.getDriver(url)` (or `Class.forName(driverClassName)` when set); failure → `UsageException(Message.of("runner.driver.missing", url, name, workspaceDrivers))` whose English is `no JDBC driver for '<url>' (database '<name>'); put the driver jar in <workspace>/drivers/`.
  - `RunCommand` logs `driver jars loaded: <list>` to `run.log` for both folders (install folder jars: entries of `System.getProperty("loader.path")` that exist).

- [ ] **Step 1: Failing tests.** `DriverLoaderTest`: copy the H2 jar (locate it via `org.h2.Driver.class.getProtectionDomain().getCodeSource()`) into a temp `drivers/`; run the check in a fresh child-first class loader that excludes `org.h2` from the parent so the only H2 is the copied one (a small `ClassLoader` subclass refusing `org.h2.*` from the parent), then `DriverLoader.load` registers it and `DriverManager.getConnection("jdbc:h2:mem:x")` succeeds through the delegating driver. `DriverCheckTest`: `jdbc:db2://h/x` with no driver → the exact message; `jdbc:postgresql://h/x` → passes (bundled).
- [ ] **Step 2–4:** fail → implement → `./mvnw test -pl idm-test-runner -am` → PASS; full-suite check.
- [ ] **Step 5: Commit** — `feat: bundled JDBC drivers plus drivers/ folders for any other database` (ITF-55 trailer).

---

### Task 10: Packaging — jlink runtime, launchers, ZIPs, CI matrix

**Files:**
- Create: `idm-test-runner/src/dist/bin/idmtest` (POSIX sh, executable), `idm-test-runner/src/dist/bin/idmtest.cmd`, `idm-test-runner/src/dist/README.md`, `idm-test-runner/src/dist/LICENSE-THIRD-PARTY` (generated by `license-maven-plugin` is out of scope — write the list by hand from `./mvnw dependency:list -pl idm-test-runner`: name, version, licence for each runtime dependency)
- Create: `idm-test-runner/src/assembly/dist.xml`
- Modify: `idm-test-runner/pom.xml` (a `dist` profile: `maven-antrun-plugin` running `jlink` from `${java.home}/bin/jlink` into `target/runtime`, then `maven-assembly-plugin` with `dist.xml` producing `target/idmtest-${project.version}-${os.detected}.zip`; `os.detected` set from `os.name`/`os.arch` by a small `build-helper`/antrun condition: `macos-arm64`, `macos-x64`, `windows-x64`, `linux-x64`)
- Create: `.github/workflows/runner-dist.yml` (matrix `macos-14`, `macos-13`, `windows-latest`, `ubuntu-latest`; Temurin 25; `./mvnw -B -Pdist -DskipTests package -pl idm-test-runner -am`; `actions/upload-artifact` of the ZIP)
- Test: `idm-test-runner/src/test/java/com/idm/test/runner/LauncherTest.java` (runs only on POSIX for `idmtest`; Windows `.cmd` is exercised in CI by a step `bin\idmtest.cmd --version`)

**Interfaces:**
- Produces: the ZIP layout of spec §6; launcher behaviour of Global Constraints.

- [ ] **Step 1: The POSIX launcher** `src/dist/bin/idmtest`:

```sh
#!/bin/sh
# idmtest launcher: uses IDM_TEST_JAVA_HOME when set, otherwise the bundled runtime.
set -e
SELF="$0"
while [ -h "$SELF" ]; do
  LINK=$(ls -ld "$SELF" | sed 's/.*-> //')
  case "$LINK" in /*) SELF="$LINK" ;; *) SELF="$(dirname "$SELF")/$LINK" ;; esac
done
HOME_DIR=$(cd "$(dirname "$SELF")/.." && pwd)

if [ -n "$IDM_TEST_JAVA_HOME" ]; then
  JAVA_DIR="$IDM_TEST_JAVA_HOME"
else
  JAVA_DIR="$HOME_DIR/runtime"
fi
RELEASE="$JAVA_DIR/release"
if [ ! -f "$RELEASE" ] || [ ! -x "$JAVA_DIR/bin/java" ]; then
  echo "IDM_TEST_JAVA_HOME points to $JAVA_DIR, which is not a Java installation; idmtest needs Java 25 or newer" >&2
  exit 2
fi
VERSION=$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$RELEASE")
if [ -z "$VERSION" ] || [ "$VERSION" -lt 25 ]; then
  echo "IDM_TEST_JAVA_HOME points to Java ${VERSION:-unknown} ($JAVA_DIR); idmtest needs Java 25 or newer" >&2
  exit 2
fi
JAR=$(ls "$HOME_DIR"/lib/idm-test-runner-*.jar | head -n 1)
exec "$JAVA_DIR/bin/java" "-Dloader.path=$HOME_DIR/lib/drivers" -jar "$JAR" "$@"
```

When `IDM_TEST_JAVA_HOME` is unset and the bundled runtime is broken, the same messages name the bundled path (keep one message family; the variable name in the text is acceptable because the fix is the same).

- [ ] **Step 2: The Windows launcher** `src/dist/bin/idmtest.cmd` with the same logic: `%~dp0..` as home; `IDM_TEST_JAVA_HOME` or `runtime`; read `JAVA_VERSION` from `release` with `findstr /b "JAVA_VERSION="`, parse the major number with `for /f "tokens=2 delims==." …` (strip quotes); messages identical; `"%JAVA_DIR%\bin\java" "-Dloader.path=%HOME_DIR%\lib\drivers" -jar "%JAR%" %*`; `exit /b` codes as in the POSIX script.

- [ ] **Step 3: Failing test** `LauncherTest` (POSIX only, `@EnabledOnOs({MAC, LINUX})`): build a fake install in a temp folder with a space in its path — copy `src/dist/bin/idmtest`, a stub `lib/idm-test-runner-0.jar` (any jar), and fake Java homes whose `bin/java` is a shell script printing its arguments and whose `release` says `JAVA_VERSION="21.0.2"` / `"25.0.3"`; assert: Java 21 → exit 2 and the exact message; Java 25 via `IDM_TEST_JAVA_HOME` → the stub prints `-Dloader.path=<home>/lib/drivers -jar <home>/lib/idm-test-runner-0.jar --version`; no `release` file → exit 2 with the "not a Java installation" message; unset variable → uses `<home>/runtime`.

- [ ] **Step 4: The `dist` profile and assembly.** jlink command (antrun `<exec>`): `${java.home}/bin/jlink --add-modules <Global Constraints list> --strip-debug --no-header-files --no-man-pages --compress zip-6 --output ${project.build.directory}/runtime`. `dist.xml`: base directory `idmtest/`; `bin/` from `src/dist/bin` (file mode `0755`), `lib/idm-test-runner-${project.version}.jar` (the repackaged jar), empty `lib/drivers/`, `runtime/` from `target/runtime`, `README.md` and `LICENSE-THIRD-PARTY` from `src/dist`.

- [ ] **Step 5: Smoke test the ZIP locally.** `./mvnw -Pdist -DskipTests package -pl idm-test-runner -am`; unzip into a temp folder with a space in its name; run `bin/idmtest --version` (prints the version), `bin/idmtest init "<tmp>/ws"` then `bin/idmtest validate --workspace "<tmp>/ws"` (exit 0). Record the commands and output in the report. This proves the jlink module list is complete; if a `NoClassDefFoundError` for a JDK module appears, add that module to the list and to the Global Constraints line in this plan's report.

- [ ] **Step 6: CI workflow** `.github/workflows/runner-dist.yml` as in Files; after building, each job unzips its ZIP and runs `idmtest --version` (`idmtest.cmd --version` on Windows) as a smoke test.

- [ ] **Step 7:** Full-suite check (the `dist` profile is not part of it).
- [ ] **Step 8: Commit** — `build: per-OS idmtest ZIPs with a jlink runtime, launchers and a CI matrix` (ITF-55 trailer).

---

### Task 11: Documentation

**Files:**
- Create: `docs/guides/runner.md`
- Modify: `README.md` ("Getting started without Maven" section near the top, linking the guide), `docs/reference/configuration.md` (a "Workspace configuration" section: the `config/` files, `secrets.env`, precedence list from the Global Constraints, `drivers/`), `docs/architecture/capabilities.md` (a "Runner" row), `docs/architecture/decisions/0007-end-user-runner-and-local-workbench.md` (Status: Part 1 implemented, with the merge date to be filled at merge time — write "Part 1 implemented (ITF-55)"), `docs/reference/running-tests.md` (one paragraph pointing at the runner for non-developers)

- [ ] **Step 1: The guide** `docs/guides/runner.md`, sections: *Install* (download the ZIP for your OS, unzip, put `idmtest/bin` on `PATH`; macOS one-time `xattr -dr com.apple.quarantine idmtest`; using your own Java with `IDM_TEST_JAVA_HOME`; the plain jar with `java -jar`), *Create a workspace* (`idmtest init my-tests`, the folders), *Connect to your lab* (`config/application-<profile>.yml`, `${…}` placeholders, `secrets.env`, precedence, never commit `secrets.env`), *Validate and run* (both commands with options, exit codes, `--locale es`), *Results* (`reports/<run-id>/` files), *Other databases* (`drivers/`), *Troubleshooting* (each exit-2 message from the Global Constraints with its fix). Every command shown as a fenced `bash` block.
- [ ] **Step 2:** The other documents as listed; keep each edit short and in the document's style.
- [ ] **Step 3: Commit** — `docs: runner guide, workspace configuration, capabilities and ADR 0007 status` (ITF-55 trailer).
