# Design: Declarative Scenario Engine

**Date**: 2026-09-13
**Status**: Approved, ready for planning
**Scope**: A YAML authoring layer over the existing connectors, so IdM consultants can write
test scenarios without writing Java. The future frontend and the standalone CLI are explicitly
out of scope; this design only commits to not blocking them.

---

## 1. Why now

The framework today is a **library**: connectors, a `TestExecutor`, and test scenarios written as
Java classes. The specification simultaneously describes it as "similar to OpenText Identity
Validator", which is a **tool** — something where a test case is data you configure.

Those two goals were never reconciled, and the code followed the library reading. Connection
details are configurable in `application.yml`, but the test case itself is Java source: 49 SQL
statements are embedded across the scenario classes, alongside hardcoded role names, departments
and DNs. Adding a scenario means writing a class, compiling, and running Maven.

This design resolves that contradiction by adding a second authoring tier rather than replacing
the first.

## 2. Evidence this is tractable

The 20 existing scenario tests use exactly **nine distinct operations** between them:

| Operation | Call sites |
|---|---|
| `databaseConnector.executeUpdate` | 31 |
| `databaseConnector.executeQuery` | 19 |
| `edirectoryConnector.create` | 13 |
| `edirectoryConnector.read` | 10 |
| `testExecutor.waitUntil` | 9 |
| `edirectoryConnector.update` | 6 |
| `roleServiceConnector.assignRoles` | 5 |
| `edirectoryConnector.delete` | 2 |
| `roleServiceConnector.removeRole` | 0 |

(Re-measured directly against the five workflow test files for this correction. Six of the nine
counts are unchanged. Two are lower for the same reason: the original figures included a call
inside `BaseIntegrationTest`'s shared cleanup helper, not inside any of the 20 scenario tests
themselves (`databaseConnector.executeUpdate`: 32 → 31, `edirectoryConnector.delete`: 3 → 2). The
ninth, `roleServiceConnector.removeRole`, drops from 1 to 0: the "1" was a source comment in
`BaseIntegrationTest` — `// Note: Actual cleanup would use roleServiceConnector.removeRole()` —
not an actual call site anywhere in the corpus.)

Nine operations cover the entire corpus. This is a small, closed vocabulary, not a general-purpose
programming problem.

Two gaps the evidence does **not** show, which the design must still cover:

- **No scenario uses REST.** `IdmRestConnector` is not even injected into `BaseIntegrationTest`.
  But the specification makes REST the primary path for creating users in IdM, so the vocabulary
  needs REST steps derived from the spec rather than from current usage.
- **No resource operations exist.** Only `RoleServiceConnector` is implemented. See section 9.

## 3. Goals

1. A consultant who does not write Java can author a scenario as a YAML file.
2. The declarative tier and the existing Java tier coexist; neither replaces the other.
3. Scenario failures produce diagnostics precise enough to act on without reading engine source.
4. Execution is independent of packaging, so a CLI can be added later without rework.
5. Results are emitted as structured data, so reporting surfaces are adapters rather than rewrites.

## 4. Non-goals

Deliberately excluded from this increment:

- The standalone CLI. Designed for, not built.
- The HTML report renderer.
- Any frontend.
- Parallel scenario execution.
- Step reuse between scenarios (`extends` / `include`). See section 8.4.

## 5. Decisions and rationale

### 5.1 A purpose-built engine, not Karate

Karate was the starting proposal. Two findings moved the decision.

**Karate's `retry until` is bound to HTTP.** In `StepExecutor`, the retry condition is stored on
the request builder and only fires when an HTTP method step executes; the loop body calls
`http().invoke(method)`. It does not wrap arbitrary `Java.type(...)` calls.

This matters because async propagation — "create in IdM, wait for the account to appear in
eDirectory and SAP" — *is* the IdM testing problem, and most targets are LDAP and JDBC rather than
HTTP. Under Karate those waits would call `TestExecutor.waitUntil` through Java interop anyway,
which removes most of what Karate was providing.

**Java interop has no Spring context.** `Java.type()` resolves statics. The connectors are
`@Component` beans, so a static bridge into the application context would be required.

What remained of Karate's value was syntax and reports, paid for with a dependency whose execution
model does not fit the protocols, plus an arbitrary-JavaScript surface. A purpose-built engine
makes waiting a first-class concept instead of an awkward wrapper.

The rejected third option was YAML-as-source generating Karate feature files for execution. It
buys Karate's runner and reports without parsing Karate, but adds a generator to maintain and puts
generated Gherkin between a consultant and their stack trace, in service of a frontend nobody has
requested. The path from this design to that one remains open.

