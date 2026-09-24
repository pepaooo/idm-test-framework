# Guarding the REST targets a real-environment run uses — design

**Ticket:** ITF-15. **Amends:** `docs/specs/2026-09-22-real-environment-runner-design.md` §5.

## 1. The gap

`RealEnvironmentScenarios` refuses to start, before any scenario runs, when its LDAP or SOAP role
URL is blank or still an `application.yml` placeholder (guard 2). It does not check the REST
base URLs.

That matters because real scenarios will mostly drive IdM with `rest.request` and then verify
what IdM provisioned downstream (`scenarios-real/README.md`). Today, a REST target left at its
placeholder fails mid-scenario with a DNS or connection error, possibly after earlier steps have
already changed the real environment. It fails loudly, not with a false green, so this is about
getting a clear message before anything runs, not about safety.

**The ticket's framing was too narrow.** REST targets are a named map
(`RestProperties.getRest()`), not one URL. `application.yml` configures four targets, each with
its own placeholder `base-url`:

| Target | Placeholder `base-url` |
|---|---|
| `idm` | `https://idm.company.com:8543/IDMProv` |
| `sap` | `https://sap.company.com/api/v1` |
| `workday` | `https://workday.company.com/api/v1` |
| `servicenow` | `https://instance.service-now.com/api` |

A scenario picks one target per step with `target:`. Guarding only `idm` would cover one of the
four.

## 2. Decision: guard only the targets the run uses

Guard 2 is unconditional, and that is right for SOAP because the corpus uses the role service.
But **no scenario in the 20-file corpus has a `rest.*` step** (checked: `grep` finds none). An
unconditional REST guard would therefore:

- force `ContainerScenarios` to publish a fake REST address just to get past the guard
- force a team with only eDirectory and databases to configure REST addresses it never calls

So the guard derives its demands from the scenarios it is about to run. **A run with no REST
steps gets no REST check.**

## 3. Guard 4

It runs in `RealEnvironmentScenarios.scenarios()`, after guard 3 and after the `-Dscenario`
filter, and before `buildTests`. So it checks exactly the scenarios this run will execute, and
nothing runs until it passes.

**Collecting the targets.** The selected files are loaded with `ScenarioLoader`. For every step
whose type starts with `rest.` (today `rest.request` and `rest.verify`, which both require
`target`), the `target` parameter is collected. Two exceptions:

- **A file that fails to load is skipped here.** `buildTests` already turns it into its own
  failing dynamic test with the real error, and reporting it twice would only add noise. This
  mirrors how `buildTests` isolates bad files.
- **A target containing `${`** is skipped, because its value is only known at run time.
  `RestTargets.resolveBaseUrl` still refuses an unknown or blank target when the step runs.

**Refusals.** Each used target is checked in sorted order, so the message is deterministic, and
the check throws `IllegalStateException` naming the fix:

1. **The target is not configured.** The message names the target and lists the configured
   ones, for example
   `Scenarios use REST target 'sapp', which is not configured. Configured targets: [idm, sap, servicenow, workday]`.
2. **Its `base-url` is blank or missing.** The message says
   `idm.test.rest.<target>.base-url is not set. Set IDM_TEST_REST_<TARGET>_BASEURL or activate a profile that defines it.`
3. **Its `base-url` still equals that target's `application.yml` placeholder.** The message has
   the same shape as guard 2's: `... is still application.yml's placeholder (<url>), so no real
   target was named. Set IDM_TEST_REST_<TARGET>_BASEURL ...`.

`<TARGET>` is the target name upper-cased with `-` removed. That is Spring's relaxed-binding
environment-variable form, which overrides every profile, and it is the same advice guard 2
gives.

**The `prod` profile.** `prod` hardcodes `idm`, `sap` and `workday`'s REST base URLs as exactly
their placeholder values, just as it does for `soap.url`. So a `prod` run whose scenarios use
one of those targets is refused until that target's `IDM_TEST_REST_<TARGET>_BASEURL` is set.
`prod`'s `servicenow` base-url is `https://company.service-now.com/api`, not its placeholder
(`https://instance.service-now.com/api`), so a `servicenow` target is not refused. That is
consistent with guard 2 and is documented, not changed.

## 4. Code shape

All in `RealEnvironmentScenarios`, package-private and static where possible, so the logic is
unit-testable without Spring:

- `static final Map<String, String> REST_URL_PLACEHOLDERS`: the four entries from §1.
- `static Set<String> restTargets(List<Scenario> scenarios)`: pure. Returns the sorted set of
  literal `target` values of `rest.*` steps, with `${` targets skipped.
- `static void requireRestTargets(Set<String> targets, RestProperties properties)`: the refusals
  from §3. An empty set passes.
- A private helper that loads the selected files from the classpath, skipping files that fail
  to load, and feeds `restTargets`.
- `scenarios()` gains one call, after guard 3 and before `buildTests`. `RestProperties` is
  already autowired there.

`ContainerScenarios`, `YamlScenarioTest`, `BaseIntegrationTest`, the corpus YAML and the engine
are unchanged.

## 5. Testing

In `RealEnvironmentGuardsTest`, with no Spring context. Scenarios are built with
`ScenarioLoader.load` from inline YAML strings, and `RestProperties` is populated directly.

- **Collection:**
  - both `rest.request` and `rest.verify` targets are collected
  - non-REST steps are ignored
  - a `${...}` target is skipped
  - duplicates collapse
- **Refusals:**
  - an unknown target, with the message listing the configured ones
  - a blank `base-url`
  - a missing `base-url`
  - each of the four placeholders, checked as one test that loops over `REST_URL_PLACEHOLDERS`
    and asserts per target
- **Positive controls:**
  - a real URL passes
  - an empty target set passes, even with every placeholder configured, which proves no demand
    without REST
- **Placeholder pinning:** the existing `testPlaceholdersMatchApplicationYml` also asserts each
  `${<VAR>:<placeholder>}` pair for the four REST targets (`IDM_REST_URL`, `SAP_REST_URL`,
  `WORKDAY_REST_URL`, `SERVICENOW_REST_URL`).
- **Falsification:** every refusal and the empty-set pass are falsification-tested. With the
  check removed, or the pass inverted, the test must fail.

**Live:**
- `ContainerScenarios` still passes 20/20, which confirms no REST demand when nothing uses REST.
- The full default suite still passes, with the new tests added.

## 6. Documentation

- `docs/specs/2026-09-22-real-environment-runner-design.md` §5 gains guard 4.
- `idm-test-scenarios/src/test/resources/scenarios-real/README.md` gains a line in the refusal
  list, plus the `prod` REST note.

## 7. Out of scope

- Checking whether a REST target is reachable.
- OAuth client settings, and REST settings other than `base-url`.
- Load-time validation of `target` names in `ScenarioValidator`. That would be a change to the
  engine, and it is ITF-10 / engine territory.
