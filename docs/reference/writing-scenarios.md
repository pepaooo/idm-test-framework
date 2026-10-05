# Writing scenarios: the reference

Everything a scenario file can contain, and what each step does. For a gentler introduction, start
with the [authoring guide](../guides/scenario-authoring.md); come back here to look things up.

A test (`ScenarioReferenceTest`) checks this page against the framework: every step type has a
section below, and each parameter table lists exactly the parameters that step accepts. If you add
or rename a step parameter, the build tells you to update this page.

## 1. File anatomy

A scenario is one YAML file, and each file runs as its own test.

```yaml
name: Provision a user to eDirectory          # required; shown in test output and results
description: Create the entry and check it    # optional
fixtures:                                     # optional; named test data
  user:
    generate: random-user
steps:                                        # required; run in order, stop at the first failure
  - ldap.create:
      dn: "cn=${user.userId},ou=users"
      as: createdDn
cleanup:                                      # optional; always runs afterwards
  - ldap.delete:
      dn: "${createdDn}"
```

| Key | Required | Meaning |
|---|---|---|
| `name` | yes | the scenario's name, used in test output and results |
| `description` | no | free text |
| `fixtures` | no | named data the steps can reference (see [Fixtures](#3-fixtures)) |
| `steps` | yes | the ordered list of steps; each entry is a map with one key, the step type, whose value holds its parameters |
| `cleanup` | no | steps that run after `steps`, whether they passed or failed (see [Cleanup](#6-cleanup)) |

**Where files go** (all under `idm-test-scenarios/src/test/resources/`):

| Folder | Run by | Against |
|---|---|---|
| `scenarios/` | `YamlScenarioTest` (the default build) and `ContainerScenarios` | embedded LDAP, H2 and WireMock, or containers |
| `scenarios-real/` | `RealEnvironmentScenarios` | your real lab, configured by profile and environment variables |

Only `.yaml` files directly in the folder are picked up (not subfolders, not `.yml`), in file-name
order. To run one file or a group, add `-Dscenario=<file or glob>` to the command, for example
`-Dscenario='provisioning-*'`; see [running tests](running-tests.md).

## 2. Values

A parameter's value can be:

- **A literal:** text, a number, `true`/`false`, a list, or a map. YAML types are kept, so
  `status: 200` is a number and `exists: true` is a boolean.
- **A reference:** `${path}` inside text, for example `"cn=${user.userId},ou=users"`. The path
  walks names and map keys with dots (`${created.body.id}`). References only look things up: no
  arithmetic, conditions or function calls. Quote any value that starts with `${`.
- **An expression:** a whole value written as `"js:{ … }"`, evaluated as JavaScript:

  ```yaml
  displayName: "js:{ user.firstName + ' ' + user.lastName.toUpperCase() }"
  ```

  - The expression must be the **whole** value. `"prefix js:{ … }"` is invalid.
  - Inside the braces, read names directly (`user.lastName`, not `${user.lastName}`).
  - Expressions compute values only: they can't reach Java classes, files, the network or
    connectors, and each runs in a fresh sandbox.
  - For an object literal, wrap it in parentheses: `"js:{ ({label: user.firstName}) }"`.
  - The result may be text, a number, a boolean, a list or a map. If the result contains
    `undefined` anywhere, the step fails, naming where (`result.dn`, `result[1]`). A misspelled
    property such as `user.userid` reads as `undefined`. Write `null` when you mean "no value".
  - The syntax and the names an expression reads are checked when the file loads.

**Names come from three places:**

| Source | Binds | Example |
|---|---|---|
| `fixtures` | the fixture's name, before any step runs | `user` |
| `set:` | each listed name, in order; a later entry may use an earlier one | `- set: { surname: "js:{ user.lastName }" }` |
| `as:` on a step | the step's result, when the step succeeds | `as: createdDn` |

- A name exists only **after** the step that binds it; a step can't reference a name bound later.
  Names can't be re-bound.
- `cleanup:` is the exception: its entries see every name bound anywhere in `steps:`. If the step
  that binds a name never ran, the cleanup entry that uses it is skipped (see
  [Cleanup](#6-cleanup)).
- `as:` accepts a plain new name (`createdDn`, `granted_roles`). If a step has nothing to capture
  and you declare `as:`, the step fails. Each step's **Captures** line below says what it binds.

`set:` is written as a step:

```yaml
- set:
    surname: "js:{ user.lastName.toUpperCase() }"
    greeting: "Hello ${surname}"
```

Resolved values are written to the results file **unredacted**. Use synthetic data, and keep
passwords and tokens in target settings, never in scenario YAML.

**Describing steps.** Any step or cleanup entry may say why it exists with `description:`.
It's text, `${…}` references are resolved, and `js:{ … }` is not allowed. It appears in the
results (`*-results.json`) and in a failing step's message, so a reader who never opens the
YAML still knows what the step was for. On `set:`, `description` is the step's description,
not a name to bind. Like parameters, a description is written to the results unredacted, so never put
secrets in it. A `${…}` that can't be resolved fails the step; in `cleanup:` the description is recorded
as written instead. Reference a field (for example `${user.userId}`) rather than a whole fixture, which
would print as a map.

```yaml
- roles.grant:
    description: "Grant the Finance roles to ${user.userId}"
    user: "cn=${user.userId},ou=users,o=data"
    roles: [ ... ]
```

## 3. Fixtures

A fixture is a named map of test data.

```yaml
fixtures:
  user:
    generate: random-user        # optional generator
    values:                      # literals; these override generated keys
      department: Finance
  config:
    values:                      # a fixture can be literals only
      region: EMEA
```

- **`random-user`** is the only generator. It produces these keys: `userId`, `firstName`,
  `lastName`, `email`, `department`, `jobTitle`, `phoneNumber` and `employeeId`. Each run
  generates new values, so scenarios don't collide.
- **`values`** are literal: `${…}` and `js:{…}` inside them are not evaluated.
- An unknown generator is rejected when the file loads.

## 4. Step reference

Every step is either an **action** (it changes something, and runs once) or an **assertion** (it
checks something, and accepts [`wait:`](#5-waiting) to retry until it passes). Every step accepts
`as:`; assertions also accept `wait:`. The tables below list each step's own parameters.

**How a step can fail.** A failed action stops the scenario. A failed assertion stops it too,
unless `wait:` is set, in which case it is retried until it passes or the wait times out. Some
failures can't be fixed by waiting (a malformed `expect`, an unknown database or target,
misconfigured or rejected credentials). Those end the wait at once, and the message ends with
`(not retried: waiting cannot change this)`.

### LDAP

LDAP DNs are **relative to the directory's base DN** (`base-dn`). With the base
`dc=test,dc=com`, write `cn=jdoe,ou=users`, not the full DN.

**Several directories.** A lab can configure several directories, for example your eDirectory
and an Active Directory that IdM provisions into, each under `idm.test.directories.<name>`. An
`ldap.*` step works in the directory its `directory:` names, or in the **default directory**
when it names none:

- `idm.test.default-directory`, when set;
- otherwise the only directory, when there is just one;
- otherwise `default`, which is what the older `idm.test.ldap` block configures.

A `directory:` that isn't configured, or a step without one when there is no default, fails
at once without retrying (`ldap.verify: unknown directory 'adx'. Configured directories: [ad,
default]`), and the real-environment runner refuses such a scenario before running anything.
With more than one directory configured, step messages name the directory
(`created cn=jdoe,ou=users in directory 'ad'`).

```yaml
- ldap.verify:
    directory: ad
    dn: "CN=${user.firstName} ${user.lastName},OU=Users"
    expect:
      mail: ${user.email}
    wait: { timeout: 5m, poll: 10s }
```

#### `ldap.create`

**Action.** Creates a directory entry.

| Parameter | Required | Meaning |
|---|---|---|
| `dn` | yes | the entry's DN, relative to the base DN |
| `attributes` | no | a map of attribute name to value; the directory's schema decides which attributes are allowed and which are required |
| `directory` | no | the configured directory to work in (`idm.test.directories.<name>`); without it, the default directory. See **Several directories** under LDAP |

**Captures:** the DN you gave, as text. Capture it and reference it in `cleanup:` to delete the
entry afterwards.

**Object classes:** without an `objectClass` attribute the entry is created as
`top, person, organizationalPerson, inetOrgPerson`. Give `objectClass` (one value or a list) to
use other classes, for example for an Active Directory user:

```yaml
- ldap.create:
    directory: ad
    dn: "CN=${user.firstName} ${user.lastName},OU=Users"
    attributes:
      objectClass: [top, person, organizationalPerson, user]
      sAMAccountName: ${user.userId}
      givenName: ${user.firstName}
      sn: ${user.lastName}
```

Active Directory creates such an account disabled and without a password; setting passwords
and enabling accounts is not supported yet (ITF-33).

**Several values:** give a list to write an attribute with several values, for any attribute
(`ou: [Engineering, Security]`). An empty list, or a list holding `null`, a map or a list, fails
the step.

**Fails when:** the directory refuses the entry, for example because it already exists, violates
the schema or the bind lacks rights.

```yaml
- ldap.create:
    dn: "cn=${user.userId},ou=users"
    attributes:
      cn: ${user.userId}
      sn: ${user.lastName}
      givenName: ${user.firstName}
      mail: ${user.email}
    as: createdDn
```

#### `ldap.update`

**Action.** Replaces the values of the listed attributes on an existing entry.

| Parameter | Required | Meaning |
|---|---|---|
| `dn` | yes | the entry's DN, relative to the base DN |
| `attributes` | yes | a map of attribute name to new value (one value, or a list of several); attributes not listed are left as they are |
| `directory` | no | the configured directory to work in (`idm.test.directories.<name>`); without it, the default directory. See **Several directories** under LDAP |

**Captures:** the DN, as text. Nothing is restored automatically: to put old values back,
capture them first with `ldap.verify … as:` and write an `ldap.update` in `cleanup:`.

**Several values:** a list replaces every value of the attribute (`seeAlso: ["cn=a,ou=groups,o=data"]`);
an empty list (`[]`) removes the attribute. To restore an entry, capture it with `ldap.verify … as:`
first: a multi-valued attribute is captured as a list, so writing it back restores every value.
If the attribute was absent when captured, the capture has no such key, so restore it in cleanup
with `[]` (which removes it) instead of a `${…}` reference.

**Fails when:** `attributes` is empty, a list holds `null`, a map or a list, or the directory refuses
the change (no such entry, a schema violation, insufficient rights).

```yaml
- ldap.update:
    dn: "${createdDn}"
    attributes:
      title: Senior Engineer
```

#### `ldap.delete`

**Action.** Deletes an entry.

| Parameter | Required | Meaning |
|---|---|---|
| `dn` | yes | the entry's DN, relative to the base DN |
| `directory` | no | the configured directory to work in (`idm.test.directories.<name>`); without it, the default directory. See **Several directories** under LDAP |

**Captures:** the DN, as text.

**Fails when:** there is no entry at that DN (`ldap.delete found no entry at …`), or the directory
refuses the delete. A cleanup `ldap.delete` for an entry your steps already deleted therefore
fails; see [Cleanup](#6-cleanup).

```yaml
- ldap.delete:
    dn: "cn=${user.userId},ou=users"
```

#### `ldap.verify`

**Assertion (accepts `wait:`).** Checks an entry's attribute values, or whether it exists.

| Parameter | Required | Meaning |
|---|---|---|
| `dn` | yes | the entry's DN, relative to the base DN |
| `expect` | no | a map of attribute name to expected value (see **Values** below); only the listed attributes are compared. Give exactly one of `expect` or `exists` |
| `exists` | no | `true` to assert the entry is present, `false` to assert it is absent. Give exactly one of `expect` or `exists` |
| `directory` | no | the configured directory to work in (`idm.test.directories.<name>`); without it, the default directory. See **Several directories** under LDAP |

**Captures:** with `expect`, a map of the entry's attributes, taken from the same read the check used: every
attribute, a list for a multi-valued one (for example `${entry.mail}`). With `exists`, nothing, so
declaring `as:` fails the step.

**Values:** each attribute in `expect` takes one of these forms. The directory's values are
compared as a set, since LDAP keeps no order.

| Form | Passes when |
|---|---|
| a value, `title: Manager` | the attribute has exactly that one value |
| a list, `ou: [Engineering, Security]` | the attribute has exactly these values, in any order |
| `{ contains: [...] }` | every listed value is present; other values are ignored |
| `{ excludes: [...] }` | none of the listed values is present; an absent attribute passes |
| `{ contains: [...], excludes: [...] }` | both |

- `contains` and `excludes` take a list or a single value.
- When both sides are DNs, they compare as DNs: `CN=Finance, OU=Groups, O=Data` matches
  `cn=finance,ou=groups,o=data`. Other values compare as text, exactly.
- A value is only a match for an attribute with **one** value: on an entry with two `cn` values,
  `cn: jdoe` fails. Use `cn: { contains: [jdoe] }`.
- `excludes` cannot tell a misspelled attribute name from an absent attribute, so it passes for
  either. Pair it with a `contains` on the same attribute, or another check in the same step.

```yaml
- roles.revoke:
    assignments: ["${grant}"]

- ldap.verify:
    dn: "cn=${user.userId},ou=users"
    expect:
      groupMembership:
        contains: ["cn=Employees,ou=groups,o=data"]
        excludes: ["cn=Finance,ou=groups,o=data"]
    wait: { timeout: 5m, poll: 10s }
```

**Fails when:** an attribute differs, or the entry is present or absent contrary to `exists`.
These are retried under `wait:`. Giving both or neither of `expect`/`exists`, an empty `expect`,
a non-boolean `exists`, an `expect` value of `null` (usually a misspelled attribute name), or a
malformed value form (an empty list; a map with keys other than `contains`/`excludes` or with
neither; an empty `contains`/`excludes`; a `null`, map or list inside a list; the same value in
both `contains` and `excludes`) ends the wait at once.

```yaml
- ldap.verify:
    dn: "${createdDn}"
    expect:
      mail: ${user.email}
    wait: { timeout: 2m, poll: 5s }

- ldap.verify:
    dn: "cn=${user.userId},ou=users"
    exists: false
    wait: true
```

### Databases

Databases are named in configuration (`idm.test.databases.<name>`). A step names one with
`database:`. SQL is run as written; use `?` placeholders with `params:` rather than pasting values
into the SQL.

#### `db.execute`

**Action.** Runs one `INSERT`, `UPDATE` or `DELETE`.

| Parameter | Required | Meaning |
|---|---|---|
| `database` | yes | the configured database name, for example `sap` |
| `statement` | yes | the SQL statement, with `?` for each parameter |
| `params` | no | a list of values bound to the `?` placeholders, in order |

**Captures:** the number of rows affected. Zero rows affected is still success, which makes a
cleanup `DELETE … WHERE` safe even when the steps already removed the rows.

**Fails when:** the database isn't configured, can't be reached, or rejects the SQL.

```yaml
- db.execute:
    database: sap
    statement: "INSERT INTO sap_users (username, status) VALUES (?, ?)"
    params: ["${user.userId}", "PENDING"]
```

#### `db.verify`

**Assertion (accepts `wait:`).** Runs a query and checks the result.

| Parameter | Required | Meaning |
|---|---|---|
| `database` | yes | the configured database name |
| `query` | yes | the SQL query, with `?` for each parameter |
| `expect` | yes | a map with `rowCount` (the number of rows expected) and/or `row` (a map of column name to value, which needs the query to return exactly one row) |
| `params` | no | a list of values bound to the `?` placeholders, in order |

**Captures:** the returned rows, as a list of maps from column name to value.

**Fails when:** the row count or a column value differs, or the query fails (for example a table
that provisioning hasn't created yet). These are retried under `wait:`. An unknown database, or a
malformed `expect` (empty, unknown keys, an empty `row`, or a `null` expected value, usually a
misspelled column), ends the wait at once. Numbers compare by value, so `1` matches `1.0`, and
`"200"` matches `200`.

```yaml
- db.verify:
    database: sap
    query: "SELECT status FROM sap_users WHERE username = ?"
    params: ["${user.userId}"]
    expect:
      row: { status: ACTIVE }
    wait: { timeout: 5m, poll: 10s }

- db.verify:
    database: sap
    query: "SELECT 1 FROM sap_users WHERE username = ?"
    params: ["${user.userId}"]
    expect: { rowCount: 0 }
```

### SOAP: roles and resources

These steps call the Identity Manager Role and Resource services. Unlike the LDAP steps, they take
**full DNs, exactly as Identity Manager knows them** (for example `cn=jdoe,ou=users,o=data`), not
DNs relative to a base. A successful grant means Identity Manager **accepted the request**. The
resulting account or entitlement appears later, when workflows and drivers run, so check for it
with an assertion and `wait:`.

#### `roles.grant`

**Action.** Requests one or more role assignments for a user.

| Parameter | Required | Meaning |
|---|---|---|
| `user` | yes | the user's full DN |
| `roles` | yes | a list of role definition DNs (full DNs) |
| `reason` | no | text recorded as the request's reason |

**Captures:** the list of assignment DNs Identity Manager returns. Pass them to `roles.revoke`.

**Fails when:** `roles` is empty, the service rejects the request, or it accepts the request but
returns no assignment DNs. If no Role service is configured (`idm.test.soap.url`), the step fails
without calling anything.

```yaml
- roles.grant:
    user: "cn=${user.userId},ou=users,o=data"
    roles: ["cn=SAP_USER,cn=Level10,cn=RoleDefs,cn=RoleConfig,cn=AppConfig,cn=User Application Driver,cn=driverset1,o=system"]
    reason: Automated test
    as: assignments
```

#### `roles.revoke`

**Action.** Removes role assignments.

| Parameter | Required | Meaning |
|---|---|---|
| `assignments` | yes | a list of assignment DNs, usually the value captured from `roles.grant` |

**Captures:** the list of assignment DNs the service reports as removed.

**Fails when:** `assignments` is empty, or the service rejects the request. If no Role service is
configured, the step fails without calling anything. It takes assignment DNs, not a user and a
role, because that is what the service removes.

```yaml
cleanup:
  - roles.revoke:
      assignments: "${assignments}"
```

#### `resources.grant`

**Action.** Requests a resource for a user.

| Parameter | Required | Meaning |
|---|---|---|
| `user` | yes | the user's full DN |
| `resource` | yes | the resource definition's full DN |
| `reason` | no | text recorded as the request's reason |

**Captures:** the request identifier Identity Manager returns (empty text if it returns none).

**Fails when:** the service rejects the request. If no Resource service is configured
(`idm.test.soap.resource-url`), the step fails without calling anything. Nothing is revoked
automatically: write a `resources.revoke` in `cleanup:`.

```yaml
- resources.grant:
    user: "cn=${user.userId},ou=users,o=data"
    resource: "cn=SAP_ACCESS,cn=ResourceDefs,cn=RoleConfig,cn=AppConfig,cn=User Application Driver,cn=driverset1,o=system"
    reason: Automated test
```

#### `resources.revoke`

**Action.** Requests the removal of a resource from a user.

| Parameter | Required | Meaning |
|---|---|---|
| `user` | yes | the user's full DN |
| `resource` | yes | the resource definition's full DN |
| `reason` | no | text recorded as the request's reason |

**Captures:** the request identifier (empty text if none).

**Fails when:** the service rejects the request, or no Resource service is configured.

```yaml
cleanup:
  - resources.revoke:
      user: "cn=${user.userId},ou=users,o=data"
      resource: "cn=SAP_ACCESS,cn=ResourceDefs,cn=RoleConfig,cn=AppConfig,cn=User Application Driver,cn=driverset1,o=system"
```

### REST (JSON)

REST steps address a **named target**: `idm.test.rest.<target>.base-url` plus the step's `path`.
There is no absolute URL, so the same scenario runs in every environment. Targets authenticate
from their own settings; see [Targets and configuration](#7-targets-and-configuration). Any
HTTP status is data: a `404` is a valid outcome to capture or assert, not a transport failure.
Redirects are not followed.

#### `rest.request`

**Action.** Sends one JSON request.

| Parameter | Required | Meaning |
|---|---|---|
| `target` | yes | the configured target name, for example `idm` |
| `method` | yes | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD` or `OPTIONS` |
| `path` | yes | appended to the target's base URL, for example `/users/${user.userId}` |
| `body` | no | the JSON body, written as YAML (a map, list or value); give `body` or `bodyFrom`, not both |
| `bodyFrom` | no | a reference to a map to send as the body, for example `"${user}"`; give `body` or `bodyFrom`, not both |
| `headers` | no | a map of header name to value; an `Authorization` header here replaces the target's own authentication |

**Captures:** `{status, headers, body}`. The status is a number, header names are lower-case, and
the body is parsed JSON (maps and lists) when the response is `application/json` or `+json`,
otherwise plain text, and empty when there is no body. Example: `${created.body.id}`.

**Fails when:** the target is unknown or has no base URL, its credentials are misconfigured or
rejected by the token server, the method isn't supported, both `body` and `bodyFrom` are given, or
the server can't be reached. An HTTP error status is **not** a failure.

```yaml
- rest.request:
    target: idm
    method: POST
    path: /users
    bodyFrom: "${user}"
    as: created
```

#### `rest.verify`

**Assertion (accepts `wait:`).** Sends a request and checks the status and/or the JSON body.

| Parameter | Required | Meaning |
|---|---|---|
| `target` | yes | the configured target name |
| `path` | yes | appended to the target's base URL |
| `expect` | yes | a map with `status` (the expected HTTP status) and/or `body` (a map of fields that must match; only the listed fields are compared, nested maps are compared field by field) |
| `method` | no | the HTTP method; defaults to `GET` |
| `headers` | no | a map of header name to value; an `Authorization` header here replaces the target's own authentication |

**Captures:** `{status, headers, body}`, as for `rest.request`.

**Fails when:** the status or a listed body field differs, or the server can't be reached. These
are retried under `wait:`. That includes an API answering `401` or `403`, because access often
appears once a role grant has propagated. An unknown target, a malformed `expect` (empty, unknown
keys, an empty or `null` field) or misconfigured or rejected credentials end the wait at once.

```yaml
- rest.verify:
    target: idm
    path: "/users/${user.userId}"
    expect:
      status: 200
      body: { department: Finance, manager: { id: "${managerId}" } }
    wait: { timeout: 2m, poll: 5s }
```

### Raw HTTP (any body, REST or SOAP)

#### `http.request`

**Action.** Sends a request whose body is exactly the text you write, and captures the response as
text. The same step calls a JSON API or a SOAP service: a SOAP call is a `POST` whose body is the
envelope, with a `SOAPAction` header.

| Parameter | Required | Meaning |
|---|---|---|
| `target` | yes | the configured target name; a SOAP service is configured as a target too |
| `method` | yes | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD` or `OPTIONS`, in any letter case |
| `path` | yes | appended to the target's base URL |
| `headers` | no | a map of header name to value; an `Authorization` header here replaces the target's own authentication |
| `body` | no | the body, which **must be text** (usually a YAML block with `|`); `${…}` and `js:{…}` work inside it. To send a JSON object built from a map, use `rest.request` |

**Captures:** `{status, headers, body}`. The status is a number, header names are lower-case
(`resp.headers['content-type'][0]`), and the body is **always text**, `""` when empty. Read JSON
with `js:{ JSON.parse(resp.body).id }`.

**Defaults:** with a body and no `Content-Type` header, it is sent as
`text/plain; charset=UTF-8`. Without an `Accept` header, it sends `Accept: */*`. Nothing else is
added.

**Fails when:** the target is unknown, the method isn't supported, `body` isn't text, `headers`
isn't a map, credentials are misconfigured or rejected, or the server can't be reached. **Any HTTP
status is a successful step**, and the status appears in the step message and the results.

```yaml
- http.request:
    target: roleservice
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

#### `http.verify`

**Assertion (accepts `wait:`).** Sends a request like `http.request`, then checks the response with
JavaScript conditions. Under `wait:`, the request and the checks repeat until every check is true or
the wait times out.

| Parameter | Required | Meaning |
|---|---|---|
| `target` | yes | the configured target name |
| `method` | yes | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD` or `OPTIONS`, in any letter case |
| `path` | yes | appended to the target's base URL |
| `check` | yes | one `js:{ … }` condition, or a list of them; each must return `true` or `false`, and all must be `true` |
| `headers` | no | a map of header name to value, as for `http.request` |
| `body` | no | the body as text, as for `http.request` |

Each check can read **`response`**:

| Key | Value |
|---|---|
| `response.status` | the HTTP status, a number |
| `response.headers` | header names in lower case, each with a list of values: `response.headers['content-type'][0]` |
| `response.body` | the body as text, `""` when empty |
| `response.json` | the body parsed, when the response is JSON (`application/json` or `…+json`); otherwise `null` |
| `response.xml` | the body parsed, when the response is XML (`text/xml`, `application/xml` or `…+xml`, which includes SOAP 1.2's `application/soap+xml`); otherwise `null`. See **Reading XML** below |

Checks may also read every name available at this point in the scenario, such as `user.userId`,
directly — inside `js:{ … }`, names are read as-is (`user.userId`), not as `${user.userId}`;
`${…}` interpolation does not run inside a check.

**Reading XML.** `response.xml` turns the body into names you can follow with dots:

- **Start at the root element:** `response.xml.Envelope.Body…`.
- **Elements are named without their namespace prefix:** `<soapenv:Body>` is `Body` and
  `<ns2:status>` is `status`, whatever prefix the server uses.
- **An element holding only text is that text:** `<name>Ana</name>` is `'Ana'`, and an empty
  element is `''`.
- **Attributes are `'@name'`:** `user['@id']`. When an element has attributes or child elements,
  its own text is `'#text'`: `status['#text']`.
- **An element that appears once is a single value; one that repeats is a list:** `role[1]`.
  When a check may see one or several, `[].concat(user.role)` is always a list:
  `[].concat(user.role).includes('HR')`.
- **Every value is text:** compare with `'42'`, or convert with `Number(user['@id'])`.
- **A body with a DOCTYPE, or nested more than 90 elements deep, is refused** as not
  well-formed, so a response can't make the check read files or expand entities.

```xml
<user id="42">
  <status code="1">ACTIVE</status>
  <name>Ana</name>
  <role>Admin</role>
  <role>HR</role>
  <manager/>
</user>
```

reads as

```js
{ user: { '@id': '42',
          status: { '@code': '1', '#text': 'ACTIVE' },
          name: 'Ana',
          role: ['Admin', 'HR'],
          manager: '' } }
```

**Captures:** the `response` map of the passing attempt.

**Fails when:**
- a check is `false`: the message names it (`check 2 of 3 is false: …`) with the status and the
  start of the body. Retried under `wait:`.
- a check throws, for example `response.json.department` while `response.json` is still `null`.
  Retried under `wait:`.
- when a failing check reads `response.xml` and it is `null`, the message ends with the reason,
  for example `(response.xml is null: Content-Type is text/html)` or
  `(response.xml is null: the body is not well-formed XML at line 3, column 7: …)`. This is
  retried like any failing check.
- a check returns something other than `true`/`false`: the message shows what it returned. This
  ends the wait at once, because it is a mistake in the check.
- the server can't be reached. Retried.
- the target is unknown, credentials are misconfigured or rejected, the method isn't supported,
  `body` isn't text or `headers` isn't a map. These end the wait at once.

Any HTTP status can be checked. A `404` is only a failure if a check says so.

```yaml
- http.verify:
    target: idm
    method: GET
    path: "/users/${user.userId}"
    check:
      - "js:{ response.status === 200 }"
      - "js:{ response.json.department === 'Finance' }"
    wait: { timeout: 2m, poll: 5s }

- http.verify:
    target: roleservice
    method: POST
    path: /IDMProv/role/service
    headers:
      Content-Type: "text/xml; charset=UTF-8"
      SOAPAction: "http://www.novell.com/role/service/getUserRequest"
    body: |
      <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
        <soapenv:Body>...${user.userId}...</soapenv:Body>
      </soapenv:Envelope>
    check:
      - "js:{ response.status === 200 }"
      - "js:{ response.xml.Envelope.Body.getUserResponse.user.status === 'ACTIVE' }"
    wait: true
```

## 5. Waiting

Provisioning is asynchronous, so assertions can retry. Only assertions (`ldap.verify`,
`db.verify`, `rest.verify`, `http.verify`) accept `wait:`; on an action it is rejected when the file loads.

| Form | Meaning |
|---|---|
| no `wait:`, or `wait: false` | check once |
| `wait: true` | retry with the configured defaults |
| `wait: { timeout: 30s, poll: 2s }` | retry every `poll` until it passes or `timeout` expires |

- **Durations** are a positive whole number with `s`, `m` or `h` (`30s`, `5m`, `1h`).
- **The defaults for `wait: true`** come from `idm.test.executor.default-timeout-seconds` and
  `default-poll-interval-seconds`: 60 s / 1 s in the `test` profile, 5 min / 5 s otherwise, unless
  your environment overrides them.
- **Parameters are resolved once,** before the first attempt; each attempt repeats the same
  check — except `http.verify`'s `check`, which is evaluated fresh on every attempt against that
  attempt's `response` (reading scenario names as they stood when the step started).
- **Failures that waiting can't fix end the wait at once**, as each step's **Fails when** paragraph
  says. The message then ends with `(not retried: waiting cannot change this)`.
- **When the wait times out,** the step fails with the last result it saw, followed by
  `(last observed value after retrying)`. The results record the number of attempts and the time
  waited.

## 6. Cleanup

Nothing is undone automatically. Write teardown yourself, in a top-level `cleanup:` block:

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

- Cleanup runs **after the steps, whether they passed or failed**, in the order written, and every
  entry is attempted even if an earlier one fails.
- Entries must be **actions**: no assertions, no `wait:`, no `as:`.
- Entries see **every name bound anywhere** in `steps:`.
- Each entry ends in one of three ways:
  - **SUCCEEDED:** the step succeeded.
  - **FAILED:** the step failed, or a value couldn't be resolved for a reason other than a name
    that was never bound. **A FAILED cleanup entry fails the scenario**, even if every step passed.
  - **SKIPPED:** it references a name whose step never ran (for example `${createdDn}` when
    `ldap.create` failed before binding it). SKIPPED never fails the scenario.
- **The idiom:** capture with `as:` in `steps:`, and reference that capture in `cleanup:`. The
  cleanup then runs only if the thing was actually created.
- Don't clean up what your steps already removed. For example, an `ldap.delete` in `cleanup:` for
  an entry a step already deleted fails, because there is nothing to delete.

## 7. Targets and configuration

Scenarios name things. Configuration says where they are. The details live in the configuration
reference; this section says where to look.

- **REST and HTTP targets:** `target: idm` means `idm.test.rest.idm.base-url`. A target
  authenticates with its own settings (basic authentication, or an OAuth2 client-credentials
  token), never with values in the scenario. A step's own `Authorization` header replaces the
  target's for that request only, which is how you test bad credentials. See
  [REST API configuration](configuration.md#rest-api-configuration).
- **Databases:** `database: sap` means `idm.test.databases.sap`. See
  [database configuration](configuration.md#database-configuration).
- **LDAP:** `directory: ad` means `idm.test.directories.ad`; without `directory:`, the default
  directory (the older `idm.test.ldap.*` block is the directory `default`). LDAP steps take DNs
  relative to that directory's `base-dn`. See [LDAP configuration](configuration.md#ldap-configuration).
- **SOAP:** `idm.test.soap.url` (roles) and `idm.test.soap.resource-url` (resources), with the SOAP
  username and password. SOAP steps take full DNs.
- **TLS** (`ldaps://`, `https://`): the servers' CA must be in the test JVM's truststore. See
  [TLS certificates](configuration.md#tls-certificates-ldaps-https).
- **A real lab:** what to set for each repository your scenarios use, and what the real-environment
  runner refuses before running, are in the
  [real-environment README](../../idm-test-scenarios/src/test/resources/scenarios-real/README.md).

## 8. What validation rejects at load

Every file is checked before any step runs, so a typo fails in seconds rather than after minutes of
waiting. Each message names the file and the step. Real examples:

| Mistake | Message |
|---|---|
| unknown step type | `demo.yaml, step 1 (ldap.creat): unknown step type. Available: [db.execute, db.verify, http.request, …]` |
| missing required parameter | `demo.yaml, step 1 (ldap.create): missing required parameter 'dn'` |
| unknown parameter (often a typo) | `demo.yaml, step 1 (ldap.create): unknown parameter 'atributes'. ldap.create accepts: [as, attributes, description, directory, dn]` |
| a reference to a name not bound yet, in `${…}` or `js:{…}` | `demo.yaml, step 1 (ldap.create): references '${usr...}' but nothing named 'usr' has been declared or bound at this point` |
| an expression that doesn't compile | `demo.yaml, step 1 (ldap.create): invalid expression js:{  1 +  }: Unexpected end of file (expression#1)` |
| unknown generator | `demo.yaml: fixture 'user' uses unknown generator 'random-person'. Available: [random-user]` |
| a malformed `wait:` | `demo.yaml, step 1 (ldap.verify): invalid 'wait': Unknown duration unit 'x' in '30x'. Use s, m or h.` |
| `wait:` on an action | `demo.yaml, step 1 (ldap.create): 'wait' is only valid on assertions, and this step is an action` |
| an assertion in `cleanup:` | `demo.yaml: cleanup entry 1 (ldap.verify): cleanup entries must be actions; ldap.verify is an assertion and tears nothing down` |
| `as:` (or `wait:`) in `cleanup:` | `demo.yaml: cleanup entry 1 (ldap.delete): 'as' is not allowed on a cleanup entry` |
| a `description` that isn't text | `demo.yaml, step 2 (roles.grant): 'description' must be text` |
| a `description` that is a `js:{…}` expression | `demo.yaml, step 2 (roles.grant): 'description' must be text; js:{ … } is not allowed here` |
| missing `name` | `demo.yaml: 'name' is required` |
| a `check:` that isn't `js:{ … }`, or an empty list | `demo.yaml, step 1 (http.verify): 'check' must be js:{ … } expressions; got "status is 200"` |

Some mistakes can only be found when the step runs: a misspelled property on a bound name
(`${user.userid}`), a value of the wrong shape (`expect` as text instead of a map), or a DN the
directory rejects. Those fail the step with a message naming the cause.

## 9. Results

Each run writes a JSON results file in `idm-test-scenarios/target/`:

| File | Written by |
|---|---|
| `scenario-results.json` | `YamlScenarioTest` (the embedded run) |
| `real-environment-results.json` | `RealEnvironmentScenarios` and `ContainerScenarios` |
| `multi-directory-results.json` | `MultiDirectoryScenarioTest` |

**The HTML report.** Beside each results file, the run writes a report you can open in any
browser, attach or e-mail: `scenario-report.html`, `real-environment-report.html` or
`multi-directory-report.html`. It is one file with nothing loaded from outside, and it is
rewritten after every scenario, so an interrupted run still has one.

- **Header:** the runner, start time, duration, active profiles, the `-Dscenario` filter, and the
  endpoints the selected scenarios use. User names and passwords in URLs are removed, and
  credential-looking URL parameters are shown as `***`.
- **Summary:** scenarios, passed, failed, time in scenarios (the sum of the scenario durations); Expand all, Collapse all, Only failed.
- **Each scenario:** open when it failed. Each step shows its description, duration (attempts and
  time waited for `wait:` steps), message and parameters; cleanup entries follow with their
  status. Parameter values under keys containing `password`, `passwd`, `pwd`, `secret`, `token`,
  `authorization`, `credential`, `apikey`, `api_key`, `api-key` or `cookie` (matched anywhere in the
  key, case-insensitive) are shown as `***` in the report; the JSON keeps them as recorded.
  Masking is by parameter name only, so a secret written into a description, a message, or a
  value under an ordinary key appears as written — keep secrets in target and database settings.
- **Printing** or saving as PDF expands every scenario.

**Evidence.** Under each step the report shows what the step sent and received, in an
**Evidence** section that is open on a failed step. The JSON results hold the same evidence under
the step's `evidence` key (`"kind": "http"`, `"sql"` or `"ldap"`).

| Step | Evidence |
|---|---|
| `http.request`, `http.verify`, `rest.request`, `rest.verify` | method, URL, request headers and body, status, response headers and body, time; on a connection error, the request and the error |
| `db.verify` | the SQL, its parameters and the rows returned |
| `db.execute` | the SQL, its parameters and the number of rows changed |
| `ldap.create`, `ldap.update` | the DN and the attributes written |
| `ldap.delete` | the DN, and that the entry was absent when there was none |
| `ldap.verify` | with `expect`, every attribute read; with `exists`, whether the entry was there; with `expect`, that the entry was absent when there was none |

- **Last attempt only:** a step with `wait:` keeps the evidence of its last attempt.
- **Nothing sent, nothing shown:** a malformed parameter or an unknown target, database or
  directory records no evidence, nor does a cleanup entry.
- **Masking**, before anything is recorded, using the same names as the parameter masking above:
  headers with such names (`Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, …); values under
  such keys in JSON bodies; such elements and attributes in XML or SOAP bodies (including CDATA and
  child elements); such `name=value` pairs in form bodies; `Location` and `Content-Location`
  header values and connection error text (URL credentials and secret query values); such LDAP
  attributes (`userPassword`, `unicodePwd`); database columns with such names; and SQL parameters
  bound to such a column in `INSERT … (cols) VALUES (?, …)` or `col = ?` / `<>` / `!=` / `LIKE ?`.
  Any other SQL parameter is shown as given, so don't put a secret in `params` of a statement
  written another way, and a secret inside free text appears as written. Secrets carried as
  name/value pairs are not masked either, for example an XDS
  `<attr attr-name="nspmDistributionPassword"><value>…</value></attr>` or JSON
  `{"name": "password", "value": "…"}`, because masking goes by the element or key name, not by a
  sibling's value.
- **Size limits:** bodies are cut at 16 384 characters, at most 20 rows are kept (the report
  says how many more there were), each database value is cut at 1 024 characters, and an LDAP
  attribute shows at most 20 values.

Each scenario record has:
- its source file, status, duration and `description` (null when none)
- every step: its type, its `description` (null when none), the **resolved** parameters
  (unredacted), success, message, and for a waited step the number of attempts and the time waited
- every cleanup entry: its type, its `description` (null when none), resolved parameters, status
  (SUCCEEDED, FAILED or SKIPPED) and message

A failing scenario's summary starts with the file and the step:

```text
provisioning.yaml, step 3 (ldap.verify): ldap.verify expected cn=jdoe,ou=users to be present but it was absent (last observed value after retrying)
```

A step that has a `description` shows it after the type:

```text
demo.yaml, step 3 (roles.grant: "Grant the Finance roles to test.user.179…"): <message>
```

- `(last observed value after retrying)`: the assertion never passed before the timeout; the
  message is the last attempt's result.
- `(not retried: waiting cannot change this)`: the failure can't be fixed by waiting, so the wait
  ended at once.
- If a step failed, it leads the summary, and any FAILED cleanup entries are listed after it.

JUnit's own reports are in each module's `target/surefire-reports/`.

## 10. A worked example

A realistic end-to-end scenario: create the directory entry, request a role, then wait for the SAP
account, the Identity Manager REST view and the directory to show the result, and tear it all
down.

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
      description: "Create ${user.userId} in the directory"
      dn: "cn=${user.userId},ou=users"
      attributes:
        cn: ${user.userId}
        sn: ${user.lastName}
        givenName: ${user.firstName}
        mail: ${user.email}
        description: ${displayName}
      as: createdDn

  - roles.grant:
      description: Request the SAP role for the new user
      user: "cn=${user.userId},ou=users,o=data"
      roles:
        - "cn=SAP_USER,cn=Level10,cn=RoleDefs,cn=RoleConfig,cn=AppConfig,cn=User Application Driver,cn=driverset1,o=system"
      reason: Automated provisioning test
      as: assignments

  - db.verify:
      description: Wait for the SAP account to become active
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
      description: Remove the test entry
      dn: "${createdDn}"
```

What each part does:

1. **`fixtures`** generates a fresh user; `department: Finance` overrides the generated
   department.
2. **`set`** computes a display name with an expression, bound as `displayName` for later steps.
3. **`ldap.create`** creates the entry. Its DN is **relative to the base DN**, and the DN is
   captured as `createdDn`.
4. **`roles.grant`** requests the SAP role. SOAP steps take **full DNs**: `…,o=data` and the role
   DN here are examples, so replace them with your tree's. The assignment DNs are captured for
   cleanup.
5. **`db.verify`** waits up to 5 minutes for the SAP driver to create an active account.
6. **`rest.verify`** waits for Identity Manager's REST API to show the user in Finance.
7. **`ldap.verify`** checks the entry still carries the mail address and display name, with the
   default wait.
8. **`cleanup`** revokes the role and deletes the entry. If `roles.grant` never ran, its revoke is
   SKIPPED rather than failing.

To run it against your lab, put the file in `scenarios-real/` and configure the `idm` REST target,
the `sap` database, LDAP and the SOAP Role service. See
[Targets and configuration](#7-targets-and-configuration).