**Accepted cost:** we are building a test runner, and runners accumulate unglamorous work —
failure messages, reporting, IDE integration. Sections 6.4 and 8.2 address this directly rather
than leaving it to goodwill.

### 5.2 Same repository, new module

The engine is a consumer of the connectors and will co-evolve with them tightly. A separate
repository would buy cross-repo version lockstep on every connector change for no present benefit.
Extracting a module from a monorepo later is routine; merging two drifted repositories is not, so
the separate repo is the expensive-to-reverse decision and needs evidence that does not yet exist.

### 5.3 JUnit now, CLI later

The JUnit path is nearly free and gives surefire XML, CI integration and IDE support immediately.
The CLI is deferred, and the only constraint it imposes today is that the engine must not create
Spring contexts, read configuration files, or reference JUnit (section 6.2).

### 5.4 Expressions are permitted, within a boundary

An earlier draft of this design forbade expressions entirely, on the grounds that a format
containing code cannot be represented by a UI. That reasoning was wrong in an important way: a UI
can hold an expression as a code field. Expressions degrade UI fidelity; they do not prevent a UI.

The stronger argument is the opposite one. Forbidding expressions pushes authors into Java for
trivial work — uppercasing a surname, adding 30 days to a date, assembling a DN from parts — which
defeats the purpose of the declarative tier. NetIQ Validator permits them for this reason, and the
audience for this framework is already familiar with that idiom.

**The governing rule: expressions compute values, they never perform actions.** They are pure
functions over scenario state, with no connector access, no filesystem, no network and no Java
class access. Steps remain the only things that act, which preserves the guarantee offered in
exchange for amending the specification's "no orchestration layers" principle: a scenario's step
list is its complete execution order. An expression that wants to call LDAP is a step.

### 5.5 Rhino as the expression engine

Rhino is actively maintained and its `ClassShutter` interface is a documented, tested mechanism for
exactly this boundary — a shutter returning `false` for every class name blocks `java.lang.System`
and all Java reflection, verified by Rhino's own test suite. Only a small curated helper object
(string, date and uuid helpers) is exposed into scope.

Syntax familiarity is the deciding factor over Spring Expression Language, which is already on the
classpath and would cost no new dependency, but whose syntax is alien to anyone arriving from
Validator.

**To confirm at implementation time rather than assume:** Rhino's instruction-count callback for
capping runaway expressions. The facility exists; the current API shape has not been verified.

## 6. Architecture

### 6.1 Module placement

A new module, `idm-test-engine`, depending on `idm-test-core`, `idm-test-utils` and the four
connector modules. Nothing depends on it except the things that run scenarios. It is a consumer of
connectors in exactly the way today's Java test classes are, so it inherits connector fixes and
cannot become a competing implementation.

### 6.2 The engine is packaging-agnostic

The engine parses a scenario and executes it against connectors supplied by constructor. It does
not create Spring contexts, read `application.yml`, or reference JUnit. This single constraint is
what makes the CLI a later addition rather than a later rewrite.

### 6.3 The JUnit adapter is not a new module

`idm-test-scenarios` gains a `@TestFactory` that discovers `src/test/resources/scenarios/*.yaml`
and emits one dynamic test per scenario, delegating to the engine. It runs inside a Spring test
context, so connector injection works as `BaseIntegrationTest` does today. This costs one class
and no new dependency: JUnit stays where JUnit already is, and the engine stays clean.

### 6.4 Reporting is structural, not an afterthought

**The engine emits data and renders nothing.** Every output format is an adapter over that data.

- `ScenarioResult` — name, source file, status, duration, ordered list of `StepResult`.
- `StepResult` — index, step type, parameters *after* interpolation, status, duration, error. For
  a step that waited: attempts made, total elapsed, and the last observed value.

The last field is load-bearing. "Timed out after 5m waiting for a sap_users row" is nearly useless;
"timed out after 5m, 60 attempts, last saw 0 rows" tells a consultant whether provisioning is slow
or broken.

**Built now:** one dynamic JUnit test per scenario (surefire XML, CI and IDE results come free),
plus one JSON results file per run.

**Later, at no architectural cost:** an HTML renderer and CLI console output, both reading that
same JSON. A future frontend would consume it too, which means the reporting investment and the UI
investment are the same investment — a property worth protecting by keeping the engine render-free.

### 6.5 Future CLI

`idm-test-cli`, unbuilt: a `main()` that boots a small Spring context, resolves connectors from a
config file, and calls the same engine.

## 7. The scenario model

### 7.1 File anatomy

One scenario per file, four top-level keys.

