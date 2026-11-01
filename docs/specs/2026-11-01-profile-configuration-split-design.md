# Split scenario configuration into profile files — design

**Ticket:** ITF-44. **Decided with:** jangulo, 2026-11-01.

## Intent

Make the Maven/JUnit scenario module's environment settings easier to inspect and let the documented
`IDM_*` (and connector-specific `SAP_*`, `HR_*`, `WORKDAY_*`, etc.) variables work under every profile.
An IdM engineer can keep their own lab settings private while choosing them with
`IDM_TEST_PROFILE=local`. This is a configuration layout change, not a change to scenario execution.

## Goals and boundaries

- `idm-test-scenarios/src/test/resources/application.yml` contains only shared defaults and
  `spring.profiles.active: ${IDM_TEST_PROFILE:default}`. Spring Boot loads it for every profile.
- `application-dev.yml`, `application-test.yml`, and `application-prod.yml` contain only their
  respective overrides, with no `spring.config.activate.on-profile` and no duplicate profile
  documents in `application.yml`. Profile files outrank shared values for the keys they declare.
- Environment-specific values declared in profile files use the same environment-variable names
  already used by shared defaults, with profile-specific fallback values. With variables unset,
  committed `default`, `dev`, `test`, and `prod` effective values match the current checked-in
  configuration. With variables set, the corresponding values change in every profile.
- A gitignored `application-local.yml` contains only the user's lab connection settings; any key
  it does not declare comes from shared `application.yml`. A committed
  `application-local.yml.example` documents the shape without lab hosts or secrets. The user's
  existing uncommitted lab values in the `dev` section are moved into the ignored local file;
  they are not copied into the committed `dev` file or the design document. The checked-in `dev`
  fallbacks come from `HEAD`, before those local edits.
- No new secret literal is committed. Existing shared and embedded-test fallback values remain
  where changing them would alter baseline behavior; the local file references environment
  variables for passwords and other secrets. The current uncommitted lab database password is
  replaced with an environment reference in local configuration.
- This ticket does not alter the end-user `idmtest` workspace runner's separate `config/` loading,
  connector code, scenario files, or the real-environment guards.

## Approaches

1. **Chosen: standard Spring Boot profile files with placeholders in each overridden setting.**
   Each file states only its differences from the shared file. A setting such as `dev` LDAP URL
   becomes `${IDM_LDAP_URL:ldap://dev-edirectory.company.com:389}`. This preserves the existing
   fallback and lets `IDM_LDAP_URL` work regardless of active profile. The local file uses only
   lab connection overrides.
2. **Shared placeholders only, with literal profile overrides.** This is the current behavior in
   separate files: a profile literal still hides the shared placeholder, so it fails the variable
   requirement.
3. **Additional import/overlay layer for variable overrides.** This could centralize variable
   references, but adds ordering and precedence rules to an otherwise standard Boot layout. It
   offers no useful gain for the small set of existing profile overrides.

The first approach is the only one that directly meets the desired inheritance and variable
behavior without custom loading or additional precedence rules.

## Configuration behavior

`default` loads only shared defaults. `dev`, `test`, `prod`, or `local` loads shared defaults plus
its corresponding `application-<profile>.yml`. A profile file overrides only the keys it declares.
An OS variable used inside `${NAME:fallback}` selects that value; Spring's direct relaxed-binding
variables such as `IDM_TEST_LDAP_URL` remain a higher-precedence way to set the property itself.
Document both forms without implying that the end-user workspace runner uses these test resources.

The committed `dev` file uses the pre-existing generic development values and retains its logging
overrides. The committed `test` file retains H2, LDAP, WireMock, shorter timeouts, and logging
defaults. Connection values there use the same variable placeholder convention, with the old
embedded values as fallbacks. The committed `prod` file retains its existing fallbacks, pool sizes,
and logging levels. The user's `local` file contains only the current lab's directory, database,
SOAP, and REST connection overrides; it does not inherit `dev` logging or other `dev`-only
properties. The `local` file is selected with `IDM_TEST_PROFILE=local`.

The local ignore rule targets only
`idm-test-scenarios/src/test/resources/application-local.yml` so the example stays tracked.
The example should use non-sensitive illustrative values and `${...}` for credentials. The docs
also explain external configuration outside the repository using Spring Boot's
`spring.config.additional-location` or `spring.config.import=optional:file:…`, including which
file wins when both set the same key. This is an alternative for people who do not want even an
ignored lab file in the checkout.

## Verification and documentation

- Compare Spring-resolved/bound values for `default`, `dev`, `test`, and `prod` against a recorded
  pre-split baseline with relevant environment variables absent. Cover LDAP, named directories,
  database URLs/users/drivers, SOAP, REST, execution settings, and logging where overridden.
  Keep the baseline fixture free of secret values. This check must prove profile file loading,
  rather than merely parse YAML text.
- Check at least representative `IDM_*`, database, SOAP, and REST variables under each profile,
  including `test`, and check that `local` inherits an undeclared shared property. Verify direct
  `IDM_TEST_*` property overrides still work. Keep `RealEnvironmentScenariosStructureTest` and
  the real-environment guards unchanged.
- Run `ConfigurationTest`, relevant guard/structure tests, embedded scenario tests, and
  `ContainerScenarios` when Docker is available. A real lab run is optional and must use
  `IDM_TEST_PROFILE=local` after migration. Do not make an unguarded real-system run part of CI.
- Update `docs/reference/configuration.md` with the file layout, profile selection,
  inheritance/precedence, local example, and external config. Update
  `idm-test-scenarios/src/test/resources/scenarios-real/README.md` to remove the stale warning
  about profile literals blocking `IDM_*`. Update `docs/reference/running-tests.md` to show
  `local` for a personal lab and point to the revised variable guidance. Correct nearby source
  comments that say all values come from one file.

## Builder decisions

The builder may choose the most reliable Spring Boot test harness for baseline and override
checks and may decide whether to extend `ConfigurationTest` or use a dedicated parameterized
test. Preserve the user's unrelated untracked files and do not stage the ignored local file.
