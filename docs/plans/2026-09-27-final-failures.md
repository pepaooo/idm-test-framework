# Final Failures End a Wait Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A retryable step can mark a failure as final, and the engine then stops a `wait:` at once instead of polling until the timeout.

**Architecture:**
- **The signal:** `StepOutcome` gains an `isFinal` component and a `finalFailure(message)` factory.
- **The engine:** its `waitUntil` supplier treats a final failure as "stop" and reports it as "not retried".
- **The steps:** `ldap.verify`, `db.verify` and `rest.verify` mark their parameter-shape and configuration failures final.
- **Authentication:** `RestAuthenticator` classifies token-endpoint failures, so that a rejection (4xx) is final and a network problem or 5xx is retried.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Security OAuth2 Client 6.5.5, JUnit 5, AssertJ, WireMock, H2, UnboundID.

**Spec:** `docs/specs/2026-09-27-final-failures-design.md` (commit 3b05dde). **Ticket:** ITF-26.

## Global Constraints

- **`StepOutcome`:**
  - Its canonical shape is `record StepOutcome(boolean success, String message, Object value, boolean isFinal)`.
  - It keeps a 3-argument constructor `StepOutcome(boolean, String, Object)` that delegates with `isFinal = false`.
  - `success(...)` and `failure(...)` always give `isFinal == false`. `finalFailure(message)` gives `success == false, value == null, isFinal == true`.
- **The engine:**
  - It stays Spring-free: no `import org.springframework` under `idm-test-engine/src/main/java` (`NoSpringDependencyTest`).
  - It does not use `ErrorType.isRetryable()`.
- **The final-failure message suffix is exactly** ` (not retried: waiting cannot change this)`, with a leading space. The existing suffix ` (last observed value after retrying)` stays for ordinary failures that time out.
- **`TestExecutor` is not modified.**
- **An API's own 401/403 in `rest.verify` stays an ordinary, retried failure** (user decision).
- **Token failures in `RestAuthenticator`:**
  - 4xx, or no identifiable cause → `AUTHENTICATION_ERROR` (final)
  - 5xx → `SERVER_ERROR`
  - `HttpTimeoutException`/`SocketTimeoutException` in the cause chain → `TIMEOUT`
  - `ResourceAccessException`/`ConnectException`/`UnknownHostException` → `NETWORK_ERROR`
  
  The existing configuration errors stay `CONFIGURATION_ERROR` (final).
- **Messages never contain a password, secret, token or `Authorization` value** (ITF-23 rule).
- **Commits** end with a blank line, `ITF-26`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. "Unresolved compilation problems" means stale classes: run `./mvnw clean test`.
- **"Green"** means 0 failures, errors and skips.

---

### Task 1: `StepOutcome.finalFailure` and the engine's early stop

**Files:**
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/spi/StepOutcome.java`
- Modify: `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java` (the `waitUntil` block in `runStep`, around lines 150-200)
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java`

**Interfaces:**
- Produces:
  - `StepOutcome.finalFailure(String message)`
  - `boolean StepOutcome.isFinal()`
  - `new StepOutcome(boolean, String, Object)`, still valid

- [ ] **Step 1: Write the failing tests**

Add to `ScenarioEngineTest`. The helpers go next to `eventuallyPassing`:

