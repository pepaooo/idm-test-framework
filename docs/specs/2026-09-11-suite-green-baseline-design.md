# Design: Green, Trustworthy Test Baseline

**Date**: 2026-09-11
**Status**: Approved, ready for planning
**Scope**: Phase (a) — make the existing suite genuinely green. Phase (b), connecting to a real
OpenText IdM / eDirectory environment, is explicitly out of scope and is planned separately.

---

## 1. Why now

Development is resuming after a pause. `mvn clean test` currently ends in `BUILD FAILURE`.
Seven of the eight modules pass; `idm-test-scenarios` fails with 1 failure and 6 errors.

The framework cannot be pointed at a real IdM system until this baseline is trustworthy. If the
harness itself is broken, every phase (b) failure is ambiguous between "IdM misconfigured" and
"framework broken", and the diagnosis cost compounds.

## 2. Verified current state

Measured by running the build on 2026-09-11, not taken from the phase reports (which are stale).

| Module | Result |
|---|---|
| idm-test-core | 34 tests pass |
| idm-test-utils | 47 tests pass |
| idm-connector-ldap | 14 tests pass |
| idm-connector-database | 21 tests pass |
| idm-connector-rest | 12 tests pass |
| idm-connector-soap | 8 tests pass (WSDL to JAX-WS codegen works, ~62s) |
| idm-connector-idm | empty module — POM only, zero sources |
| idm-test-scenarios | FAILURE — 1 failure, 6 errors |

### Root cause of the 6 errors

Every `BaseIntegrationTest` subclass dies before `@BeforeEach`:

```
Status 400: client version 1.32 is too old. Minimum supported API version is 1.40
```

Testcontainers 1.20.4's bundled docker-java negotiates Docker API 1.32. The local Docker Engine
reports API 1.55 with a minimum of 1.40 and rejects the client. Docker itself is running and
healthy. This is dependency drift since October 2025, not a defect in project code.

Consequence: the five workflow test classes have **never executed past `@BeforeAll`** in any run
recorded in this repository. The "13/31 passing" figures in `PHASE10_TEST_RESULTS.md` predate the
current code and describe a different failure ("Failed to borrow DirContext from pool") that is no
longer reachable.

### Root cause of the 1 failure

`UserProvisioningIntegrationTest.testConnectorsAvailable:208` asserts `executorProperties` is
non-null. The class does not extend `BaseIntegrationTest` and carries no `@ActiveProfiles("test")`,
so nothing is injected. Its actual workflow body is commented out. It is a leftover template that
duplicates the real tests under `provisioning/`.

### The silent-skip trap

`BaseIntegrationTest` injects all six connectors with `@Autowired(required = false)`, and
`requireConnector()` (line 463) calls `Assumptions.assumeTrue(false)` when one is null. A missing
bean therefore **skips** the test rather than failing it. This is currently masked because tests
error out earlier at container startup. Once the container problem is fixed, the suite could report
`BUILD SUCCESS` with a large share of scenarios silently skipped.

### Two competing LDAP substrates

- `idm-connector-ldap` uses UnboundID `InMemoryDirectoryServer` (`EmbeddedLdapServerConfig`, test
  sources): 14 tests, ~1.2s, no Docker, passing today.
- `idm-test-scenarios` uses `osixia/openldap:1.5.0` via Testcontainers: that image was archived by
  its maintainer in 2021, and these tests have never passed reliably.

### Database reality

Live scenario queries use only the H2 `test-db` (63 call sites). The `sap`, `workday` and
`oracle-hr` references are 3 occurrences inside a commented-out block in the leftover template test,
plus 2 assertions in `ConfigurationTest` that check property-map keys without opening a connection.
**No PostgreSQL or Oracle instance is required for green.**

## 3. Goals

1. `mvn clean test` returns `BUILD SUCCESS`.
2. Every scenario test actually runs. Zero skips, enforced mechanically.
3. The suite does not require Docker.
4. Connection configuration is profile-driven, so phase (b) is a config change, not a code change.
5. Markdown documentation is classified so maintained and frozen documents are distinguishable.

## 4. Non-goals

Deliberately deferred to phase (b):

