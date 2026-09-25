# Generic `http.request` step — design

**Ticket:** ITF-22 (subtask of ITF-4). This is sub-project 1 of 4 toward a Postman/Karate-style
generic HTTP connector. The other three, each a later ticket, are listed in §9.

## 1. The idea

This follows OpenText Identity Validator's generic HTTP connector. The author writes the whole
request, the body travels as a **raw string**, and the response comes back as a **raw string**.
Checking then works the same way whatever the protocol.

A SOAP call is just HTTP: a `POST` whose body is an XML envelope, with `Content-Type: text/xml`
(or `application/soap+xml`) and a `SOAPAction` header. Once a step sends a raw string body, REST
and SOAP go through **the same step**.

## 2. What exists, and why it isn't enough

`rest.request` and `rest.verify` already take any method, a named target (base URL) plus a path,
and headers. They treat 4xx/5xx as data, and return `{status, headers, body}`. But
`IdmRestConnector.exchange`:

- **always sends JSON:** it forces `Content-Type: application/json` and `Accept: application/json`,
  and serialises a map body. There is no raw string, so no XML and no SOAP.
- **parses the response:** JSON becomes maps and lists, and anything else becomes raw text.

## 3. Decisions (jangulo)

1. **A new `http.request` step.** `rest.request` and `rest.verify` stay exactly as they are, as
   the JSON convenience steps. No scenario breaks.
2. **Named `target` + `path` only.** There is no absolute `url:`. Base URLs live in configuration
   per environment (`idm.test.rest.<target>.base-url`), the same scenario runs in dev, QA or prod,
   and guard 4 checks every target a run uses. A SOAP service is simply another target.
3. **It never fails on HTTP status.** Any status is captured, as in Postman. The status is always
   in the step message and the report, e.g. `POST https://…/role/service returned 500`, so a
   failed action is visible even though the step passed. Checking the status is the job of
   sub-project 3 (JavaScript assertions).

## 4. The step

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

**Parameters:**
- **Required:** `target`, `method` and `path`.
- **Optional:** `headers`, `body` and `as`. `wait:` is not accepted, because this is an action.
- **`method`:** `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD` or `OPTIONS`, in any letter case.
  Anything else fails the step.
- **`headers`:** a map of header name to string value. Non-string values are converted with
  `String.valueOf`, and a non-map value fails the step.
- **`body`:** it must be a **string**, usually a YAML block scalar (`|`). `${...}` and `js:{}` work
  inside it as in any parameter. A map or list fails the step with `http.request body must be a
  string sent as-is; to send a JSON object use rest.request`. If the value is absent or null, no
  body is sent.
- **`Content-Type`:** never inferred from the body. If there is a body and no `Content-Type`
  header (compared case-insensitively), it defaults to `text/plain; charset=UTF-8`, like
  Postman's raw mode.
- **Nothing is added behind the author's back:** no serialisation, and no content-specific
  `Accept`. If the author sets no `Accept` header, the neutral `Accept: */*` is sent. Otherwise
  Spring would add a value derived from its converters, such as `application/octet-stream`.
- **The request body** is encoded as UTF-8, unless the `Content-Type` header names another
  charset.

**Response**, bound by `as:` as a plain map:
- `status`: the HTTP status, as a number
- `headers`: a map of **lower-case** header name to a list of values, e.g.
  `resp.headers['content-type'][0]`. HTTP header names are case-insensitive, and lower-casing
  gives JavaScript one spelling to use.
- `body`: always the raw text, decoded with the response's `Content-Type` charset, or UTF-8 when
  none is given. An empty or missing body is `""`, never null. That way `as:` never binds a null
  where text is expected.

**Outcomes:**
- **Any HTTP status succeeds.** The message is `<METHOD> <url> returned <status>`.
- **A transport problem fails** (connection refused, timeout, DNS failure), with the connector's
  message. The target is resolved by the existing `RestTargets.resolveBaseUrl`, so an unknown or
  unconfigured target fails with its existing message.

## 5. The connector

`IdmRestConnector` gains one method, beside `exchange`:

```java
OperationResult<TextResponse> exchangeText(
        String method, String url, String body, Map<String, String> headers)

record TextResponse(int status, Map<String, List<String>> headers, String body)
```

- It uses the same Spring `RestClient` bean as `exchange`, and suppresses status handling in the
  same way, so every status arrives as data. It follows CLAUDE.md's rule: Spring's client is used
  directly, and there is no new HTTP code.
- The body is sent as bytes in the charset its `Content-Type` declares (`text/plain;
  charset=UTF-8` by default), so no message converter re-encodes it.
