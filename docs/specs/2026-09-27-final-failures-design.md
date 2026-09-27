# Final failures end a wait — design

**Ticket:** ITF-26 (follow-up to ITF-23).

## 1. The problem

A retryable step (`ldap.verify`, `db.verify`, `rest.verify`) under `wait:` is polled until it
passes or the timeout expires. Every failure means "not yet". Some failures can never change
by waiting, and polling them only burns the timeout and repeats the damage.

**The case that prompted the ticket:** a `rest.verify` whose target has a wrong OAuth client
secret. Nothing caches a failed token request, so every poll sends a new one. With the default
profile (5-minute timeout, 5-second poll) that is about 60 rejected token requests, which can
also trip an identity provider's lockout. Then the step fails.

The same waste applies to:
- a malformed `expect`
- an unknown target or database
- half-configured credentials

The retry loop is always bounded by `wait:`, so nothing hangs.

## 2. Decisions (jangulo, 2026-09-27)

1. **Scope: any permanent failure,** not only authentication. Each retryable step marks the
   failures that waiting cannot change.
2. **An API's own 401 or 403 keeps retrying.** It is a status mismatch like any other. In IdM,
   access often appears later, for example once a role grant has propagated. Only failures that
   happen before the request is sent, or a token the server rejected, stop the wait.

## 3. The signal: `StepOutcome.finalFailure`

- **The new component:** `StepOutcome` gains a fourth component, `boolean isFinal`, meaning
  waiting cannot change this failure. `isFinal` is meaningful only on a failure; a successful
  outcome always has `isFinal == false`.
- **Factories:**
  - `success(message)`, `success(message, value)` and `failure(message)` are unchanged, with
    `isFinal == false`.
  - The new `finalFailure(message)` returns a failure with `isFinal == true`.
  - The three-argument constructor stays, delegating with `isFinal == false`, so existing callers
    compile unchanged.
- **No step changes behaviour unless it opts in.** The engine stays Spring-free and does not
  depend on `idm-test-core`'s `ErrorType` policy. `ErrorType.isRetryable()` treats `LDAP_ERROR`
  and `DATABASE_ERROR` as non-retryable, which would wrongly end a wait for an entry that
  hasn't appeared yet.

## 4. The engine

In `ScenarioEngine`'s `waitUntil` supplier:

- **An attempt that ends in a final failure** returns `true`, meaning "stop waiting". `last`
  still records the outcome.
- **After the wait:**
  - The step's success is `last.success()`, **not** `waitUntil`'s return value. It already is,
    via `outcome.success()`, but the `passed` branch that picks the message must also check
    `outcome.success()`. Otherwise a final failure would be reported with a passing step's
    message shape.
  - **The message** for a final failure is `<outcome message> (not retried: waiting cannot
    change this)`, never `(last observed value after retrying)`.
  - `attempts` and `waitedMillis` are reported as today. A final failure on the first attempt
    shows `attempts: 1`.
- **A step without `wait:`** behaves exactly as today: a final failure is just a failure.
- **`TestExecutor` is unchanged.**

## 5. What each step marks as final