- OAuth2 wiring. `spring-boot-starter-oauth2-client` is a dependency and `useOAuth` /
  `oauthClientName` properties exist, but `RestClientConfiguration` builds a bare `RestClient` with
  no `OAuth2ClientHttpRequestInterceptor`. The configuration is currently decorative.
- Authentication in `IdmRestConnector`, which sends no `Authorization` header of any kind.
- Populating the empty `idm-connector-idm` module.
- A ResourceService connector. `idmprov-resource.wsdl` is compiled but unused; roles only.
- Additional test scenarios (no `synchronization/`, no per-application suites).

These stay invisible through phase (a) because WireMock and an embedded LDAP accept any
credentials. That is a known and accepted consequence of sequencing green-first.

## 5. Design

### 5.1 Shared embedded LDAP server

Promote the UnboundID setup from `idm-connector-ldap`'s test sources into `idm-test-utils` main
sources as `com.idm.test.utils.ldap.EmbeddedLdapServer`, parameterized by base DN, port and an LDIF
resource. Both `idm-connector-ldap` and `idm-test-scenarios` consume it.

Test infrastructure in main scope is appropriate here — this is a test framework, and the
alternative (publishing a test-jar from `idm-connector-ldap`) adds build complexity for no benefit.
`idm-test-utils` gains a compile dependency on `unboundid-ldapsdk`.

The component must support both a caller-specified port and an ephemeral one, because its two
consumers differ:

- **`idm-connector-ldap` keeps its existing contract unchanged**: fixed port 10389 and base DN
  `dc=springframework,dc=org`, both currently hardcoded in `EmbeddedLdapServerConfig` and in that
  module's `src/test/resources/application.yml`. This module's `application.yml` is not edited, and
  its 14 passing tests serve as a pure regression check on the extraction.
- **`idm-test-scenarios` uses an ephemeral port** and base DN `dc=test,dc=com`, supplied through
  `@DynamicPropertySource` as described in 5.2.

### 5.2 Wiring into the scenarios module

`BaseIntegrationTest` keeps its existing shape: a static server started in a static block, with
`@DynamicPropertySource` registering `idm.test.ldap.url`. This is the same mechanism the container
path used, so churn is contained to one class.

- The server binds a **random free port**, so parallel module builds cannot collide.
- Root is `dc=test,dc=com`, matching every existing test DN assumption.
- `ldap-init.ldif` is imported unchanged, preserving `ou=users`, `ou=roles`, `ou=groups`.
- **Standard schema validation stays enabled.** Violations such as an `inetOrgPerson` missing `sn`
  must still fail. This is the only eDirectory fidelity available for free, and it is the class of
  bug that would otherwise surface first in phase (b).

### 5.3 Removing Testcontainers from scenarios

Testcontainers leaves `idm-test-scenarios` entirely: dependency, annotations, and image pull. This
removes the Docker API-version blocker at its root rather than deferring it behind a version bump,
and drops the suite's Docker requirement to zero.

`docker-compose.yml` and `.github/workflows/ci.yml` remain on disk for phase (b).

### 5.4 Loud failure replaces silent skip

All six `@Autowired(required = false)` fields in `BaseIntegrationTest` become required, and
`requireConnector()` is deleted along with every call site. A missing bean then fails context
startup with a clear message. This is the mechanism that enforces the zero-skip goal; without it,
nothing does.

### 5.5 WireMock

WireMock is retained for SOAP — it is not the problem. It moves off its hardcoded port 8090 to a
dynamically allocated port, since a fixed port is a latent conflict in CI.

### 5.6 The phase (b) seam

`idm.test.ldap.*` remains the single source of connection truth. The embedded server only
**overrides the URL** under the test profile. Pointing at real eDirectory becomes a matter of
setting `IDM_LDAP_URL` / `IDM_LDAP_BASE_DN` and activating a different profile. The same shape
already holds for SOAP (`idm.test.soap.url`) and REST (`idm.test.rest.*`).

### 5.7 Dead test artifacts

Two files are removed. Neither contains a live assertion.

- `UserProvisioningIntegrationTest` — workflow body entirely commented out, duplicates the real
  `provisioning/` tests, and is the source of the one genuine failure.
- `LdapDiagnosticTest` — a debugging aid for the container problem being deleted.

