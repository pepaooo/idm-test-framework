# `response.xml`: reading XML and SOAP responses in `http.verify` checks — design

**Ticket:** ITF-25, sub-project 4 of 4 of the generic HTTP connector (see
`docs/specs/2026-09-24-http-request-design.md` §9). ITF-24 settled the approach
(`docs/specs/2026-09-28-http-verify-design.md` §2.2): the engine parses the body in Java and
binds plain objects, so JavaScript stays helper-free (ITF-7's rule). This spec defines the
XML-to-object mapping and how `http.verify` exposes it.

## 1. The problem

`http.verify` binds `response.json` for JSON bodies, but a SOAP or XML body is only
`response.body`, raw text. JavaScript can't read it:
- **Rhino 1.8.0 ships no E4X.**
- **The sandbox's `ClassShutter` blocks every Java class**, so no DOM or XPath is reachable.

Today a check that reads `response.xml` sees `undefined`, fails with an ITF-25 hint, and that
failure is final (ITF-24's amendment).

## 2. Decisions (jangulo, 2026-09-28)

1. **Local names only.** Namespace prefixes and `xmlns` declarations are dropped.
   `response.xml.Envelope.Body.getUserResponse.status` works whatever prefixes the server picks.
2. **A single value when an element appears once, a list when it repeats.** A check that may
   see either uses plain JavaScript: `[].concat(x.role).includes('HR')`.
3. **Attributes are `@name` keys; `#text` holds the text of an element that also has
   attributes or child elements.** A plain leaf stays a string. Values are always strings.
4. **`response.xml` follows `response.json`'s lenient rule, plus a hint.** It's parsed only for
   an XML `Content-Type`, and is `null` otherwise or when malformed. A failing check that reads
   it while it's `null` says why. That failure is retried; ITF-24's final rule for
   `response.xml` is removed.

## 3. The mapping

A new engine class, `com.idm.test.engine.steps.XmlObjects`, with one entry point that turns XML
text into plain Java values (`LinkedHashMap`, `ArrayList`, `String`). It uses only the JDK's
`javax.xml.parsers`, so the engine stays Spring-free.

- **The root element is the top key:** the result is a one-entry map,
  `{ <root local name>: <root value> }`.
- **Elements are keyed by local name.** The parser is namespace-aware; prefixes and `xmlns` /
  `xmlns:*` attributes don't appear in the result.
- **Repeated siblings** with the same local name become a list, in document order, even if
  other elements sit between them. A single one is its value, not a one-item list.
- **An element's value:**
  - **no attributes and no child elements:** its trimmed text, a string. An empty element (or
    one with only whitespace) gives `""`.
  - **otherwise:** a map in document order holding:
    - `@<local name>` for each attribute (namespace declarations excluded). Two attributes
      with the same local name (from different namespaces) become a list, as elements do.
    - one key per child element's local name
    - `#text`: the element's own text nodes (not its children's), each trimmed, the non-empty
      ones joined with a single space. Present only when that text isn't empty.
- **Other content:**
  - CDATA counts as text.
  - Comments and processing instructions are ignored.
  - Whitespace-only text between elements is ignored.
- **Values are always strings.** `'42'` is never turned into `42`; `xsi:nil="true"` is simply
  `'@nil': 'true'`.

Example:

```xml
<user id="42">
  <status code="1">ACTIVE</status>
  <name>Ana</name>
  <role>Admin</role>
  <role>HR</role>
  <manager/>
</user>
```

becomes

```js
{ user: { '@id': '42',
          status: { '@code': '1', '#text': 'ACTIVE' },
          name: 'Ana',
          role: ['Admin', 'HR'],
          manager: '' } }
```

### Security

The `DocumentBuilderFactory` is configured so that XML can't reach outside the body:
- `http://apache.org/xml/features/disallow-doctype-decl` is `true`: any DOCTYPE is refused,
  which blocks XXE and entity expansion ("billion laughs").
- `FEATURE_SECURE_PROCESSING` is on; `ACCESS_EXTERNAL_DTD` and `ACCESS_EXTERNAL_SCHEMA` are
  empty.
- XInclude is off and entity references are not expanded.
- The JDK's `maxElementDepth` limit is set to 100 (the same depth the JavaScript sandbox
  accepts), so a deeply nested body can't exhaust the stack while it is mapped.
- A document with a DOCTYPE, or nested deeper than 100 elements, is therefore **malformed**,
  like any other parse error.

### Errors

