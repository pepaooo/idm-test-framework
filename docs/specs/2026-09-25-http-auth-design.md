# HTTP authentication from target settings — design

**Ticket:** ITF-23 (subtask of ITF-4). This is sub-project 2 of 4 toward the generic HTTP connector
(see `docs/specs/2026-09-24-http-request-design.md` §9).

## 1. The problem

`RestProperties.RestApiConfig` has `username`, `password`, `use-oauth` and `oauth-client-name`,
and `application.yml` declares OAuth2 client-credentials registrations (`sap`, `workday`,
`servicenow`). **None of it reaches a request.** `IdmRestConnector` sends no `Authorization`
header, so a `rest.*` or `http.request` step against a protected API only works if the scenario
writes the header itself, with the secret in the YAML.

`idm.test.soap.username` and `password` have the same problem: `RoleServiceConnector` and
`ResourceServiceConnector` set only the endpoint address on their JAX-WS ports.

## 2. Decisions (jangulo, 2026-09-25)

1. **The step's own header wins.** If a step sets `Authorization` (in any letter case), the
   target's authentication is not applied to that request. Negative tests such as "bad
   credentials → 401" stay possible.
2. **SOAP basic authentication is in scope** for `roles.*` and `resources.*`.
3. **A real-environment credentials guard (guard 5)** refuses placeholder or incomplete
   credentials before any scenario runs.

## 3. How a target authenticates

A target's settings decide, in this order:

| Settings | Header sent |
|---|---|
| `use-oauth: true` | `Authorization: Bearer <token>`, a client-credentials token for the `oauth-client-name` registration |
| `username` and `password` both set (non-blank) | `Authorization: Basic base64(username:password)`, UTF-8 |
| neither set | none; the target is unauthenticated, which is legitimate for public APIs |

**Configuration errors** fail the step before anything is sent, with a message that names the
target and what to fix:
- only one of `username` and `password` is set
- `use-oauth: true` with no `oauth-client-name`, or a name that is not a
  `spring.security.oauth2.client.registration` entry
- the token request fails (connection error, `invalid_client`, …). The message names the
  registration and the OAuth error code or cause.

`application.yml`'s `sap`, `workday` and `servicenow` targets say in a comment that they use OAuth,
but they don't set `use-oauth`. They gain `use-oauth: true` and `oauth-client-name: <same name>`,
so the configuration does what its comments say.

## 4. Components

### 4.1 `RestAuthenticator` (new, `idm-connector-rest`)

- It is a bean, built in `RestClientConfiguration`.
- **Its method:** it takes a target name and its `RestApiConfig`, and returns one of three results:
  - a header value
  - "none"
  - a failure message
  
  It never throws. The plan fixes the exact signature; it uses `OperationResult`, as the
  connectors do.
- **Its public API exposes no Spring types,** so the engine can call it and stay Spring-free
  (`NoSpringDependencyTest`).
- **OAuth uses Spring Security, not custom token code** (CLAUDE.md):
  - an `AuthorizedClientServiceOAuth2AuthorizedClientManager`, which works outside a servlet
    request
  - built from the `ClientRegistrationRepository` and `OAuth2AuthorizedClientService`
  - with a client-credentials provider
  - Spring caches the token and fetches a new one when it expires. The principal is the target
    name.
  
  If Spring Boot doesn't auto-configure the registration repository in this non-web context, the
  configuration builds it from `OAuth2ClientProperties` (Boot's `OAuth2ClientPropertiesMapper`).
  The plan confirms which case applies.
- **When no registrations are configured,** OAuth targets fail with "no OAuth2 client registrations
  are configured", and basic and unauthenticated targets still work.

### 4.2 Steps (`idm-test-engine`)

- **Constructors:** `RestRequestStep`, `RestVerifyStep` and `HttpRequestStep` take the
  `RestAuthenticator` as a third constructor argument.
- **Before sending:**
  - If the step's headers contain `Authorization`, compared case-insensitively, the authenticator
    is **not called**. No token is fetched for a request that won't use it.
  - Otherwise, the step asks the authenticator:
    - a header value is added as `Authorization`
    - "none" adds nothing
    - a failure fails the step with the authenticator's message
- **`rest.verify`** asks on every attempt. OAuth tokens come from Spring's cache, so retries don't
  fetch a token each time.
- **The added header is not a step parameter,** so it never appears in resolved parameters, the
  step message, or `scenario-results.json`.

### 4.3 Registration

`YamlScenarioTest.registry(...)` gains a `RestAuthenticator` parameter, and `YamlScenarioTest`,
`RealEnvironmentScenarios` and `ContainerScenarios` (through its superclass) pass the bean.
`BaseIntegrationTest` autowires it.

### 4.4 SOAP (`idm-connector-soap`)

When `idm.test.soap.username` and `password` are both non-blank, `RoleServiceConnector` and
`ResourceServiceConnector` set them on their ports, as `BindingProvider.USERNAME_PROPERTY` and
`PASSWORD_PROPERTY`, next to the endpoint address. JAX-WS then sends HTTP basic authentication.
When neither is set, nothing is added. When only one is set, the connector fails at construction
with a message naming the missing property.

## 5. Secrets never leak

Passwords, client secrets, tokens and `Authorization` values never appear in:
- step messages or failure messages
- `scenario-results.json`
- log output from these components

Error messages name the target, the registration and the variable to set, never a value. A header
the **author** writes in YAML is an ordinary parameter and is reported like any other; the
authoring guide already says resolved parameters are not redacted, and it will point to target
settings as the way to keep secrets out of reports.

## 6. Guard 5: credentials (`RealEnvironmentScenarios`)

It runs after guard 4, against the same selected scenarios, before anything is executed.

**REST/HTTP.** For each target collected by `restTargets` (from `steps:` and `cleanup:`), it
refuses:
- a username or password still equal to `application.yml`'s placeholder (`idmadmin` / `idmpass`
  for `idm`)