```java
    /** Fails with ordinary failures {@code ordinary} times, then with a final failure. */
    private StepType finallyFailing(String name, int ordinary, AtomicInteger calls) {
        return new StepType() {
            public String name() { return name; }
            public boolean retryable() { return true; }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                return calls.getAndIncrement() < ordinary
                        ? StepOutcome.failure("not yet")
                        : StepOutcome.finalFailure("cannot ever pass");
            }
        };
    }

    @Test
    @DisplayName("A final failure under wait stops after one attempt and says it was not retried")
    void testFinalFailureStopsWait() {
        AtomicInteger calls = new AtomicInteger();
        ScenarioEngine engine = engineWith(finallyFailing("check", 0, calls));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait",
                        Map.of("timeout", "10s", "poll", "1s")))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.steps().get(0).attempts()).isEqualTo(1);
        assertThat(result.steps().get(0).waitedMillis()).isLessThan(5_000L);
        assertThat(result.steps().get(0).message())
                .isEqualTo("cannot ever pass (not retried: waiting cannot change this)");
    }

    @Test
    @DisplayName("A final failure after ordinary failures stops at that attempt")
    void testFinalFailureAfterOrdinaryFailures() {
        AtomicInteger calls = new AtomicInteger();
        ScenarioEngine engine = engineWith(finallyFailing("check", 2, calls));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait", Boolean.TRUE))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(result.steps().get(0).attempts()).isEqualTo(3);
        assertThat(result.steps().get(0).message()).endsWith("(not retried: waiting cannot change this)");
    }

    @Test
    @DisplayName("An ordinary failure under wait keeps polling and keeps the retry suffix")
    void testOrdinaryFailureStillPolls() {
        ScenarioEngine engine = engineWith(eventuallyPassing("check", Integer.MAX_VALUE));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x", "wait",
                        Map.of("timeout", "2s", "poll", "1s")))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).attempts()).isGreaterThan(1);
        assertThat(result.steps().get(0).message()).endsWith("(last observed value after retrying)");
    }

    @Test
    @DisplayName("A final failure without wait is an ordinary failed step")
    void testFinalFailureWithoutWait() {
        AtomicInteger calls = new AtomicInteger();
        ScenarioEngine engine = engineWith(finallyFailing("check", 0, calls));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("check", Map.of("dn", "x"))), "t.yaml");

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).message()).isEqualTo("cannot ever pass");
        assertThat(result.steps().get(0).attempts()).isNull();
    }

    @Test
    @DisplayName("Only finalFailure is final")
    void testOnlyFinalFailureIsFinal() {
        assertThat(StepOutcome.success("ok").isFinal()).isFalse();
        assertThat(StepOutcome.success("ok", 1).isFinal()).isFalse();
        assertThat(StepOutcome.failure("no").isFinal()).isFalse();
        assertThat(new StepOutcome(false, "no", null).isFinal()).isFalse();
        StepOutcome terminal = StepOutcome.finalFailure("never");
        assertThat(terminal.success()).isFalse();
        assertThat(terminal.value()).isNull();
        assertThat(terminal.isFinal()).isTrue();
    }
```

`testOrdinaryFailureStillPolls` uses `timeout: 2s, poll: 1s`, the same values `testAssertionTimeout` uses, which gives 2 attempts.

- [ ] **Step 2: Run the tests and confirm they fail to compile**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=ScenarioEngineTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `cannot find symbol: method finalFailure`.

- [ ] **Step 3: Implement `StepOutcome`**

Replace the record declaration and its factories with:

```java
public record StepOutcome(boolean success, String message, Object value, boolean isFinal) {

    /** An outcome that waiting may still change: every success, and an ordinary failure. */
    public StepOutcome(boolean success, String message, Object value) {
        this(success, message, value, false);
    }

    public static StepOutcome success(String message) {
        return new StepOutcome(true, message, null);
    }

    /** @param value what {@code as:} binds; may be null */
    public static StepOutcome success(String message, Object value) {
        return new StepOutcome(true, message, value);
    }

    public static StepOutcome failure(String message) {
        return new StepOutcome(false, message, null);
    }

    /**
     * A failure that waiting cannot change: a malformed parameter, an unknown target or
     * database, rejected or misconfigured credentials. Under {@code wait:} the engine stops at
     * once instead of polling until the timeout.
     */
    public static StepOutcome finalFailure(String message) {
        return new StepOutcome(false, message, null, true);
    }
}
```

Extend the record's Javadoc with one line for `isFinal`: `@param isFinal whether waiting cannot change this failure; always false on success`.

- [ ] **Step 4: Stop the wait in `ScenarioEngine`**

Add a constant near the top of the class:

```java
    private static final String NOT_RETRIED = " (not retried: waiting cannot change this)";
```

In the `waitUntil` supplier, replace `return outcome.success();` with:

```java
            // A final failure ends the wait: polling it again cannot change the answer.
            return outcome.success() || outcome.isFinal();
```

In the message selection after the wait, add a branch **before** `else if (passed)`:

```java
        } else if (outcome.isFinal()) {
            message = outcome.message() + NOT_RETRIED;
        } else if (passed) {
```

