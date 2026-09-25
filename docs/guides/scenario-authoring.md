# Writing scenarios

This guide describes the current [capability baseline](../architecture/capabilities.md). Begin in the repository root with Java 21. See [running tests](../reference/running-tests.md) if the build environment is not ready.

## 1. Run the bundled example

The file [user-provisioning.yaml](../../idm-test-scenarios/src/test/resources/scenarios/user-provisioning.yaml) already exists. Run it through the JUnit adapter:

```bash
./mvnw -pl idm-test-scenarios -am test \
  -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false
```

The example generates a user, computes a display name, creates an LDAP entry, verifies attributes and attempts deletion during cleanup. It uses embedded LDAP. It does not create a user through a real Identity Manager.

To author another scenario, copy this example to a new `.yaml` file directly under `idm-test-scenarios/src/test/resources/scenarios/`. The adapter discovers files in sorted filename order. It does not recursively discover subdirectories or `.yml` files.

```yaml
name: User provisioning to eDirectory
description: Create a user and verify the directory entry carries its attributes
fixtures:
  user:
    generate: random-user
    values:
      department: Engineering
steps:
  - set:
      displayName: "js:{ user.firstName.charAt(0).toUpperCase() + user.firstName.slice(1) + ' ' + user.lastName.toUpperCase() }"
  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      as: created
      attributes:
        cn: ${user.userId}
        sn: ${user.lastName}
        givenName: ${user.firstName}
        mail: ${user.email}
        description: ${displayName}
  - ldap.verify:
      dn: "${created}"
      expect:
        givenName: ${user.firstName}
        sn: ${user.lastName}
        mail: ${user.email}
        description: ${displayName}
      wait:
        timeout: 30s
        poll: 2s
```

The DN is relative to the LDAP base configured by the test context. In the embedded suite that base is `dc=test,dc=com`, so `cn=...,ou=users` addresses an entry beneath it.

## 2. Fixtures and references

`random-user` generates `userId`, `firstName`, `lastName`, `email`, `department`, `jobTitle`, `phoneNumber` and `employeeId`. Literal `values` override generated fields. A fixture can also contain only literal values. Fixture values are not evaluated as expressions.

`${user.firstName}` looks up map keys and substitutes a string. It does not perform arithmetic or return a typed object. References must name a fixture or earlier binding; missing nested properties can still fail at execution.

Use synthetic data. Resolved parameters are written to the results file without redaction.

## 3. Expressions, variables and captures

An expression must occupy the whole value and should be quoted in YAML:

```yaml
- set:
    surname: "js:{ user.lastName.toUpperCase() }"
    greeting: "Hello ${surname}"
```

Entries in `set` bind in order; the second entry can use the first. Existing names cannot be overwritten. Use simple identifier names such as `surname` or `created_user`.

Expressions read scope variables directly: write `user.lastName`, not `${user.lastName}` inside `js:{ ... }`. JavaScript template-literal interpolation is handled by JS itself. Expressions may return plain strings, numbers, booleans, maps or lists, but cannot access Java classes or connectors. There is no custom helper library.

A value such as `prefix js:{ ... }` is invalid. For an object literal, use parentheses: `"js:{ ({label: user.firstName}) }"`. Some regex literals containing braces, quotes or comment delimiters are rejected by the outer parser; ordinary string operations avoid this limitation. Expression syntax is checked up front, and so are root names: `js:{ usr.userId }` is rejected at load time exactly like `${usr.userId}`. A misspelled property such as `user.userid`, by contrast, is only caught when the step runs, and a result that contains `undefined` anywhere is an error, not a silent `null`; the message names where it sits (`result.dn`, `result[1]`). This includes an expression whose last statement produces no value, such as `var x = 1` or a call to a function with no `return`; end with the value you want, for example `var x = 1; x`. Write `null` explicitly when no value is intended.

A guarded read such as `typeof created === 'undefined' ? null : created.dn` still counts as reading `created`, so a cleanup entry written like that is SKIPPED, not run with a `null`, when `created` was never bound.