```yaml
name: SAP provisioning for a new engineer
description: Create in IdM, grant the SAP role, verify the downstream account

fixtures:
  user:
    generate: random-user
    values: { department: Engineering, jobTitle: Software Engineer }

steps:
  - rest.request:
      target: idm
      method: POST
      path: /users
      bodyFrom: "${user}"
      as: created

  - ldap.verify:
      dn: "cn=${user.userId},ou=users"
      expect:
        givenName: ${user.firstName}
        mail: ${user.email}
      wait: { timeout: 30s, poll: 2s }

  - roles.grant:
      user: "cn=${user.userId},ou=users"
      roles: ["cn=SAP_USER,ou=roles"]

  - db.verify:
      database: sap
      query: "SELECT status FROM sap_users WHERE username = ?"
      params: ["${user.userId}"]
      expect:
        rowCount: 1
        row: { status: ACTIVE }
      wait: true
```

### 7.2 Waiting is a property of assertions, not a step

An earlier draft had a separate `wait-for` step. That was wrong: it forces every check to exist
twice, once as an assertion and once as a wait condition.

Instead, any assertion accepts an optional `wait`, meaning "retry until this passes or the timeout
expires". This halves the vocabulary and matches the domain — you never wait for its own sake, you
wait for something you were going to assert anyway.

`wait: true` uses the configured defaults from `TestExecutorProperties`, so LDAP and database steps
inherit their own timeouts without anyone restating them.

### 7.3 Step vocabulary

Twelve types in two categories. Actions change state; assertions check it and may retry.

| Actions | Assertions |
|---|---|
| `ldap.create` `ldap.update` `ldap.delete` | `ldap.verify` |
| `db.execute` | `db.verify` |
| `roles.grant` `roles.revoke` | `rest.verify` |
| `resources.grant` `resources.revoke` | |
| `rest.request` | |

The verbs `grant` and `revoke` match the Identity Applications API, which is what consultants
already say when they grant a resource to a User object. The DSL speaks the vocabulary of the
system under test.

The vocabulary is a maintenance budget — every type is Java we own. Twelve comes from three
sources: ten from the operations the existing scenarios actually use plus the specification's REST
flow, and two (`resources.grant`, `resources.revoke`) from how Identity Applications is used in
practice to grant and revoke resources on User objects. Growth requires a scenario that genuinely
cannot otherwise be written.

Reserved keys never share a namespace with user data: `db.verify` separates `rowCount` from the
`row` matcher, and `rest.request` uses `bodyFrom` rather than a `from` key inside `body`. A table
with a column named `rows`, or a payload with a field named `from`, must not change how a scenario
parses.

### 7.4 Interpolation

Two tiers.

- `${path}` — a plain property path. The common case, fully representable in a future UI.
- `js:{ expression }` — the escape hatch, following Validator's marker for familiarity. Opaque to a
  UI, sandboxed per section 5.5.

```yaml
- set:
    idUser: "${created.body.id}"
    upperLast: "js:{ user.lastName.toUpperCase() }"
    reviewDate: "js:{ var d = new Date(user.startDate); d.setDate(d.getDate() + 30); d.toISOString().slice(0, 10) }"
```

Expressions are plain sandboxed ES6 with no helper object. Standard JavaScript already covers
the cases that motivated one — date arithmetic, capitalisation, zero-padding, conditionals,
JSON — verified against Rhino 1.8.0 before this was decided. A curated `dates`/`strings` API
would be surface to design, document and keep stable with nothing yet requiring it. The
conversion of the 20 existing scenarios (§8.3) is the evidence that should decide which
helpers, if any, earn their place. The accepted cost is that the native date idiom reads
clunkily for someone who is not a JavaScript programmer.

### 7.5 Where variables come from

Three sources:

- **`fixtures:`** — declared up front, generated or literal.
- **`as:` on any step** — binds that step's result. For `rest.request` the result is
  `{status, headers, body}`; for `db.verify` the rows; for `ldap.verify` the entry's attributes.
- **`set:` step** — derived values, and where `js:{ }` lives.

Scope is scenario-local and strictly ordered: a variable exists only after the step that created
it. That ordering is statically checkable, so referencing `${created.body.id}` before the step
binding `created` fails at load time rather than three minutes into a provisioning wait.

### 7.6 Cleanup

> **Superseded by ITF-19** (docs/specs/2026-09-24-explicit-cleanup-design.md): steps no longer
> register undos; teardown is the author's cleanup: block.

Every action step registers its undo when it succeeds. The engine unwinds them in reverse order
after the scenario, on success and failure alike — the behaviour `BaseIntegrationTest` implements
by hand today. An optional `cleanup:` block handles anything the engine cannot infer.

### 7.7 Validation

