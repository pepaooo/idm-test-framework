# Running a single YAML scenario — design

**Ticket:** ITF-13 (subtask of ITF-4, relates to ITF-10).

## 1. The problem

`YamlScenarioTest` is a `@TestFactory` that runs every `.yaml` in `scenarios/`. Surefire's
`-Dtest=` filters classes and methods, not the dynamic tests a factory produces. So an author
editing one file has to run all 20, each of which creates LDAP entries and database rows. The
one failure they care about is buried among nineteen passes.

This hits hardest on the audience the declarative tier exists for. It will hit harder still on
`RealEnvironmentScenarios` (ITF-12), where every scenario writes to a real directory.

**A correction to the ticket.** It says "a typo in someone else's file blocks yours". That is not
what the code does. `buildTests` loads and validates every file before running any, but a file
that fails to load or validate becomes **its own failing dynamic test**, and the valid files
still run. A broken neighbour makes the build red beside your scenario. It does not stop your
scenario.

## 2. Usage

```bash
./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dscenario=provisioning-user-to-ldap.yaml
```

| `-Dscenario=` | Runs |
|---|---|
| *(not set)* | every file, exactly as today |
| `provisioning-user-to-ldap.yaml` | that file |
| `provisioning-user-to-ldap` | the same file (see §3) |
| `'provisioning-*'` | the three `provisioning-*` files |
| `'{provisioning-*,roles-*}'` | both groups |

The same property works on `RealEnvironmentScenarios`, and as a VM option in an IDE run
configuration. Quote any pattern that contains `*` or braces, so the shell passes it through.

**Verified:** surefire 3.5.4 passes `-Dscenario='provisioning-*'` into the forked test JVM
unchanged. This was checked with a throwaway probe test before this design was written.

## 3. Matching

- The pattern is a **glob**, using Java's `FileSystems.getDefault().getPathMatcher("glob:" + pattern)`.
  It is matched against the file name.
- A file is selected if the pattern matches its full name **or** its name without the `.yaml`
  extension. Leaving off the extension is the most likely slip, and accepting it costs one line.
- **Why glob, not substring.** Substring was the ticket's suggestion. But `provisioning` is also
  part of `deprovisioning`, so `-Dscenario=provisioning` would select 10 files, not the three
  `provisioning-*` ones. A glob says exactly what it means. An exact file name is just a glob
  with no wildcards.

## 4. Failing loudly

A filter that selects nothing and reports green is the vacuous-success failure this project has
fixed eight times. Every refusal throws `IllegalStateException` from the `@TestFactory` method,
which JUnit reports as a failure, not a skip.

1. **No file matches.** The message names the pattern and the directory, and lists every
   available file name, so a typo can be fixed from the message alone.
2. **The property is set but blank** (`-Dscenario=`). This usually means a shell variable was
   never set. Running everything would guess at intent, so it is refused.
3. **The glob is invalid**, e.g. an unclosed `[`. The `PatternSyntaxException` is wrapped in a
   message naming the pattern.

## 5. Which files are loaded and validated

**Only the selected files.** With no filter, every file is loaded and validated, so CI and any
plain `./mvnw test` keep design §8.1's guarantee in full. With a filter, the run's result
reflects exactly the scenarios that were asked for. A broken unselected file surfaces the next
time the whole directory runs.

## 6. Where the filter lives

The filter is applied in the two `@TestFactory` methods, not inside `scenarioFiles(dir)`.

`scenarioFiles` has callers that must never be filtered. `YamlScenarioValidationTest` lists its
fixture directory with it, and `RealEnvironmentGuardsTest` uses it to check that
`scenarios-real/` ships empty. Filtering inside it would make `./mvnw test -Dscenario=x`
(with no `-Dtest`) fail those two tests, for reasons that have nothing to do with them.

A JUnit Platform filter or extension was also considered and rejected. Discovery filters never
see dynamic tests, because those are created during execution.

Changes to `YamlScenarioTest`:

- `static List<String> select(List<String> files, String pattern, String scenarioDir)` is
  **pure**. A `null` pattern returns `files` unchanged. Otherwise it applies §3 or throws per §4.
  `scenarioDir` is used only in messages.
- `static List<String> selectedScenarioFiles(String scenarioDir)` lists the directory, reads
  `System.getProperty("scenario")`, and delegates to `select`. It is the only place that reads
  the property.
- `buildTests` gains an overload that takes the file list:
  `buildTests(String scenarioDir, List<String> files, ScenarioValidator, ScenarioEngine, Path)`.
  The existing four-argument signature delegates to it with `scenarioFiles(scenarioDir)`, so
  `YamlScenarioValidationTest` does not change.
- `scenarios()` passes `selectedScenarioFiles(SCENARIO_DIR)` to the new overload.

Changes to `RealEnvironmentScenarios`:

- Guard 3 (`requireScenarios`) still checks the **unfiltered** listing, so an empty directory
  keeps its own message pointing at the README. Only after that is `select` applied, so a
  pattern that matches nothing gets the filter's message instead.
- Its structural and guard tests are unaffected.

## 7. Testing

Unit tests for `select`, with no Spring context and no system-property mutation, in
`ScenarioFilterTest` in `com.idm.test.scenarios.engine`:

- `null` pattern returns the input list unchanged (the CI path)
- an exact name selects one file
- a name without `.yaml` selects the same file
- `provisioning-*` selects the `provisioning-*` files and **no** `deprovisioning-*` file
- a brace pattern selects two groups
- no match throws, and the message contains the pattern, the directory and the available names
- a blank pattern throws
- an invalid glob throws, and the message names the pattern

Each refusal is falsification-tested: with its check removed, its test must fail.

Live checks, run by hand in the plan rather than as permanent tests:

- `-Dtest=YamlScenarioTest -Dscenario=provisioning-user-to-ldap.yaml` runs exactly **one**
  dynamic test.
- `-Dscenario='provisioning-*'` runs exactly three.
- `-Dscenario=provisoning-user-to-ldap.yaml` (typo) produces `BUILD FAILURE`, with the list of
  available names in the report.
- `./mvnw clean test` with no property: the previous total plus the new unit tests, 0 failures,
  errors or skips.

## 8. Documentation

- `CLAUDE.md`, under Development Commands: one line showing `-Dscenario`.
- `idm-test-scenarios/src/test/resources/scenarios-real/README.md`: the same option, for the
  real-environment runner.
- ITF-10's authoring reference can now say how to run a single scenario. That reference is
  ITF-10's work, not this ticket's.

## 9. Out of scope

- Changing validate-all-before-running when no filter is set (design §8.1). This design keeps
  it as it is.
- Filtering by scenario `name:` or tag rather than file name.
- Filtering individual steps within a scenario.
