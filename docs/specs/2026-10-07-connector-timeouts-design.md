# Apply the SOAP and REST timeouts, and remove unused settings — design

**Ticket:** ITF-43 (bug). **Found in:** the 2026-10-07 documentation audit (`5ff921f`).

## 1. The problem

Several documented settings are bound from configuration but never applied:

- `idm.test.soap.connection-timeout` / `read-timeout`: the Role and Resource service ports use the
  JAX-WS defaults, so a hung service can block a step for a long time.
- `idm.test.soap.message-logging`: never read.
- `idm.test.rest.<target>.connect-timeout` / `read-timeout`: every `rest.*` / `http.*` step shares
  one HTTP client fixed at 10 s connect / 30 s read.
- `idm.test.executor.max-retries`, `retry-delay-ms`, `rest-provision-timeout-seconds`: read only by
  `ConfigurationTest`.

**Correction to the audit:** `ldap-provision-timeout-seconds` and
`database-provision-timeout-seconds` are used by four Java workflow tests (through the `Duration`
getters); `configuration.md` wrongly says they are unused.

## 2. Decisions (jangulo, 2026-10-07)

1. **Remove `message-logging`.** JAX-WS's built-in dump is global and prints HTTP headers,
   including `Authorization: Basic …`; since ITF-40 the report and the JSON results already show
   each SOAP call's envelopes, masked.
2. **REST: one HTTP client per distinct (connect, read) timeout pair,** created when first needed.
3. **Remove the three unused executor keys.** The LDAP and database provisioning timeouts stay.

## 3. SOAP timeouts

- In `RoleServiceConnector` and `ResourceServiceConnector`, after `SoapCredentials.apply`, set the
  JAX-WS RI request-context properties `com.sun.xml.ws.connect.timeout` = `connection-timeout` and
  `com.sun.xml.ws.request.timeout` = `read-timeout` (milliseconds, `Integer`). Defaults stay
  10 000 and 30 000.
- **A non-positive value fails at startup** (connector construction), like half-set credentials:
  `idm.test.soap.read-timeout must be a positive number of milliseconds`.
- A timed-out call fails its step through the existing failure path
  (`roles.grant failed for …: …`); the ITF-40 evidence shows the request and the error.
- `message-logging` is removed from `SoapProperties`, from
  `idm-test-scenarios/src/test/resources/application.yml` (three places) and
  `idm-connector-soap/src/test/resources/application.yml`, and from the docs.

## 4. REST per-target timeouts

- **`Timeouts`** (new record, `com.idm.test.connector.rest.client`): `Duration connect`,
  `Duration read`; `Timeouts.DEFAULT` = 10 s / 30 s.
- **`RestClientConfiguration.requestFactory(Duration connect, Duration read)`:** a new overload of
  the existing JDK HTTP/1.1 factory; `requestFactory()` and `requestFactory(Duration read)` delegate
  to it.
- **`IdmRestConnector`:**
  - new overloads `exchange(method, url, body, headers, Timeouts)` and
    `exchangeText(method, url, body, headers, Timeouts)`; the existing methods use the client the
    connector was built with
  - a thread-safe cache, one `RestClient` per distinct `Timeouts`, built with
    `RestClient.builder().requestFactory(requestFactory(connect, read))`; `Timeouts.DEFAULT` (and
    `null`) use the client the connector was built with
- **Steps:** `rest.request`, `rest.verify`, `http.request` and `http.verify` pass their target's
  `connect-timeout` / `read-timeout` (`RestProperties.RestApiConfig`, milliseconds; a missing value
  takes the default).
  - A non-positive value is a final failure before anything is sent, with no evidence:
    `rest target 'idm' has a read-timeout that is not a positive number of milliseconds`.
  - A request that exceeds its read timeout fails like other connection errors (retried under
    `wait:`; evidence shows the request and the error).
- The OAuth token request keeps Spring Security's own client (out of scope).

## 5. Executor

Remove `max-retries`, `retry-delay-ms` and `rest-provision-timeout-seconds` (and their getters)
from `TestExecutorProperties`, from `application.yml`, from `ConfigurationTest` and from the
Javadoc and docs. Keep `default-timeout-seconds`, `default-poll-interval-seconds`,
`ldap-provision-timeout-seconds` and `database-provision-timeout-seconds`.

## 6. Documentation

- `docs/reference/configuration.md`:
  - SOAP: `connection-timeout` / `read-timeout` apply (defaults, positive, what a timeout does);
    the `message-logging` row and YAML line are removed
  - REST: per-target `connect-timeout` / `read-timeout` apply (defaults, positive, what a timeout
    does); the "not applied" note is removed
  - Executor: the provisioning timeouts are used by the Java workflow tests; the three keys are
    gone; the sentence claiming they are unused is corrected
- `docs/reference/writing-scenarios.md` §7 table: add `rest.<target>.connect-timeout`,
  `read-timeout` and `soap.connection-timeout`, `read-timeout`.

## 7. Testing

**SOAP** (`idm-connector-soap`, WireMock): with a stub delayed 2 s and `read-timeout: 500`,
`assignRoles` fails within 1.5 s with a timeout-related message; `grantResource` likewise; a
non-positive `read-timeout` or `connection-timeout` fails connector construction with the message.

**REST:**
- `IdmRestConnector`: a 500 ms read timeout against a 2 s stub fails within 1.5 s; the default
  client still succeeds against the same stub; equal `Timeouts` share one client, different ones
  get different clients (observable through a package-private accessor or behaviour)
- steps (`RestStepsTest`, `HttpStepsTest`): a target configured with `read-timeout: 500` fails fast
  against a slow stub while a default target succeeds; a target with `read-timeout: 0` is a final
  failure with no evidence

**Executor:** `ConfigurationTest` no longer asserts the removed keys; the build compiles with them
gone.

**Also:** the full suite and the corpus (0 FAILED).

## 8. Out of scope

- Timeouts for the OAuth token request, LDAP or database operations (their pools already have
  their own settings).
- A wall-clock limit for whole steps.