Scenarios are validated at load, before anything executes: unknown step type, missing required
field, unresolvable or forward-referenced `${...}`, and malformed `js:{ }`. Errors name the file
and the step. For a non-Java author this is the difference between a usable tool and a frustrating
one, and it is also the contract a future UI would validate against.

## 8. Execution, testing and migration

### 8.1 Execution semantics

All scenario files are parsed and validated before any step runs, so a typo in the twentieth file
does not surface after nineteen minutes of provisioning waits.

Within a scenario, steps run in order and the first failure stops execution, skipping the
remainder. Cleanup always runs. A cleanup failure is reported without overwriting the original
error, since the thing that broke matters more than the thing that could not be tidied.

Scenarios run **sequentially**. They share one directory and one database, so parallelism is a
correctness question rather than a speed knob. It is not permanently closed off — fixtures generate
randomised user IDs, which is most of what isolation needs — but it requires evidence that the
suite is too slow.

### 8.2 Testing the engine

Reuses existing infrastructure: `EmbeddedLdapServer`, `TestSchemaInitializer` and the WireMock SOAP
stubs. No new test infrastructure.

1. **Parser and validation** — malformed YAML, unknown step type, missing required field, a
   `${var}` referenced before the step binding it, a `js:{}` that throws. These assert on *message
   content*, not merely that an exception was raised, because diagnostic quality is an acceptance
   criterion rather than polish.
2. **Per-step execution** — each of the twelve step types driven against embedded LDAP, H2 and
   WireMock, proving the engine operates the connectors correctly.
3. **Whole-scenario** — multi-step flows through the JUnit adapter: waits that succeed, waits that
   time out, and cleanup after a mid-scenario failure.

Plus a golden-file test pinning the JSON result structure, since the HTML renderer and any future
UI become consumers of that shape and silent changes would be expensive.

### 8.3 The 20 existing Java scenarios

Convert them to YAML — primarily as the engine's acceptance corpus rather than as migration. If all
20 express cleanly in twelve step types, the vocabulary is proven sufficient by construction. Any
that resist conversion are the most valuable output: they define what the Java tier is *for*, and
become its documented examples.

**The limit of that exercise, stated plainly:** those scenarios are fictional. They simulate IdM by
writing directly to LDAP and H2, and validate no real system. Converting them produces a corpus
that exercises the engine well and still tests no IdM. They stay labelled as examples, real
scenarios get written fresh in YAML when the framework is pointed at a real environment, and the
conversions should not be polished beyond what the engine needs.

### 8.4 Step reuse is deliberately left open

Most scenarios will begin by creating a user, and without a reuse mechanism every file repeats it.
`fixtures:` covers data reuse but not step reuse.

Ship without it. The conversion in 8.3 yields 20 real data points on how badly the duplication
hurts — better than inventing an `extends:` syntax speculatively. It is the most likely first
extension.

## 9. Prerequisite: ResourceServiceConnector

`resources.grant` and `resources.revoke` have no connector beneath them. Only `RoleServiceConnector`
exists. `idmprov-resource.wsdl` is already wired into the `jaxws-maven-plugin` and generating
classes, so the WSDL work is done and the connector is not.

This is Java-tier work that must land before the resource step types. It is a candidate for its own
smaller plan ahead of the engine.

## 10. Specification amendment

This design contradicts a stated principle in `docs/reference/specification.md`:

> ❌ Orchestration layers that hide test logic (tests must be explicit)

A declarative engine is an orchestration layer. That principle was written when Java tests were the
only surface, and it is the unresolved library-versus-tool contradiction in another form.

The amendment, agreed before this spec was written:

- Scope the bullet to the Java tier.
- Add a section describing the two tiers and the guarantee the declarative tier offers in exchange:
  a scenario's step list is its complete execution order, and expressions may compute values but
  never act.

## 11. Risks

**We are building a test runner.** Failure messages, reporting and IDE integration are ongoing
costs that Karate would have provided. Sections 6.4 and 8.2 commit to specific mechanisms —
structured results and message-content assertions — rather than intentions.

**The vocabulary may prove insufficient.** Section 8.3 is the designed early-warning system: the
conversion either validates twelve types or names the gaps before the engine is widely used.

**Expression creep.** `js:{ }` invites gradual migration of logic out of steps and into
expressions, eroding the inspectability the specification amendment depends on. The purity rule in
5.4 is the defence and needs enforcing in review, not only at load time.

**Delivery phasing.** This increment is large: a connector, an engine, twelve step types, an
adapter, reporting and a 20-scenario conversion. The implementation plan should sequence it —
`ResourceServiceConnector` first, then engine core with a minimal vocabulary, then the remaining
step types, then conversion — and may warrant splitting into more than one plan.