Leave `boolean success = outcome != null && outcome.success();` as it is. Success already comes from the outcome, not from `passed`.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='ScenarioEngineTest,NoSpringDependencyTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass, with 0 failures, errors and skips.

- [ ] **Step 6: Falsification**

Make each change below, re-run `ScenarioEngineTest`, confirm that the named tests fail, then revert:
- The supplier returns `outcome.success()` only. `testFinalFailureStopsWait` and `testFinalFailureAfterOrdinaryFailures` fail.
- Remove the `isFinal()` message branch. `testFinalFailureStopsWait` fails on the message, because it falls into `passed` and gets no suffix.

- [ ] **Step 7: Run the engine module and commit**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips.

```bash
git add idm-test-engine/src/main/java/com/idm/test/engine/spi/StepOutcome.java \
        idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java \
        idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java
git commit -m "feat: let a step mark a failure final so a wait stops at once

ITF-26
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Classify token-endpoint failures in `RestAuthenticator`

**Files:**
- Modify: `idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/RestAuthenticator.java` (the two `catch` blocks in `bearer`)
- Test: `idm-connector-rest/src/test/java/com/idm/test/connector/rest/client/RestAuthenticatorTest.java`

**Interfaces:**
- Produces: `authorization(...)` failures carry these `ErrorType`s:
  - `CONFIGURATION_ERROR`: unchanged
  - `AUTHENTICATION_ERROR`: the token server rejected the request
  - `SERVER_ERROR`: a 5xx from the token server
  - `NETWORK_ERROR`, `TIMEOUT`: the token server could not be reached

- [ ] **Step 1: Write the failing tests**

Add to `RestAuthenticatorTest`. `withRegistration` and `oauth` already exist there. Add this helper for a registration whose token URI you choose:

```java
    private static RestAuthenticator withTokenUri(String tokenUri) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("svc")
                .clientId("svc-client")
                .clientSecret("svc-secret-value")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokenUri)
                .build();
        return new RestAuthenticator(new InMemoryClientRegistrationRepository(registration), null);
    }

    @Test
    @DisplayName("A 5xx from the token server is a SERVER_ERROR, which may pass later")
    void testTokenServerError() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(503)));

        OperationResult<String> result = withRegistration("svc").authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getErrorType()).contains(ErrorType.SERVER_ERROR);
        assertThat(result.getMessage()).contains("'svc'").doesNotContain("svc-secret-value");
    }

    @Test
    @DisplayName("An unreachable token server is a NETWORK_ERROR, which may pass later")
    void testTokenServerUnreachable() {
        OperationResult<String> result = withTokenUri("http://localhost:1/oauth/token")
                .authorization("api", oauth("svc"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getErrorType()).contains(ErrorType.NETWORK_ERROR);
    }

    @Test
    @DisplayName("A 400 OAuth error from the token server is an AUTHENTICATION_ERROR")
    void testTokenRejected400IsAuthentication() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_client\"}")));

        OperationResult<String> result = withRegistration("svc").authorization("api", oauth("svc"));

        assertThat(result.getErrorType()).contains(ErrorType.AUTHENTICATION_ERROR);
    }
```

The existing `testTokenRejected` (401) already asserts `AUTHENTICATION_ERROR`, and must keep passing.

- [ ] **Step 2: Run the tests and confirm the new ones fail**

Run: `./mvnw test -pl idm-connector-rest -am -Dtest=RestAuthenticatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected:
- `testTokenServerError` and `testTokenServerUnreachable` fail: they get `AUTHENTICATION_ERROR`.
- `testTokenRejected400IsAuthentication` passes already.

- [ ] **Step 3: Confirm the cause chain before classifying**

Temporarily add `e.printStackTrace()` in both catch blocks, or put a breakpoint there. Run the two failing tests and note the exception types in each cause chain. The spec expects:
- **503:** `HttpServerErrorException`
- **Closed port:** `ResourceAccessException` wrapping `ConnectException`

If the chain differs, classify by the types actually seen and say so in your report. Remove the print before continuing.

- [ ] **Step 4: Implement the classification**

Add these imports to `RestAuthenticator`:

```java
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
```