- The response is read raw with `RestClient.exchange(...)`: status, headers and body bytes, with no
  message converter or media-type parsing that a server's `Content-Type` could make throw. The body
  is decoded with the response's declared charset, parsed leniently: a missing or unparseable
  `Content-Type`, or an unsupported charset (for example a 404 with `charset=bogus-cs`), falls back
  to UTF-8 and never fails the step.
- Header names are normalised by `HeaderNames.normalize`. Names are lower-cased (`Locale.ROOT`),
  names that differ only in case are merged in encounter order, and HTTP/2 pseudo-headers such as
  `:status` (which the JDK client exposes) are dropped.
- Methods outside `IdmRestConnector.SUPPORTED_METHODS` (`GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS`)
  are refused before anything is sent.
- It never throws. A transport problem becomes a failed `OperationResult`, as in `exchange`.
- **One HTTP client.** Production and tests share `RestClientConfiguration.requestFactory()`: the
  JDK `HttpClient` (`JdkClientHttpRequestFactory`), with a 10 s connect timeout and a 30 s read
  timeout. `HttpURLConnection`, used before, rejects `PATCH` and drops a `GET`'s body. Tests that
  ran on a different client than production could not see either problem, or test the header
  lower-casing.
- `rest.*` (`exchange`) now uses the same header normalisation and method list; otherwise it is
  unchanged.

## 6. Engine and registration

- **A new `HttpRequestStep`** (`http.request`) in `idm-test-engine/.../steps`. It uses
  `RestTargets.resolveBaseUrl` and `IdmRestConnector.exchangeText`, with no Spring imports
  (`NoSpringDependencyTest`).
- **Registered** in `YamlScenarioTest.registry(...)`, which `RealEnvironmentScenarios` and
  `ContainerScenarios` reuse. The vocabulary grows to 13 step types.
- **Guard 4** (`RealEnvironmentScenarios.restTargets`) collects targets from steps whose type starts
  with `rest.` **or `http.`**, so an `http.request` against a placeholder target is refused before
  anything runs.

## 7. Testing

**Step tests** (new `HttpStepsTest`, against WireMock, in the style of `RestStepsTest`):
- a JSON `POST` with a raw string body arrives byte-for-byte, with the author's `Content-Type`
- a SOAP envelope `POST` with a `SOAPAction` header arrives intact, and the XML response comes back
  as raw text
- a 500 response: the step succeeds, the capture has `status` 500 and the body, and the message
  contains `returned 500`
- with no `Content-Type` header, the request carries `text/plain; charset=UTF-8`
- a map `body` fails with the message in §4
- response header names are lower-case
- a response in a non-UTF-8 charset (e.g. `ISO-8859-1` with `é`) is decoded correctly
- an empty response body is captured as `""`
- an unknown method fails
- a transport failure (a closed port) fails
- an unknown target fails with `RestTargets`' message

**Also:**
- **Registry and validator:** `http.request` is registered with its accepted parameters, and `wait:`
  on it is rejected at load time.
- **Guard 4:** `RealEnvironmentGuardsTest` gets a case where an `http.request` target is collected.
- **Falsification:** every refusal (map body, unknown method, default content type, lower-casing,
  `http.` in guard 4) is falsification-tested.
- **Live:** the full default suite. The corpus is unchanged, because this adds a step and changes
  none.

## 8. Documentation

- `docs/guides/scenario-authoring.md` gets a short `http.request` section with one REST and one SOAP
  example, the response shape, and the note that status is never a failure. Checking comes with
  sub-project 3.
- `scenarios-real/README.md` notes that a SOAP service used with `http.request` is configured as a
  target (`idm.test.rest.<name>.base-url`).

## 9. Out of scope: the next three sub-projects

1. **Authentication** from target settings (basic and OAuth). `RestProperties` has `username`,
   `password` and OAuth fields, but nothing wires them into `RestClient` today. Until then, a
   scenario can send its own `Authorization` header.
2. **JavaScript assertions against a response,** evaluated on each attempt and retryable with
   `wait:`. This is the Postman/Karate "check" half.
3. **Reading XML from JavaScript.** Rhino 1.8.0 has no E4X, and the sandbox blocks Java classes, so
   a SOAP response can't be parsed in `js:{}` today. This decision touches ITF-7's "no helper
   library" rule.

Also out of scope:
- **Absolute URLs** (decision 2).
- **Failing on status** (decision 3).
- **Multipart or binary bodies.**
- **Per-target timeouts:** the shared `RestClient` keeps its current timeouts.