| Step | Final (`finalFailure`) | Still retried (`failure`) |
|---|---|---|
| `rest.verify` | a malformed `expect`, and every reason `rejectMalformedExpect` gives; an unknown or unconfigured target (`RestTargets`); authentication configuration errors; a token request the server rejected | connection errors and timeouts, to the API or the token endpoint; a 5xx from the token endpoint; any status or body mismatch, **including an API's 401 and 403** |
| `db.verify` | a malformed `expect` (`rejectMalformedExpect`); an unknown database (the connector's `CONFIGURATION_ERROR`) | SQL errors and connection problems (`DATABASE_ERROR` and others), because a table or row may appear later; result mismatches |
| `ldap.verify` | both or neither of `expect`/`exists`; a malformed `exists` or `expect` (the parameter-shape checks) | connection errors; entry missing or present; attribute mismatches |

**Action steps** (`rest.request`, `http.request` and the rest) never retry, so they keep
`failure`. The shared helpers they call (`RestTargets`, `RestAuthorization`) may return a final
failure; for an action that's indistinguishable from `failure`.

**How the REST steps learn it:**
- `RestTargets.Resolution` failures are always final.
- `RestAuthorization.apply` currently returns an error string. It changes to return a
  `StepOutcome`, or `null` when the headers are ready:
  - a final failure when the authenticator's `ErrorType` is `CONFIGURATION_ERROR` or
    `AUTHENTICATION_ERROR`
  - an ordinary failure otherwise

## 6. Classifying token failures in `RestAuthenticator`

Today every token failure is `AUTHENTICATION_ERROR`. It becomes:

| What happened | `ErrorType` | Final? |
|---|---|---|
| The token server answered **4xx**, including a 400 with an OAuth error body (`invalid_client`, …) and a 401 | `AUTHENTICATION_ERROR` | yes |
| The token server answered **5xx** | `SERVER_ERROR` | no |
| An I/O error or timeout reaching the token server | `NETWORK_ERROR`, or `TIMEOUT` when the cause chain holds an `HttpTimeoutException` or `SocketTimeoutException` | no |
| The configuration errors that exist today (half-set credentials, no or unknown registration, a non-client-credentials grant) | `CONFIGURATION_ERROR` | yes |

- **How it tells them apart:** by walking the `OAuth2AuthorizationException`'s cause chain.
  - An `HttpServerErrorException` means 5xx.
  - A `ResourceAccessException`, `ConnectException`, `UnknownHostException` or timeout exception
    means I/O.
  - Anything else, including no cause at all, means a rejection. This covers Spring's own
    parsing of a 400 OAuth error body, and an `HttpClientErrorException`.
  
  The plan confirms these cause types against Spring Security 6.5.5 with WireMock (a 401, a
  503, a closed port).
- **Messages are unchanged in form,** and still contain no secret.

## 7. Testing

**Engine** (`ScenarioEngineTest`, with a stub step):
- a final failure under `wait:` ends after exactly 1 attempt, fails the scenario, and its
  message ends with `(not retried: waiting cannot change this)`
- an ordinary failure still polls more than once
- a final failure after earlier ordinary failures stops at that attempt
- a final failure without `wait:` is an ordinary failed step
- `StepOutcome.success(...)` is never final

**Steps:** each "final" row in §5 gives exactly 1 attempt under `wait:`; each "still retried"
row gives more than 1. The fixtures are WireMock, H2 and embedded LDAP, as the existing step
tests use.
- **`rest.verify`:**
  - a token endpoint returning 401 → 1 token request, final
  - returning 503 → several token requests
  - a closed token port → several attempts
  - the API returning 401 while `expect` says 200 → several attempts (decision 2)
- **`db.verify`:** an unknown database → final; a missing table → retried.
- **`ldap.verify`:** both `expect` and `exists` → final; a missing entry → retried.

**`RestAuthenticator`:** a 401, a 400 `invalid_client`, a 503 and a closed port each give the
`ErrorType` in §6.

**Falsification:** each classification is flipped once, and a test must fail.

**Live:** the full default suite (0 skips) and the corpus (0 FAILED). The corpus uses no final
failures, so its behaviour is unchanged.

## 8. Documentation

- **`docs/guides/scenario-authoring.md` §5 (Waiting):** a short paragraph saying that failures
  waiting cannot fix (a malformed `expect`, an unknown target or database, rejected or
  misconfigured credentials) end the wait at once and are reported as "not retried". Everything
  else, including an API's 401 and 403, retries until the timeout.
- **The capability matrix's "Assertion waiting" row:** the same point in one line.

## 9. Out of scope

- Making `TestExecutor.waitUntil` itself aware of final failures. The engine's supplier is enough.
- Validating `expect` shapes at load time instead of at run time (a validator change).
- Marking failures final in action steps, which never retry.
- Treating an API's 401 or 403 as final (decision 2).