Malformed input raises an `IllegalArgumentException` whose message is
`not well-formed XML at line <l>, column <c>: <parser message>` (line and column from the
`SAXParseException`; `at line …` is omitted when the parser gives none). The parser's own
error output is suppressed (a no-op `ErrorHandler`), so nothing is written to stderr.

## 4. `http.verify`

- **`response` gains `xml`.** The table in the reference becomes:

  | Key | Value |
  |---|---|
  | `status` | the HTTP status, a number |
  | `headers` | a map of lower-case header name to a list of values |
  | `body` | the body as text; `""` when empty, never null |
  | `json` | the parsed body for `application/json` or `+json`, when it parses; otherwise `null` |
  | `xml` | the parsed body (§3) for `text/xml`, `application/xml` or `+xml` (including SOAP 1.2's `application/soap+xml`), when it parses; otherwise `null` |

- **The media type** is read exactly as for `json`: the first `Content-Type` value, before
  `;`, trimmed, lower-cased. A blank body gives `xml: null`.
- **Parsing happens once per attempt**, when `response` is built, and only for an XML media
  type. The reason `xml` is `null` is kept for the hint below.
- **`as:`** binds the last attempt's `response`, now with five keys.

### The hint

When a check **fails** (returns `false` or throws) **and** its source contains `response.xml`
**and** `xml` is `null`, the failure message ends with one of:
- `(response.xml is null: Content-Type is <media type>)`, or
  `(response.xml is null: no Content-Type)` when the header is missing, or
  `(response.xml is null: the body is empty)` for a blank body
- `(response.xml is null: the body is <XmlObjects error>)`, e.g.
  `(response.xml is null: the body is not well-formed XML at line 3, column 7: …)`

The reason is passed through `TokenFailureText.safe(…, null)`, like every other body-derived
text. A check that fails while `xml` is non-null gets no hint.

### Outcomes (changes to ITF-24 §7)

- A failing check that reads `response.xml` is an **ordinary, retried** failure, like any other
  failing check. The ITF-24 amendment that made it final, and its "arrives with ITF-25" hint,
  are removed.
- Every other row of ITF-24's outcome table is unchanged, including the final failure for a
  non-boolean result.

## 5. Documentation

`docs/reference/writing-scenarios.md`, `http.verify` section:
- the `response` table gains the `xml` row (§4)
- a new "Reading XML" subsection: the rules of §3 in consultant terms, the example above, and
  the `[].concat(x.role)` idiom for an element that may appear once or several times
- the SOAP example checks `response.xml.Envelope.Body…` instead of `response.body`
- the outcome text drops the `response.xml` final rule and describes the hint

Also: the README's `http.verify` row and the capability matrix mention XML checks; the README's
"Next" table drops the finished ITF-10, ITF-24 and ITF-25 rows; `docs/overview.md` no longer
calls JavaScript checks and XML "planned". The authoring guide is unchanged (it points to the
reference).

## 6. Testing

**`XmlObjectsTest`** (plain unit tests, engine module):
- namespaces: prefixed and default-namespace elements are keyed by local name; `xmlns`
  attributes are absent
- a single element is a value; repeated siblings are a list in document order, including when
  interleaved with other elements
- attributes as `@name`; `#text` alongside attributes and alongside children; no `#text` when
  the text is empty
- empty and whitespace-only elements give `""`
- CDATA is text; comments and processing instructions are ignored
- values stay strings (`'42'`, `'true'`)
- a DOCTYPE, an XXE payload with an external entity, and an entity-expansion payload are each
  refused as malformed, and no file is read
- malformed XML gives the `line …, column …` message
- the example in §3 maps exactly as shown

**`HttpVerifyStepsTest`**, against WireMock:
- a SOAP 1.1 envelope (`text/xml`) passes a `response.xml.Envelope.Body…` check
- `application/soap+xml` and `application/xml` are parsed
- `text/html` gives `xml: null` and the Content-Type hint; `json` stays unaffected
- a missing Content-Type and an empty body give their hints
- malformed XML gives the not-well-formed hint and is retried: malformed then valid (WireMock
  scenario states) under `wait:` passes on attempt 2
- the ITF-24 test that asserted a final `response.xml` failure is replaced by one asserting it
  is retried
- a check that fails while `xml` is non-null has no hint
- `as:` binds `xml`

**Also:**
- `ScenarioReferenceTest` still passes.
- A falsification for each rule.
- The full suite and the corpus (0 FAILED).

## 7. Out of scope

- `xml` on `http.request`'s capture, and on `rest.*` steps.
- XPath or any other JavaScript helper.
- Namespace URIs in the result, and number or boolean conversion.
- Streaming or size limits beyond what the body already is in memory.
