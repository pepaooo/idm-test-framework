# Per-step evidence in the results and the HTML report — design

**Ticket:** ITF-37. **Depends on:** ITF-36 (HTML report). **Follow-up:** SOAP envelope capture
for `roles.*` / `resources.*` (a new ticket, plugging into the same mechanism).

## 1. The problem

The report (ITF-36) shows each step's parameters and message, but not what was actually sent
and received. When `http.verify` fails after 60 attempts, the author sees the failed check but
not the response body; when `db.verify` fails, not the rows the query returned. Karate shows each
request and response under its step; this ticket does the same for the HTTP, database and LDAP
steps.

## 2. Decisions (jangulo, 2026-10-05)

1. **Two parts:** this ticket builds the mechanism plus evidence for `http.*`, `rest.*`, `db.*`
   and `ldap.*`. SOAP envelopes for `roles.*` / `resources.*` need a JAX-WS handler in the SOAP
   connectors and are a follow-up ticket.
2. **Evidence is recorded in both the JSON results and the HTML report,** masked once, when it
   is recorded.
3. **Only the last attempt** of a `wait:` step keeps its evidence.
4. **Size limits are fixed in code:** HTTP bodies 16 KB each, 20 database rows, 1 KB per
   database value, 20 values per LDAP attribute.
5. **A SQL parameter is masked when the column it binds has a secret-looking name;** other
   parameters are shown, and the docs say so.
6. **Report layout:** a collapsible "Evidence" section per step, open on the failed step, with
   request and response side by side (chosen over tabs and over always-visible evidence).

## 3. Data

### `Evidence` (engine module, `com.idm.test.engine.spi`, Spring-free)

A sealed interface with three records. **Each record's canonical constructor masks and truncates
its input**, so an unmasked or oversized evidence object cannot exist and no step can forget to
mask.

| Record | Components |
|---|---|
| `HttpEvidence` | `method`, `url` (sanitised with `ReportUrls`), `requestHeaders` (name → list of values), `requestBody` (text, or a JSON structure for `rest.*`), `status` (`Integer`, null when no response), `responseHeaders`, `responseBody` (text, or a JSON structure for `rest.*`), `durationMillis`, `error` (the connection error, or null) |
| `SqlEvidence` | `database`, `sql`, `params` (list), `rows` (list of column → value maps, or null for `db.execute`), `totalRows` (the count before truncation), `updateCount` (`Integer`, `db.execute` only) |
| `LdapEvidence` | `directory` (null for the default), `dn`, `operation` (`create`, `update`, `delete`, `read`, `exists`), `attributes` (name → value or list of values; empty for `delete` and `exists`), `present` (`Boolean`, `exists` only) |

Each record has a `kind()`: `http`, `sql`, `ldap`. The JSON results write it as `"kind"`.

### `StepOutcome` and `StepResult`

- **`StepOutcome`** gains a last component, `Evidence evidence` (nullable). The existing
  constructors and factories keep working with `null` evidence; `withEvidence(Evidence)` returns
  a copy carrying it.
- **`StepResult`** gains a last component, `Evidence evidence` (nullable); the existing
  constructors are kept.

### `Secrets` (engine module, `com.idm.test.engine.result`)

The secret-name rule moves out of `HtmlReportWriter` into one class used by the report and by
evidence. A name is a secret when, lower-cased, it contains `password`, `passwd`, `pwd`,
`secret`, `token`, `authorization`, `credential`, `apikey`, `api_key`, `api-key` or `cookie`
(this already covers `userPassword`, `unicodePwd` and `Set-Cookie`). Masked values are `***`.

## 4. Masking

Applied by the record constructors:

- **HTTP headers** whose name is a secret: every value becomes `***` (`Authorization` whatever its
  scheme, `Cookie`, `Set-Cookie`, `X-Api-Key`, …).
- **JSON bodies** (structures, and text bodies that parse as JSON): values under secret keys
  become `***` at any depth. A text body that parses as JSON stays text, with the masked values
  replaced.
- **XML / SOAP bodies:** the text of an element whose local name is a secret
  (`<password>…</password>`, `<ns:clientSecret>…</ns:clientSecret>`), and the value of an
  attribute whose name is a secret (`password="…"`), become `***`.
- **Form bodies** (`application/x-www-form-urlencoded`, or any text with `name=value&…` pairs):
  values of secret names become `***`.