In `bearer`, change the `ErrorType.AUTHENTICATION_ERROR` argument of **both** catch-block failures to `tokenFailureType(e)`. Leave the messages unchanged. Then add:

```java
    /**
     * Whether a failed token request may succeed if tried again. A token server that answered
     * with a rejection (any 4xx, including a 400 OAuth error Spring parsed itself) will answer
     * the same way next time: AUTHENTICATION_ERROR. A server error or an unreachable server may
     * recover: SERVER_ERROR, TIMEOUT or NETWORK_ERROR. Anything unidentified counts as a
     * rejection, so an unknown failure ends a wait rather than hammering the token server.
     */
    static ErrorType tokenFailureType(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return ErrorType.TIMEOUT;
            }
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpServerErrorException) {
                return ErrorType.SERVER_ERROR;
            }
            if (cause instanceof ResourceAccessException
                    || cause instanceof ConnectException
                    || cause instanceof UnknownHostException) {
                return ErrorType.NETWORK_ERROR;
            }
        }
        return ErrorType.AUTHENTICATION_ERROR;
    }
```

Update the class Javadoc sentence about failures to say that rejections and configuration errors are final, and server or network problems are not.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./mvnw test -pl idm-connector-rest -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips.

- [ ] **Step 6: Falsification**

Make each change below, re-run `RestAuthenticatorTest`, confirm that the named test fails, then revert:
- Remove the `HttpServerErrorException` branch. `testTokenServerError` fails.
- Remove `ResourceAccessException` and `ConnectException` from the network branch. `testTokenServerUnreachable` fails.
- Make the fallback `return ErrorType.NETWORK_ERROR;`. `testTokenRejected` fails.

- [ ] **Step 7: Commit**

```bash
git add idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/RestAuthenticator.java \
        idm-connector-rest/src/test/java/com/idm/test/connector/rest/client/RestAuthenticatorTest.java
git commit -m "feat: tell a rejected token request from an unreachable token server

ITF-26
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The retryable steps mark their final failures

**Files:**
- Modify:
  - `idm-test-engine/src/main/java/com/idm/test/engine/steps/LdapVerifyStep.java` (`rejectMalformedAssertion`)
  - `idm-test-engine/src/main/java/com/idm/test/engine/steps/DbVerifyStep.java` (`rejectMalformedExpect`, and the connector-failure branch in `execute`)
  - `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestVerifyStep.java` (`rejectMalformedExpect` and the target-resolution failure)
  - `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestAuthorization.java` (returns a `StepOutcome`)
  - `idm-test-engine/src/main/java/com/idm/test/engine/steps/RestRequestStep.java` and `HttpRequestStep.java` (their call to `RestAuthorization.apply`)
- Test:
  - `idm-test-engine/src/test/java/com/idm/test/engine/steps/LdapStepsTest.java`
  - `DbStepsTest.java`
  - `RestStepsTest.java`
  - `HttpAuthStepsTest.java`

**Interfaces:**
- Consumes:
  - Task 1: `StepOutcome.finalFailure(String)` and `StepOutcome.isFinal()`
  - Task 2: `RestAuthenticator` failures carry `CONFIGURATION_ERROR`/`AUTHENTICATION_ERROR` (final) or `SERVER_ERROR`/`NETWORK_ERROR`/`TIMEOUT` (not final)
- Produces: `static StepOutcome RestAuthorization.apply(String stepName, String target, RestProperties properties, RestAuthenticator authenticator, Map<String, String> headers)`, which returns `null` when the headers are ready and otherwise a failure.

- [ ] **Step 1: Write the failing tests**

`LdapStepsTest` (the `connector` field already exists):

```java
    @Test
    @DisplayName("A malformed ldap.verify is a final failure; a missing entry is not")
    void testLdapVerifyFinalFailures() {
        LdapVerifyStep verify = new LdapVerifyStep(connector);

        assertThat(verify.execute(Map.of("dn", "cn=x,ou=users", "expect", Map.of("cn", "x"), "exists", true), false)
                .isFinal()).isTrue();
        assertThat(verify.execute(Map.of("dn", "cn=x,ou=users"), false).isFinal()).isTrue();
        assertThat(verify.execute(Map.of("dn", "cn=x,ou=users", "exists", "yes"), false).isFinal()).isTrue();
        assertThat(verify.execute(Map.of("dn", "cn=x,ou=users", "expect", Map.of()), false).isFinal()).isTrue();

        StepOutcome missing = verify.execute(Map.of("dn", "cn=nobody-itf26,ou=users", "exists", true), false);
        assertThat(missing.success()).isFalse();
        assertThat(missing.isFinal()).isFalse();
    }
