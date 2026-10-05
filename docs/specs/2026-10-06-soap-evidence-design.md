# SOAP envelopes as step evidence — design

**Ticket:** ITF-40. **Depends on:** ITF-37 (per-step evidence).

## 1. The problem

ITF-37 records what the HTTP, REST, database and LDAP steps send and receive. The SOAP steps
(`roles.grant`, `roles.revoke`, `resources.grant`, `resources.revoke`) record nothing, because the
envelopes stay inside JAX-WS. When a grant fails with "Role service error: Invalid name", the
author cannot see the request that was sent or the fault that came back.

## 2. Decisions (jangulo, 2026-10-06)

1. **Reuse `HttpEvidence`:** a SOAP call is an HTTP POST, so it is recorded as one (endpoint as
   URL, `SOAPAction` as the only request header, the envelopes as bodies, the status; a fault is
   a response, usually 500, with the fault envelope). It renders and masks as HTTP evidence
   already does; the JSON shows `"kind": "http"`. No new evidence kind.
2. **The connector keeps the last exchange per thread:** a JAX-WS handler copies the envelopes;
   the connector exposes `lastExchange()`; the step reads it after its call. Connector method
   signatures and `OperationResult` stay unchanged.

## 3. Components

### `SoapExchange` (new record, `com.idm.test.connector.soap.client`)

| Component | Meaning |
|---|---|
| `String endpoint` | the address the request went to |
| `String soapAction` | the `SOAPAction` sent, or null |
| `String requestEnvelope` | the outgoing envelope as text |
| `Integer status` | the HTTP status, or null when no response arrived |
| `String responseEnvelope` | the incoming envelope (a normal response or a fault), or null |
| `long durationMillis` | from sending to receiving, or to the failure |

The connector module has no engine dependency; the step converts a `SoapExchange` to
`HttpEvidence`.

### `SoapCapture` (new, package-private `SOAPHandler<SOAPMessageContext>`)

- **Outbound message:** records the endpoint (`BindingProvider.ENDPOINT_ADDRESS_PROPERTY`), the
  `SOAPAction` (`BindingProvider.SOAPACTION_URI_PROPERTY`, or the `SOAPAction` HTTP header when
  set), the envelope text (`SOAPMessage.writeTo`, UTF-8) and the start time.
- **Inbound message or fault:** records the status (`MessageContext.HTTP_RESPONSE_CODE`), the
  envelope text and the duration.
- **Never throws and never blocks the call:** a failure to read an envelope records
  `(could not read envelope: <exception class>)` in its place; `handleMessage`/`handleFault`
  always return `true`.
- **Records no HTTP headers,** so the HTTP Basic credentials (`idm.test.soap.username` /
  `password`, sent as a header) never enter the exchange.
- Holds the exchange in a `ThreadLocal`. JAX-WS calls are synchronous and run handlers on the
  calling thread, so the step that made the call reads its own exchange; `wait:` retries run on
  the same thread.

### `RoleServiceConnector` and `ResourceServiceConnector`

- Install a `SoapCapture` in the port's handler chain in the constructor.
- **Clear the slot at the start of every public operation,** so a call that fails before
  anything is sent (for example while building the request) leaves no exchange, rather than the
  previous call's.
- Expose `Optional<SoapExchange> lastExchange()`.

### The four steps

After the connector call, on success and on failure:
- read `connector.lastExchange()`; when present, build
  `new HttpEvidence("POST", endpoint, {"SOAPAction": [soapAction]} (or empty when null),
  requestEnvelope, status, null, responseEnvelope, durationMillis, error)`, where `error` is the
  connector's failure message when `status` is null, else null; attach it with `withEvidence`
- attach nothing when nothing was sent: no connector configured, an empty `roles` or
  `assignments` list, or no exchange recorded

Messages, pass/fail and `as:` captures are unchanged.

## 4. Masking and limits

All from `HttpEvidence` (ITF-37): secret-named XML elements (including CDATA and child elements)
and attributes in either envelope become `***`; the endpoint and `error` are URL-sanitised;
envelopes are cut at 16 384 characters. NetIQ Role and Resource service envelopes carry DNs,
reasons and results, which are not secret.

## 5. Report and results

No change to the report or the JSON writer: SOAP evidence is HTTP evidence. The summary reads,
for example, `POST /IDMProv/role/service → 200 · 140 ms`; a fault shows its status and the fault
envelope; an unreachable service shows `→ no response` and the error.

## 6. Documentation

- `docs/reference/writing-scenarios.md` §9 evidence table: a row for `roles.*` / `resources.*`
  (the endpoint, `SOAPAction`, request and response envelopes, status and time; the fault
  envelope on a fault; the request and the error when the service could not be reached).
- The capability matrix (the HTML report row) and the README: SOAP evidence included (ITF-40).

## 7. Testing

**Connector module** (`idm-connector-soap`, against WireMock):
- `lastExchange()` is empty before any call
- after a call: endpoint, `SOAPAction`, a request envelope containing the call's DN, status
  200 and the response envelope
- a fault response: status 500 and the fault envelope
- an unreachable endpoint: the request, no status, no response
- the next call replaces the exchange; a call that fails before sending clears it
- with `username`/`password` configured, neither value appears anywhere in the exchange

**`SoapStepsTest`** (engine, WireMock):
- `roles.grant`, `roles.revoke`, `resources.grant` and `resources.revoke` each return
  `HttpEvidence` with method `POST`, the endpoint, the `SOAPAction` and both envelopes
- a fault: the step fails with today's message and its evidence has status 500 and the fault
  envelope
- an unreachable service: evidence with no status and a non-blank `error`
- an empty `roles` list: no evidence
- a secret-named element in a stubbed response envelope is masked

**Also:** the full suite, the corpus (0 FAILED), `ContainerScenarios` if Docker is available.

## 8. Out of scope

- HTTP headers of SOAP calls other than `SOAPAction`.
- `getAssignedRoles` and other connector operations no step uses (they install the same handler
  and record an exchange, but nothing reads it).
- MTOM/attachments.
