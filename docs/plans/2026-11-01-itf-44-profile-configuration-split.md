# ITF-44 Profile Configuration Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the scenario module's Spring configuration into profile files while retaining current fallbacks and making the documented environment variables work in every profile.

**Architecture:** Spring Boot loads `application.yml` and the active `application-<profile>.yml`; each profile file contains only overrides. Every overridden connection value keeps its old fallback inside the same `${ENV_NAME:fallback}` placeholder convention used by the shared file. The private local profile is a gitignored overlay on shared defaults.

**Tech Stack:** Java 25, Spring Boot 4.1.1 config data and configuration properties, JUnit 5, AssertJ, Maven, YAML.

**Spec:** `docs/specs/2026-11-01-profile-configuration-split-design.md`

## Global Constraints

- Preserve the checked-in effective values of `default`, `dev`, `test`, and `prod` when relevant variables are absent; use `HEAD` from before implementation as the baseline, not the user's modified worktree `dev` section.
- Preserve `RealEnvironmentScenarios`' no-`@ActiveProfiles` and no-property-override structure and all existing guards.
- Keep the user's current lab connection values private in the ignored local file. Never stage that file or unrelated untracked files. Replace its literal database password with an environment reference.
- Do not change the end-user `idmtest` workspace runner or frontend workbench. Their `config/application-<profile>.yml` files are separate from the scenario module's test resources.
- No new dependencies or connector changes. Do not run `RealEnvironmentScenarios` against a lab without an explicit local-profile setup.

## Review Focus

- `IDM_TEST_PROFILE=local` with no local file: shared defaults still load, and the real-environment guard rejects untouched placeholders; Task 1 checks it.
- `test` profile with no variables: H2/embedded values remain exact despite adding placeholders; Task 1 checks it.
- Both `IDM_LDAP_URL` and direct `IDM_TEST_LDAP_URL` set: direct Spring property takes precedence; Task 1 checks it.
- A local profile declaring only connection keys: shared execution settings remain and `dev` TRACE logging does not leak in; Task 1 checks it.
- A profile file existing beside a retained `---` section: duplicate sources could change precedence; Task 1 checks the base file has one document and no profile activation blocks.

---

## File map

- `idm-test-scenarios/src/test/resources/application.yml`: shared defaults and active-profile selector only.
- `idm-test-scenarios/src/test/resources/application-{dev,test,prod}.yml`: each profile's own overrides and `${NAME:fallback}` connection values.
- `idm-test-scenarios/src/test/java/com/idm/test/scenarios/config/ConfigurationTest.java`: existing test-profile binding smoke test; remove its explicit YAML `@TestPropertySource` if it obscures config-data loading.
- `idm-test-scenarios/src/test/java/com/idm/test/scenarios/config/ProfileConfigurationTest.java`: new real Spring config-data assertions for profile fallbacks, inheritance, overrides, and local isolation. Its fixture data contains no private values.
- `idm-test-scenarios/src/test/resources/application-local.yml.example`: safe template. `.gitignore`: only the matching real local file is ignored.
- `idm-test-scenarios/src/test/resources/application-local.yml`: private lab overlay, created before rewriting the user's modified source and never committed.
- `docs/reference/configuration.md`, `docs/reference/running-tests.md`, and `idm-test-scenarios/src/test/resources/scenarios-real/README.md`: Maven/JUnit profile layout and variable guidance, kept distinct from workspace-runner settings.
- Nearby Javadocs that describe one YAML file: correct only where the file layout makes them stale.

### Task 1: Split profiles, preserve the private lab, and prove parity

**Files:** Modify `.gitignore`, `idm-test-scenarios/src/test/resources/application.yml`, `idm-test-scenarios/src/test/java/com/idm/test/scenarios/config/ConfigurationTest.java`; create `application-dev.yml`, `application-test.yml`, `application-prod.yml`, `application-local.yml.example`, private ignored `application-local.yml`, and `ProfileConfigurationTest.java` in the paths above.

**Interfaces:** Consumes Spring Boot's standard `application-<profile>.yml` discovery. Produces no Java API; Task 2 documents the resulting file layout and selection behavior.