```

`DbStepsTest` (the `connector` and `DB` fields already exist):

```java
    @Test
    @DisplayName("A malformed db.verify or unknown database is final; a missing table is not")
    void testDbVerifyFinalFailures() {
        DbVerifyStep verify = new DbVerifyStep(connector);

        assertThat(verify.execute(Map.of("database", DB, "query", "SELECT 1", "expect", Map.of()), false)
                .isFinal()).isTrue();
        assertThat(verify.execute(Map.of("database", DB, "query", "SELECT 1", "expect", Map.of("bogus", 1)), false)
                .isFinal()).isTrue();
        assertThat(verify.execute(Map.of("database", "no-such-db", "query", "SELECT 1",
                "expect", Map.of("rowCount", 1)), false).isFinal()).isTrue();

        StepOutcome missingTable = verify.execute(Map.of("database", DB,
                "query", "SELECT * FROM not_created_yet", "expect", Map.of("rowCount", 1)), false);
        assertThat(missingTable.success()).isFalse();
        assertThat(missingTable.isFinal()).isFalse();
    }
```

Check the `db.verify` expect key names with `grep -n "ROW_COUNT =\|ROW =" idm-test-engine/src/main/java/com/idm/test/engine/steps/DbVerifyStep.java`, and use the real ones if they differ from `rowCount`.

`RestStepsTest`. `CONNECTOR` and `PROPERTIES` exist, and `idm` points at WireMock:

```java
    @Test
    @DisplayName("A malformed rest.verify or unknown target is final; a status mismatch, even 401, is not")
    void testRestVerifyFinalFailures() {
        RestVerifyStep verify = new RestVerifyStep(CONNECTOR, PROPERTIES, RestAuthenticator.withoutOAuth());

        assertThat(verify.execute(Map.of("target", "idm", "path", "/x", "expect", Map.of()), false)
                .isFinal()).isTrue();
        assertThat(verify.execute(Map.of("target", "nope", "path", "/x", "expect", Map.of("status", 200)), false)
                .isFinal()).isTrue();

        stubFor(get(urlEqualTo("/locked")).willReturn(aResponse().withStatus(401)));
        StepOutcome refused = verify.execute(Map.of("target", "idm", "path", "/locked",
                "expect", Map.of("status", 200)), false);
        assertThat(refused.success()).isFalse();
        assertThat(refused.isFinal()).isFalse();
    }