`as` captures a successful operation's value:

| Step | Captured value |
|---|---|
| `ldap.create` | Relative DN string |
| `ldap.verify` | Attribute view built by the step after a successful follow-up read |

`as` must be a fresh literal identifier. A requested capture with no value fails the scenario. Verification capture is a selected map, not a promise to return every LDAP attribute; inspect [LdapVerifyStep](../../idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java) before relying on a field.

## 4. Supported steps

| Step | Required parameters | Optional parameters | Behavior |
|---|---|---|---|
| `ldap.create` | `dn` | `attributes`, `as` | Create once; write teardown yourself in `cleanup:` |
| `ldap.verify` | `dn`, `expect` | `wait`, `as` | Verify attributes; may retry |
| `http.request` | `target`, `method`, `path` | `headers`, `body`, `as` | Send a raw string body (JSON, XML, a SOAP envelope); capture `{status, headers, body}` as text; never fails on HTTP status |
| `set` | One or more name/value entries | — | Bind values without a connector call |

The create step maps known LDAP attributes into `LdapUser` and passes remaining attributes as custom attributes. The directory schema decides which are acceptable. Supply required directory attributes even when the YAML parameter itself is optional.

Use a nonempty `expect` map to check meaningful state. The validator checks that `expect` is present, not that every nested assertion expresses a useful condition.

### HTTP requests with a raw body (REST and SOAP)

`http.request` sends its `body` exactly as written and captures the response as text, so the same step calls a JSON API or a SOAP service. A SOAP call is a `POST` whose body is the envelope, with a `SOAPAction` header:

```yaml
- http.request:
    target: roleservice                  # idm.test.rest.roleservice.base-url
    method: POST
    path: /IDMProv/role/service
    headers:
      Content-Type: "text/xml; charset=UTF-8"
      SOAPAction: "http://www.novell.com/role/service/getUserRequest"
    body: |
      <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
        <soapenv:Body>...${user.userId}...</soapenv:Body>
      </soapenv:Envelope>
    as: resp
```

- **Addressing:** the target is a named base URL from configuration, just as for `rest.*`.
- **`body` must be a string.** To send a JSON object built from a map, use `rest.request`.
- **Default headers:** with a body and no `Content-Type`, the request is sent as `text/plain; charset=UTF-8`. Without an `Accept`, it sends `Accept: */*`.
- **The capture:**
  - `resp.status` is a number
  - `resp.headers` uses lower-case names, e.g. `resp.headers['content-type'][0]`
  - `resp.body` is always text, `""` when empty. Read JSON with `js:{ JSON.parse(resp.body).id }`.
- **Any HTTP status is a successful step.** The status appears in the step message and the report. Checking it, and reading XML responses from JavaScript, are planned separately.

Database, role, resource and `rest.*` steps also exist; a complete reference is tracked as
ITF-10. `http.request` is described above.

## 5. Waiting

An assertion without `wait` runs once. `wait: true` uses configured defaults; `wait: false` also runs once. An explicit map supports `timeout` and `poll`:

```yaml
wait: { timeout: 30s, poll: 2s }
```

Use positive integer durations with `s`, `m` or `h`. The test profile defaults to 60 seconds and a 1-second poll interval. Actions reject `wait`. Waiting repeats the assertion with already resolved parameters; it does not recompute expressions on every attempt or impose a hard deadline on a blocking protocol call.

## 6. Results and cleanup

The adapter writes `idm-test-scenarios/target/scenario-results.json`. Each record contains the source, status, duration and executed steps. Retry records include attempts and elapsed waiting. Surefire reports appear in each module's `target/surefire-reports/`.

A representative failure summary has this shape; values below are illustrative:

```text
example.yaml, step 2 (ldap.verify): ldap.verify failed for cn=example,ou=users: ...
```

For a timed-out assertion, the summary also reports attempts and waited milliseconds. Read the underlying message to distinguish a missing entry, mismatched attribute, or connection error. Invalid files produce failures with no executed steps; a normal step failure stops later steps.