## 6. Documentation structure

Three different kinds of document currently sit in one flat pile at the repository root, with no
signal about which are maintained. That is why they contradict each other: `README.md` asserts
"Phase 10 Complete" while `PHASE10_COMPLETE.md` opens with "INCOMPLETE".

```
README.md                          <- stays (root convention)
CLAUDE.md                          <- stays (root convention)
docs/
├── reference/                     <- maintained; trust these
│   ├── specification.md
│   ├── configuration.md
│   └── running-tests.md
├── specs/                         <- design docs, one per initiative
│   └── 2026-09-11-suite-green-baseline-design.md
└── history/                       <- frozen; never updated
    ├── README.md                  <- staleness banner
    ├── phase-09-summary.md
    ├── phase-09-complete.md
    ├── phase-09-tests-fixed.md
    ├── phase-09-fix-tests.md
    ├── phase-10-complete.md
    ├── phase-10-test-results.md
    ├── phase-10-fixes-applied.md
    └── phase-10-null-safety.md
```

`idm-connector-soap/README.md` stays with its module.

Three rules govern this:

1. **`docs/history/README.md` carries an explicit staleness banner** stating that these are
   point-in-time records, may be inaccurate, and are not evidence of current state. This file is
   what actually resolves the contradiction problem; the folder move alone only hides it.
2. **Historical content is not rewritten.** Editing a dated report to be "correct" destroys the
   record without adding anything. These files are moved and quarantined, not revised.
3. **Every inbound link is updated in the same commit.** `CLAUDE.md` references `SPECIFICATION.md`
   and `README.md` links several reports by name. Missing one trades clutter for broken references.

This is done as a separate commit from the test work — pure `git mv` plus link edits — so it cannot
destabilize the build and stays easy to review.

## 7. Execution plan

Steps 1 to 4 are mechanical and independently verifiable.

1. Promote `EmbeddedLdapServer` into `idm-test-utils`; refactor `idm-connector-ldap` onto it;
   confirm its 14 tests still pass. Isolated and reversible.
2. Rewire `BaseIntegrationTest` to the embedded server; strip Testcontainers from the scenarios POM
   and annotations; move WireMock to a dynamic port.
3. Flip the six injections to required; delete `requireConnector()` and its call sites.
4. Remove the two dead test artifacts named in 5.7.
5. **Run the full suite and triage.** Scope unknown until step 2 lands — see section 9.
6. Restructure documentation per section 6, in its own commit.
7. Correct the `README.md` status table once the build is genuinely green.

## 8. Verification criteria

Checkable, not subjective:

- `mvn clean test` returns `BUILD SUCCESS`.
- Aggregate surefire output reports **Skipped: 0** across all modules.
- `mvn dependency:tree` shows no `org.testcontainers` artifact in `idm-test-scenarios`.
- The full suite passes **with the Docker daemon stopped** — the real proof the dependency is gone.
- No markdown file outside `README.md`, `CLAUDE.md`, `docs/` and module directories.
- No broken relative links from `README.md` or `CLAUDE.md`.

## 9. Risks and open questions

**The one genuine unknown: what step 5 uncovers.** The five workflow classes have never run to
completion. The suite might come up nearly clean, or it might surface latent assertion bugs in the
family the October reports describe (`NoSuchElementException` on empty `Optional`s, partially
addressed in commit cd33e83). This cannot be scoped before the LDAP substrate works. Actual numbers
will be reported after the first run of step 2 rather than estimated now.

**Triage principle, agreed in advance.** When a test fails there are two possible repairs: fix the
code, or relax the test to match what the code does. The second is how a suite quietly becomes
decorative. Each failure will be classified as *connector bug* or *wrong test expectation*, and
where it is the latter, the call is surfaced for a decision before any assertion is weakened. The
entire purpose of phase (a) is a baseline that can be trusted in phase (b); silently tuning tests to
green would defeat it.

**Accepted limitation.** An in-memory LDAP server is more permissive than eDirectory even with
schema validation enabled. Some schema and referential-integrity behaviour will only be exercised in
phase (b). This is accepted deliberately: whichever substrate is chosen now is replaced by real
eDirectory later, so investing in container fidelity buys a stepping stone already slated for
disposal.