```

`HttpAuthStepsTest`. It has `CONNECTOR`, `PROPERTIES` with the targets `basic`/`oauth`/`open`/`half`, `CLIENT_SECRET`, `WIREMOCK`, `freshAuthenticator()`, and a `@BeforeEach` that stubs `/thing` 200 and a token 200. Add these imports:
- `com.idm.test.core.engine.TestExecutor`
- `com.idm.test.engine.FixtureFactory`
- `com.idm.test.engine.ScenarioEngine`
- `com.idm.test.engine.expr.ExpressionEvaluator`
- `com.idm.test.engine.model.Scenario`
- `com.idm.test.engine.model.StepSpec`
- `com.idm.test.engine.result.ScenarioResult`
- `com.idm.test.utils.data.TestDataGenerator`
- `java.time.Duration`

Then add:

```java
    private static ScenarioResult runVerifyUnderWait(RestAuthenticator authenticator) {
        RestVerifyStep verify = new RestVerifyStep(CONNECTOR, PROPERTIES, authenticator);
        ScenarioEngine engine = new ScenarioEngine(Map.of(verify.name(), verify),
                new FixtureFactory(new TestDataGenerator()), new TestExecutor(),
                Duration.ofSeconds(2), Duration.ofMillis(200), new ExpressionEvaluator());
        return engine.run(new Scenario("wait", null, Map.of(), List.of(new StepSpec("rest.verify",
                Map.of("target", "oauth", "path", "/thing", "expect", Map.of("status", 200),
                        "wait", Map.of("timeout", "2s", "poll", "1s"))))), "wait.yaml");
    }

    @Test
    @DisplayName("Under wait, a rejected token request stops after one attempt and one token request")
    void testRejectedTokenStopsWait() {
        WIREMOCK.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(401)));

        ScenarioResult result = runVerifyUnderWait(freshAuthenticator());

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).attempts()).isEqualTo(1);
        assertThat(result.steps().get(0).message())
                .endsWith("(not retried: waiting cannot change this)")
                .doesNotContain(CLIENT_SECRET);
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
        WIREMOCK.verify(0, getRequestedFor(urlEqualTo("/thing")));
    }

    @Test
    @DisplayName("Under wait, a token server returning 503 is retried")
    void testTokenServerErrorRetried() {
        WIREMOCK.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(503)));

        ScenarioResult result = runVerifyUnderWait(freshAuthenticator());

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).attempts()).isGreaterThan(1);
        assertThat(result.steps().get(0).message()).endsWith("(last observed value after retrying)");
    }

    @Test
    @DisplayName("A half-configured target is a final failure in rest.verify")
    void testHalfConfiguredIsFinal() {
        RestVerifyStep verify = new RestVerifyStep(CONNECTOR, PROPERTIES, freshAuthenticator());

        StepOutcome outcome = verify.execute(Map.of("target", "half", "path", "/thing",
                "expect", Map.of("status", 200)), false);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.isFinal()).isTrue();
    }