- only one of the two set
- `use-oauth` with no `oauth-client-name`, or with an unknown registration
- a registration whose `client-id` or `client-secret` is still `application.yml`'s placeholder:
  - `sap-test-client` / `sap-test-secret`
  - `workday-client` / `workday-secret`
  - `snow-client` / `snow-secret`

An unauthenticated target (nothing set) passes.

**SOAP.** Only when the selected scenarios use a `roles.*` or `resources.*` step, it refuses a
SOAP username or password equal to its placeholder (`idmadmin` / `idmpass`), or only one of them
set.

**Messages** name the property and the environment variable that overrides it in every profile:
- `IDM_TEST_REST_<TARGET>_USERNAME` / `_PASSWORD`, formed as guard 4 forms `_BASEURL`
- `IDM_TEST_SOAP_USERNAME` / `_PASSWORD`
- `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<NAME>_CLIENTID` / `_CLIENTSECRET`

Messages never contain the value. Targets are checked in sorted order, so the first refusal is
deterministic.

**Pinning:** the placeholder tables are pinned to `application.yml` by `RealEnvironmentGuardsTest`,
as guards 2 and 4's are.

**`ContainerScenarios`:** 4 corpus files use `roles.*`/`resources.*`, and the corpus uses no
`rest.*` or `http.request`. `ContainerScenarios.publish` therefore sets non-placeholder
`idm.test.soap.username` / `password` for its WireMock SOAP stubs, which ignore authentication.

## 7. Testing

**`RestAuthenticator`** (in `idm-connector-rest`; OAuth against a WireMock token endpoint):
- basic: exact header value, including a non-ASCII password encoded as UTF-8
- unauthenticated → none
- username without password, and password without username → failure naming the target
- OAuth → `Bearer <stubbed token>`. The token endpoint receives a client-credentials grant, and two
  calls hit it **once** (caching).
- `use-oauth` with no name, or an unknown registration → failure
- the token endpoint returns 401 `invalid_client` → failure whose message names the registration
  and contains no secret

**Steps** (`RestStepsTest`, `HttpStepsTest`, against WireMock):
- a basic-auth target → WireMock receives the header, for `rest.request`, `rest.verify` and
  `http.request`
- an OAuth target → WireMock receives `Bearer <token>`
- the step sets `authorization: Basic bad` (lower-case name) → that header is sent alone, and the
  authenticator is not called (no token request)
- an unauthenticated target → no `Authorization` header
- a configuration error fails the step, and neither its message nor the step's captured result
  contains the password

**SOAP:** `RoleServiceConnectorTest` and `ResourceServiceConnectorTest` check that WireMock
receives `Authorization: Basic …` when credentials are set, and none when they aren't.

**Guard 5** (`RealEnvironmentGuardsTest`):
- every refusal above, and the message names the variable and not the value
- the positive cases: real credentials, an unauthenticated target, and no SOAP steps with
  placeholder SOAP credentials
- targets in `cleanup:` are checked
- placeholder pinning to `application.yml`

**Falsification:** every refusal and header rule is falsification-tested.

**Live:**
- the full default suite, with 0 skips
- `ContainerScenarios`, with guard 5 passing on the published SOAP credentials
- the corpus, with 0 FAILED

## 8. Documentation

- **`docs/guides/scenario-authoring.md`:** a short "Authentication" note under `http.request`:
  - the table in §3
  - the step's-header-wins rule
  - keep secrets in target settings, not YAML
- **`scenarios-real/README.md`:** which variables to set per target and for SOAP, and guard 5's
  refusals.
- **`application.yml`:** the `use-oauth` / `oauth-client-name` additions (§3).

## 9. Out of scope

- OAuth grant types other than client credentials, and per-scenario or per-step credentials,
  apart from writing the header (decision 1)
- API keys in custom headers, mutual TLS, digest or NTLM
- per-target timeouts; `connect-timeout` and `read-timeout` stay unwired
- refreshing a token after a 401. The token's expiry is Spring's only trigger.
- the other two sub-projects: JavaScript assertions against a response (ITF-24), and reading XML
  from `js:{}` (ITF-25)