- **LDAP attributes** whose name is a secret: every value becomes `***`.
- **SQL parameters:** the SQL is scanned, ignoring case and `"`/`` ` ``/`[]` quoting, for
  - `INSERT … (col1, col2, …) VALUES (?, ?, …)`: the n-th `?` binds the n-th column
  - `col = ?`, `col <> ?`, `col != ?`, `col LIKE ?` (including `SET col = ?` in an UPDATE)

  A parameter bound to a secret column becomes `***`. A parameter whose column cannot be found
  this way is recorded as given.
- **URLs:** `ReportUrls.sanitise` as today (userinfo removed, secret query parameters masked).
- **Never recorded:** tokens fetched by `RestAuthenticator`; they reach the step only inside the
  `Authorization` header, which is masked.

**Limits, documented:** a secret inside free text (a description, a message, a body that is
neither JSON, XML nor form data, a positional SQL parameter whose column isn't recognised) is
shown as written.

## 5. Size limits

Applied by the record constructors, after masking:

| Evidence | Limit | Beyond it |
|---|---|---|
| HTTP request / response body | 16 KB of text each (JSON structures measured as serialised text) | cut to the limit, followed by `… [truncated, N more characters]`; a truncated JSON structure becomes text |
| Database rows | 20 | the first 20 kept; `totalRows` holds the real count and the report shows `… N more rows` |
| One database value | 1 KB of text | cut, followed by `… [truncated, N more characters]` |
| LDAP attribute values | 20 per attribute | the first 20 kept, followed by `… N more values` |

The limits are constants in code, with no configuration.

## 6. What each step records

| Step | Evidence |
|---|---|
| `http.request`, `http.verify` | `HttpEvidence` with the request as sent and the text response; on a connection error, the request and `error`, no response |
| `rest.request`, `rest.verify` | `HttpEvidence`; JSON request and response bodies kept as structures |
| `db.verify` | `SqlEvidence` with the rows returned |
| `db.execute` | `SqlEvidence` with the update count |
| `ldap.create`, `ldap.update` | `LdapEvidence` with the attributes written |
| `ldap.delete` | `LdapEvidence`, operation `delete` |
| `ldap.verify` | with `expect`: operation `read`, the attributes read (every attribute, as `readAttributes` returns them); with `exists`: operation `exists` and `present` |

- **Failures carry evidence** whenever the request reached the system: an HTTP 500, a mismatched
  row, an attribute that differs, a directory that refused a write.
- **No evidence** when nothing was sent: a malformed parameter, an unknown target, database or
  directory (the final failures), or a step that threw.
- **Only the last attempt** of a `wait:` step keeps its evidence; the engine replaces it on each
  attempt.
- **`ldap.verify` with `expect`** reads the entry once per attempt and uses that read both to
  compare and as evidence (and as its `as:` capture), so evidence adds no directory load.
- **Cleanup entries** record no evidence.

## 7. Results and report

**JSON (`JsonResultWriter`):** each step gains `"evidence": { "kind": "http" | "sql" | "ldap",
… }` with the record's components; the key is left out when there is none.

**HTML (`HtmlReportWriter`),** in the step's existing `<div class="evidence">` slot:
- a `<details class="evidence">`, open when the step failed, with the summary `Evidence` and a
  short description:
  - `GET /api/users/jdoe → 200 · 84 ms` (or `→ no response`)
  - `SQL · 1 parameter · 2 rows` (or `updated 3 rows`)
  - `ldap.create cn=jdoe,ou=users · 6 attributes`
  - plus `(last attempt)` when the step made more than one attempt
- **HTTP:** Request and Response side by side (stacked on narrow screens), each with its first
  line, headers, then the body; JSON pretty-printed; the error when there was no response.
- **SQL:** the statement in a code block, `params: […]`, then the rows as a table with
  `… N more rows`, or `updated N rows`.
- **LDAP:** the DN, operation and directory, then an attribute → values table.
- Everything is HTML-escaped. Expand all / Collapse all and printing include evidence. The page
  stays self-contained.

## 8. Documentation

- `docs/reference/writing-scenarios.md` §9 "Results": evidence, what each step records, the
  masking rules and their limits, the size limits, last attempt only.
- The capability matrix and README: one line each.

## 9. Testing

**`EvidenceTest`** (engine, unit):
- each masking rule: header, nested JSON structure, JSON text, XML element and attribute with a
  namespace prefix, form body, LDAP attribute, SQL `INSERT … VALUES`, `col = ?`, `SET col = ?`,
  a quoted column, and an unrecognised parameter left as given
- each size limit and its note; a truncated JSON structure becomes text
- masking happens in the constructor (a record built with a secret never holds it)

**Step tests** (`HttpStepsTest`, `RestStepsTest`, `DbStepsTest` or the existing database step
tests, `LdapStepsTest`):
- each step returns evidence on success and on failure
- HTTP: `Authorization` masked; a failed `rest.verify` keeps its response body; a connection
  error records the request and the error
- `db.verify`: more than 20 rows capped, `totalRows` correct
- LDAP: attributes written and read recorded, `userPassword` masked
- final failures (unknown target, malformed parameter) carry no evidence

**Engine:** only the last attempt's evidence is kept; a throwing step has none.

**Writers:** `JsonResultWriterTest` writes evidence (and omits it when null); `HtmlReportWriterTest`
renders each kind, opens it on a failed step, escapes `<script>` inside a body, and shows
`… N more rows`.

**Runner level:** the embedded corpus run's `scenario-report.html` and `scenario-results.json`
contain evidence for HTTP, database and LDAP steps, and none of the test credentials configured
for the embedded run appear in either file.

**Also:** the full suite, the corpus (0 FAILED), `ContainerScenarios` if Docker is available.

## 10. Out of scope

- SOAP request and response envelopes (follow-up ticket).
- Evidence for cleanup entries, or for earlier attempts of a `wait:` step.
- Configurable size limits.
- Binary bodies and binary LDAP attributes beyond their text form.