```

`Scenario` has a 4-argument constructor `(name, description, fixtures, steps)`; check with `grep -n "public Scenario(" idm-test-engine/src/main/java/com/idm/test/engine/model/Scenario.java`. The `@BeforeEach` token stub returns 200; `stubFor` on the same URL replaces it for the test, because WireMock picks the most recently added matching stub.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='LdapStepsTest,DbStepsTest,RestStepsTest,HttpAuthStepsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected:
- the `isFinal().isTrue()` assertions fail
- `testRejectedTokenStopsWait` fails on `attempts`
- `testTokenServerErrorRetried` passes already

- [ ] **Step 3: Mark the parameter-shape failures final**

- In `LdapVerifyStep.rejectMalformedAssertion`, change every `StepOutcome.failure(` to `StepOutcome.finalFailure(`. That is every return in the method.
- In `DbVerifyStep.rejectMalformedExpect`, change every `StepOutcome.failure(` to `StepOutcome.finalFailure(`.
- In `RestVerifyStep.rejectMalformedExpect`, change every `StepOutcome.failure(` to `StepOutcome.finalFailure(`. These include the `vacuous` branch.

Do **not** change any other `failure(` in these three files except the two below.

- [ ] **Step 4: Mark an unknown database and an unknown target final**

In `DbVerifyStep.execute`, add `import com.idm.test.core.model.ErrorType;` and replace the connector-failure branch with:

```java
        if (!result.isSuccess()) {
            String message = "db.verify failed on '" + database + "': " + result.getMessage();
            // An unknown database never becomes known by waiting; a SQL error might (a table or
            // row that provisioning has not created yet), so only configuration errors are final.
            return result.getErrorType().filter(ErrorType.CONFIGURATION_ERROR::equals).isPresent()
                    ? StepOutcome.finalFailure(message)
                    : StepOutcome.failure(message);
        }
```

In `RestVerifyStep.execute`, change the resolution failure to:

```java
        if (resolution.isFailure()) {
            return StepOutcome.finalFailure(resolution.error());
        }
```

- [ ] **Step 5: `RestAuthorization` returns a `StepOutcome`**

Replace `apply` with the following, and add `import com.idm.test.core.model.ErrorType;` and `import com.idm.test.engine.spi.StepOutcome;`:

```java
    /**
     * Call only after {@link RestTargets#resolveBaseUrl} succeeded, so the target exists.
     *
     * @return null when {@code headers} is ready to send; otherwise a failure naming the step,
     * final when waiting cannot change it (misconfigured or rejected credentials)
     */
    static StepOutcome apply(String stepName, String target, RestProperties properties,
                             RestAuthenticator authenticator, Map<String, String> headers) {
        if (headers.keySet().stream().anyMatch(AUTHORIZATION::equalsIgnoreCase)) {
            return null;
        }
        OperationResult<String> result = authenticator.authorization(target, properties.getRest().get(target));
        if (result.isFailure()) {
            String message = stepName + " could not authenticate: " + result.getMessage();
            ErrorType type = result.getErrorType().orElse(ErrorType.UNKNOWN_ERROR);
            return type == ErrorType.CONFIGURATION_ERROR || type == ErrorType.AUTHENTICATION_ERROR
                    ? StepOutcome.finalFailure(message)
                    : StepOutcome.failure(message);
        }
        result.getData().ifPresent(value -> headers.put(AUTHORIZATION, value));
        return null;
    }
```

In `RestRequestStep`, `RestVerifyStep` and `HttpRequestStep`, replace the existing block

```java
        String authError = RestAuthorization.apply(..., headers);
        if (authError != null) {
            return StepOutcome.failure(authError);
        }
```

with the step's own name:

```java
        StepOutcome authFailure = RestAuthorization.apply("rest.request", target, properties, authenticator, headers);
        if (authFailure != null) {
            return authFailure;
        }
```

Use `"rest.verify"` and `"http.request"` in the other two.

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips. The existing `HttpAuthStepsTest.testConfigurationErrorFails` and `testTokenRejectedFails` still pass: their messages are unchanged.

- [ ] **Step 7: Falsification**

Make each change below, re-run the four step test classes, confirm that the named test fails, then revert:
- In `RestAuthorization`, drop `AUTHENTICATION_ERROR` from the final condition. `testRejectedTokenStopsWait` fails.
- In `DbVerifyStep`, make every connector failure final. `testDbVerifyFinalFailures` fails on the missing table.
- In `RestVerifyStep`, revert the resolution failure to `failure`. `testRestVerifyFinalFailures` fails.
- In `LdapVerifyStep`, revert the "neither supplied" branch to `failure`. `testLdapVerifyFinalFailures` fails.

- [ ] **Step 8: Run the full suite and commit**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS, 0 failures, errors and skips. `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`. Report the total from `grep -h "tests=" */target/surefire-reports/TEST-*.xml | sed -E 's/.*tests="([0-9]+)".*/\1/' | paste -sd+ - | bc`.

```bash
git add idm-test-engine
git commit -m "feat: end a wait on failures that waiting cannot change

ITF-26
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Documentation

**Files:**
- Modify:
  - `docs/guides/scenario-authoring.md` (§5 Waiting)
  - `docs/architecture/capabilities.md` (the "Assertion waiting" row)

- [ ] **Step 1: The authoring guide**

In `docs/guides/scenario-authoring.md` §5, append this paragraph after the one that ends "…or impose a hard deadline on a blocking protocol call.":

```markdown
Some failures cannot change by waiting, and they end the wait at once instead of polling until the timeout. The step's message ends with `(not retried: waiting cannot change this)`. They are:

- **a malformed assertion:** an empty or misspelled `expect`, or both or neither of `expect`/`exists`
- **a name that isn't configured:** an unknown REST target or database
- **bad credentials:**
  - credentials that are only half configured
  - an OAuth registration that doesn't exist
  - a token request the token server rejected

Everything else retries until the timeout. That includes a missing entry, row or table; a connection error; a token server that is down; and an API answering 401 or 403, because access often appears once a role grant has propagated.
```

- [ ] **Step 2: The capability matrix**

In `docs/architecture/capabilities.md`, replace the "Assertion waiting" row's limitation cell (`Parameters resolve once before polling; actions cannot declare `wait`. Duration parsing does not enforce positive values`) with:

```markdown
Parameters resolve once before polling; actions cannot declare `wait`. Failures waiting cannot change (malformed assertions, unknown targets or databases, misconfigured or rejected credentials) end the wait at once (ITF-26). Duration parsing does not enforce positive values
```

- [ ] **Step 3: Commit**

```bash
git add docs/guides/scenario-authoring.md docs/architecture/capabilities.md
git commit -m "docs: describe failures that end a wait early

ITF-26
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