> **Superseded by ITF-19** (docs/specs/2026-09-24-explicit-cleanup-design.md): steps no longer register undos; teardown is the author's cleanup: block, and a FAILED cleanup entry fails the scenario.

The engine no longer infers teardown. A scenario writes its own top-level `cleanup:` block: a list
of steps with the same shape as `steps:`, run after the steps, whether they passed or failed. Every
cleanup entry is attempted, in declaration order, even if an earlier one fails.

The idiom for conditional teardown is to capture with `as:` in `steps:` and reference that capture
in `cleanup:`:

```yaml
steps:
  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      attributes: { cn: "${user.userId}", sn: "${user.lastName}" }
      as: createdDn

cleanup:
  - ldap.delete:
      dn: "${createdDn}"
```

Each cleanup entry ends in exactly one of three outcomes:

- **SUCCEEDED** — the step returned success.
- **FAILED** — the step returned failure, or resolving its parameters failed for any reason other
  than an unbound root name (for example, a typo'd field on a bound value). A FAILED cleanup entry
  fails the scenario, even if every step passed. If a step already failed, that failure leads the
  summary and any cleanup failures are listed after it.
- **SKIPPED** — a parameter references a root name that was never bound because the step that
  would have bound it (via `as:`) never ran, e.g. `${createdDn}` when `ldap.create` never executed.
  SKIPPED never fails the scenario.

Cleanup entries see the scenario's full scope (fixtures plus every capture bound anywhere in
`steps:`), not just names bound before them. At load time, `wait:` and `as:` are rejected on
cleanup entries — they run once, and nothing runs after cleanup to consume a capture — and a
reference to a name that is bound nowhere in the scenario is a load-time error, not a runtime
SKIPPED. Cleanup entries must be actions; a retryable `*.verify` step is rejected there.

Cleanup outcomes appear in the JSON report per entry (`ScenarioResult.cleanup()`), each carrying
its type, resolved parameters (empty for SKIPPED), status and message.

## 7. When to use Java

Use Java when an operation has no YAML step, or when the workflow needs branching or computation beyond value expressions. Existing workflows live under [scenario sources](../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/).

This excerpt comes from `UserProvisioningWorkflowTest.testUserProvisioningToLdap`, after the test has prepared `testUser` and `userDn`:

```java
OperationResult<LdapUser> createResult = edirectoryConnector.create(
        testUser.toLdapUser(userDn)
);

assertThat(createResult.isSuccess())
        .as("User should be created successfully in LDAP")
        .isTrue();

trackLdapDn(userDn);
```

This is a method-body excerpt, not a standalone class. For imports, fixture preparation and the full workflow, use [UserProvisioningWorkflowTest](../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/provisioning/UserProvisioningWorkflowTest.java). Its `BaseIntegrationTest` supplies the connector and tracking helper. Java test cleanup is distinct from the YAML engine's `cleanup:` block and currently has incomplete role cleanup.

## 8. Adding a step as a developer

Implement the existing [StepType](../../idm-test-engine/src/main/java/com/idm/test/engine/spi/StepType.java) contract:

```java
String name();
boolean retryable();
Set<String> requiredParams();
Set<String> acceptedParams();
StepOutcome execute(Map<String, Object> params, boolean captureRequested);
```

The metadata methods other than `name` have defaults. Declare required and optional fields accurately. Validation adds required keys, `as`, and `wait` for retryable steps to the effective accepted set.

The engine supplies resolved parameters with `as` and `wait` removed. Return an outcome instead of throwing. If capture is requested on success, supply a non-null plain value. Steps no longer register undo actions; an author who needs teardown writes it explicitly as a `cleanup:` entry. Mark an assertion retryable only when repeated execution is appropriate.

Add the step to [YamlScenarioTest.registry](../../idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java), which supplies the same registry to validation and execution. Add any connector dependency to the engine POM. Test failure, capture, and cleanup behavior against suitable embedded protocol fixtures, then update the supported-step table and capability matrix.