- [ ] **Step 1: Preserve the user's lab edits before changing the source.** Record the current diff of `application.yml`; add the exact-path `.gitignore` rule; create the ignored `application-local.yml` with the user's current named directories/default directory, `db-postgres`, SOAP, and REST connection overrides. Use `${...}` references for credentials, including `POST_DB_PASSWORD`, and retain the original modified `application.yml` until the migrated file is inspected. Keep the local file out of all commits and test fixtures.
- [ ] **Step 2: Record the baseline and write failing tests.** From the committed pre-split YAML, record exact resolved values for representative shared and profile-specific keys in `ProfileConfigurationTest`: LDAP URL/base DN/user DN; `sap`, `oracle-hr`, `workday` JDBC URL/username/driver; SOAP URL; `idm`, `sap`, `workday`, `servicenow` REST base URL; executor timeout/poll; `logging.level.com.idm.test`. Cover all four profiles. Do not store password values in the test fixture. Assert that `application.yml` is a single YAML document without `spring.config.activate.on-profile` and that the three profile files exist. Use a Spring Boot config-data context, not a standalone YAML parser, for resolved-value checks.
- [ ] **Step 3: Prove the tests fail before the split.** Run `./mvnw test -pl idm-test-scenarios -am -Dtest=ProfileConfigurationTest,ConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`; expect profile-file/layout assertions to fail before making changes.
- [ ] **Step 4: Split the YAML.** Move only the three committed profile documents out of `application.yml`. Drop each activation block. For every environment-specific value a profile overrides, use the shared file's variable name and the profile's old value as fallback: `IDM_LDAP_*`, `SAP_DB_*`, `HR_DB_*`, `WORKDAY_DB_*`, `IDM_SOAP_URL`, and the relevant `*_REST_URL`. For `test`, keep embedded H2/LDAP/WireMock fallbacks and use the same variable convention for its connection URL, DN, database URL/user, and SOAP URL overrides. Keep non-connection overrides such as drivers, pool sizes, executor timing and log levels unchanged. Restore the committed generic `dev` values from pre-implementation `HEAD`; do not move the user's lab hosts into `dev`. Add a safe `.example` with illustrative hosts and credential references only.
- [ ] **Step 5: Add override and local-inheritance assertions.** In `ProfileConfigurationTest`, prove representative environment placeholders resolve under each of `dev`, `test`, and `prod`, including LDAP, database, SOAP and REST. Use distinct test values, and prove a direct `IDM_TEST_LDAP_URL` property wins over `IDM_LDAP_URL`. With an isolated temporary local-profile fixture, assert connection overrides plus inherited executor values and no `dev` TRACE logging. With that fixture absent, assert shared placeholders still load and the real-environment guard refuses them. Check the ignore pattern ignores the real local file but not `.example`. Keep tests isolated from real services, private local data, and ambient environment variables.
- [ ] **Step 6: Run the focused tests and inspect privacy.** Run the Step 3 command; expect both classes to pass. Also run `./mvnw test -pl idm-test-scenarios -am -Dtest=RealEnvironmentPlaceholdersTest,RealEnvironmentScenariosStructureTest -Dsurefire.failIfNoSpecifiedTests=false`; expect pass. Check `git check-ignore idm-test-scenarios/src/test/resources/application-local.yml` succeeds and `git check-ignore idm-test-scenarios/src/test/resources/application-local.yml.example` does not. Inspect `git diff` and `git status --short` to ensure no private lab data is staged.
- [ ] **Step 7: Commit only Task 1 reviewable files.** `git add` the named configuration, ignore, example, and test files, excluding `application-local.yml`; then `git commit -m "refactor: split scenario profile configuration"`.

### Task 2: Update guidance and verify scenario runs

**Files:** Modify `docs/reference/configuration.md`, `docs/reference/running-tests.md`, `idm-test-scenarios/src/test/resources/scenarios-real/README.md`, and stale Javadocs in `BaseIntegrationTest.java`, `RealEnvironmentScenarios.java`, and `ConfigurationTest.java` as needed.

**Interfaces:** Consumes the completed profile file names and `local` selection from Task 1. Produces user guidance only.

- [ ] **Step 1: Replace the old file-layout and precedence guidance.** Explain shared-first then profile-file precedence, `IDM_TEST_PROFILE=local`, the ignored file and `.example`, and that the same `${IDM_*:profile fallback}` names work in each profile. Remove the real-lab README warning that `dev`/`prod` block `IDM_*`; retain the direct `IDM_TEST_*` override guidance as an additional option. Show external config using `--spring.config.additional-location` or `spring.config.import=optional:file:…` and describe ordering accurately. State that the frontend workbench uses separate workspace `config/` files and does not automatically show the repository's local profile.
- [ ] **Step 2: Correct stale source comments.** Update references that say all scenario values come from the one YAML file, without altering runner structure or guard logic.
- [ ] **Step 3: Run relevant embedded tests.** `./mvnw test -pl idm-test-scenarios -am -Dtest=ConfigurationTest,ProfileConfigurationTest,RealEnvironmentPlaceholdersTest,RealEnvironmentScenariosStructureTest,YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false`; expect pass. Run the full module/default suite if the focused run exposes shared context behavior.
- [ ] **Step 4: Run optional container verification.** If Docker is available, run `./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false`; expect pass. If unavailable, report it as unrun, not passed. Do not invoke `RealEnvironmentScenarios` automatically.
- [ ] **Step 5: Commit the documentation and comment edits only.** `git add` the named docs and source-comment files, then `git commit -m "docs: explain scenario profile files and local labs"`.

## Final review

- [ ] Compare each spec section against the two task commits and inspect the final diff for private lab values, duplicate profile documents, and accidental changes to other worktree files.
- [ ] Report focused, embedded, and optional container test results with any environmental limitations.
