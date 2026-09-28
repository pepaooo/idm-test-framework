# http.verify Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A retryable `http.verify` step whose `check:` JavaScript conditions are evaluated against each attempt's `response`.

**Architecture:**
- **The engine** gains *deferred parameters*. A step declares parameter names the engine must not resolve up front. The engine hands the step a `DeferredExpression` for each, which evaluates against the scenario's names plus bindings the step supplies (`response`).
- **The validator** checks deferred parameters: each must be a `js:{}` expression, and the step's bindings count as known names.
- **`HttpVerifyStep`** shares request preparation with `HttpRequestStep` through a new `HttpExchange` helper.
- **Registration and docs** land together, because the ITF-10 drift test requires a reference section for every registered step.

**Tech Stack:** Java 21, Rhino 1.8.0 (the existing `ExpressionEvaluator`), Jackson (already on the engine's classpath), JUnit 5, AssertJ, WireMock.

**Spec:** `docs/specs/2026-09-28-http-verify-design.md` (commit efec0f0). **Ticket:** ITF-24.

## Global Constraints

- **The engine stays Spring-free.** No `import org.springframework` under `idm-test-engine/src/main/java` (`NoSpringDependencyTest`).
- **`StepType` gains two methods,** both defaulting to empty: `default Set<String> deferredParams()` and `default Set<String> deferredBindings()`. No existing step overrides them except `HttpVerifyStep`.
- **`DeferredExpression`** (`com.idm.test.engine.spi`) has exactly `String source()` and `Object evaluate(Map<String, Object> bindings)`. Bindings win over scope names.
- **Results record a deferred parameter as its source text,** or a list of source texts, never an evaluated value.
- **Validation messages, exactly:**
  - `'check' must be js:{ … } expressions; got "<value>"`, with the parameter name substituted
  - `'check' needs at least one condition`

  Both are located by the existing `ValidationException(sourceName, index, stepType, message)`.
- **`http.verify`:**
  - required: `target`, `method`, `path`, `check`
  - also accepted: `headers`, `body`, `wait`
  - `retryable() == true`
  - `deferredParams() == {"check"}`
  - `deferredBindings() == {"response"}`
- **`response` keys:** `status` (int), `headers` (lower-case → list), `body` (text, never null), `json` (the parsed body when the `Content-Type` media type is `application/json` or ends in `+json` and the body parses; otherwise `null`). There is no `xml` key.
- **http.verify messages, exactly** (`<where>` = `http.verify <METHOD> <url>`, `<ctx>` = ` (status <s>, body: <TokenFailureText.safe(body, null)>)`):
  - success: `<METHOD> <url> passed <n> check(s) (status <s>)`
  - a false check: `<where>: check <i> of <n> is false: <source><ctx>`
  - a check that throws: `<where>: check <i> of <n> failed: <error message><ctx>`
  - a non-boolean result, as a **final** failure: `<where>: check <i> of <n> must return true or false, got <value>`, where text is shown in double quotes and `null` as `null`
  - when a failing check's source contains `response.xml`, append: ` (response.xml is not available yet: it arrives with ITF-25; check response.body for now)`
- **Final failures,** in `HttpExchange` for both HTTP steps: an unsupported method, non-map `headers`, non-text `body`, an unknown or unconfigured target. `RestAuthorization` keeps its existing classification.
- **Commits** end with a blank line, `ITF-24`, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Build commands:** always pair `-pl` with `-am`; single-test runs add `-Dsurefire.failIfNoSpecifiedTests=false`. "Unresolved compilation problems" means stale classes: run `./mvnw clean test`. Green means 0 failures, errors and skips.

---

### Task 1: Deferred parameters in the engine and validator

**Files:**
- Create:
  - `idm-test-engine/src/main/java/com/idm/test/engine/spi/DeferredExpression.java`
  - `idm-test-engine/src/main/java/com/idm/test/engine/ScopedExpression.java`
- Modify:
  - `idm-test-engine/src/main/java/com/idm/test/engine/spi/StepType.java`
  - `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioEngine.java` (`runStep`: parameter resolution and every `new StepResult(...)` that passes `resolved`)
  - `idm-test-engine/src/main/java/com/idm/test/engine/ScenarioValidator.java` (the step loop, around lines 190-193)
- Test:
  - `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioEngineTest.java`
  - `idm-test-engine/src/test/java/com/idm/test/engine/ScenarioValidatorTest.java`

**Interfaces:**
- Produces:
  - `StepType.deferredParams()` and `StepType.deferredBindings()`
  - `DeferredExpression` with `source()` and `evaluate(Map)`
  - Engine behaviour: a deferred parameter arrives in `execute`'s params as a `DeferredExpression`, or a `List<DeferredExpression>` when written as a list.

- [ ] **Step 1: Write the failing tests**

In `ScenarioEngineTest`, add:

```java
    /** An assertion whose 'check' is deferred and sees 'attempt' as a binding. */
    private StepType deferredProbe(List<Object> seen) {
        AtomicInteger attempt = new AtomicInteger();
        return new StepType() {
            public String name() { return "probe.verify"; }
            public boolean retryable() { return true; }
            public Set<String> requiredParams() { return Set.of("check"); }
            public Set<String> deferredParams() { return Set.of("check"); }
            public Set<String> deferredBindings() { return Set.of("attempt"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                seen.add(params.get("check"));
                DeferredExpression check = (DeferredExpression) params.get("check");
                Object passed = check.evaluate(Map.of("attempt", attempt.incrementAndGet()));
                return Boolean.TRUE.equals(passed) ? StepOutcome.success("ok") : StepOutcome.failure("not yet");
            }
        };
    }

    @Test
    @DisplayName("A deferred parameter reaches the step unevaluated and is evaluated each attempt with scope and bindings")
    void testDeferredParameter() {
        List<Object> seen = new ArrayList<>();
        ScenarioEngine engine = engineWith(deferredProbe(seen));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("set", Map.of("target", 3)),
                new StepSpec("probe.verify", Map.of("check", "js:{ attempt >= target }", "wait", Boolean.TRUE))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat(result.steps().get(1).attempts()).isEqualTo(3);
        assertThat(seen).allSatisfy(value -> assertThat(value).isInstanceOf(DeferredExpression.class));
        assertThat(((DeferredExpression) seen.get(0)).source()).isEqualTo("js:{ attempt >= target }");
        assertThat(result.steps().get(1).resolvedParams().get("check")).isEqualTo("js:{ attempt >= target }");
    }

    @Test
    @DisplayName("A deferred list arrives as a list of expressions and is reported as its sources")
    void testDeferredList() {
        List<Object> seen = new ArrayList<>();
        StepType listProbe = new StepType() {
            public String name() { return "probe.verify"; }
            public boolean retryable() { return true; }
            public Set<String> requiredParams() { return Set.of("check"); }
            public Set<String> deferredParams() { return Set.of("check"); }
            public Set<String> deferredBindings() { return Set.of("attempt"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
                seen.add(params.get("check"));
                return StepOutcome.success("ok");
            }
        };
        ScenarioEngine engine = engineWith(listProbe);

        ScenarioResult result = engine.run(scenario(new StepSpec("probe.verify",
                Map.of("check", List.of("js:{ true }", "js:{ attempt > 0 }")))), "t.yaml");

        assertThat(result.success()).isTrue();
        assertThat((List<?>) seen.get(0)).hasSize(2).allSatisfy(v -> assertThat(v).isInstanceOf(DeferredExpression.class));
        assertThat(result.steps().get(0).resolvedParams().get("check"))
                .isEqualTo(List.of("js:{ true }", "js:{ attempt > 0 }"));
    }

    @Test
    @DisplayName("A binding wins over a scope name of the same name")
    void testBindingWinsOverScope() {
        List<Object> seen = new ArrayList<>();
        ScenarioEngine engine = engineWith(deferredProbe(seen));

        ScenarioResult result = engine.run(scenario(
                new StepSpec("set", Map.of("attempt", 0)),
                new StepSpec("probe.verify", Map.of("check", "js:{ attempt === 1 }")))), "t.yaml");

        assertThat(result.success()).isTrue();
    }
```

Add imports for `com.idm.test.engine.spi.DeferredExpression` and `java.util.Set`, if they're missing.

`testBindingWinsOverScope` binds `attempt` via `set:` and then uses it as a deferred binding. If the validator rejects `set: attempt` because it shadows something, rename it; the point is only that the binding wins. The validator doesn't know about bindings for names defined by `set`, so it should accept this.

In `ScenarioValidatorTest`, add a deferred-parameter step type and tests:

```java
    private static final StepType DEFERRED = new StepType() {
        public String name() { return "probe.verify"; }
        public boolean retryable() { return true; }
        public Set<String> requiredParams() { return Set.of("check"); }
        public Set<String> deferredParams() { return Set.of("check"); }
        public Set<String> deferredBindings() { return Set.of("response"); }
        public StepOutcome execute(Map<String, Object> params, boolean captureRequested) { return StepOutcome.success("ok"); }
    };

    private final ScenarioValidator deferredValidator = new ScenarioValidator(
            Map.of("ldap.create", ACTION, "probe.verify", DEFERRED), Set.of("random-user"));

    @Test
    @DisplayName("A deferred check may read its bindings and the scenario's names")
    void testDeferredCheckAccepted() {
        Scenario ok = scenario(new StepSpec("probe.verify", Map.of(
                "check", List.of("js:{ response.status === 200 }", "js:{ response.body.includes(user.userId) }"))));
        assertThatCode(() -> deferredValidator.validate(ok, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A deferred check must be js:{} expressions, and not an empty list")
    void testDeferredCheckShape() {
        assertThatThrownBy(() -> deferredValidator.validate(scenario(new StepSpec("probe.verify",
                Map.of("check", "status is 200"))), "t.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'check' must be js:{ … } expressions; got \"status is 200\"");
        assertThatThrownBy(() -> deferredValidator.validate(scenario(new StepSpec("probe.verify",
                Map.of("check", "${user.userId}"))), "t.yaml"))
                .hasMessageContaining("'check' must be js:{ … } expressions");
        assertThatThrownBy(() -> deferredValidator.validate(scenario(new StepSpec("probe.verify",
                Map.of("check", List.of()))), "t.yaml"))
                .hasMessageContaining("'check' needs at least one condition");
    }

    @Test
    @DisplayName("A binding is known only inside the deferred parameter; unknown roots are still rejected")
    void testDeferredBindingScope() {
        assertThatThrownBy(() -> deferredValidator.validate(scenario(new StepSpec("probe.verify",
                Map.of("check", "js:{ respnse.status === 200 }"))), "t.yaml"))
                .hasMessageContaining("nothing named 'respnse'");
        assertThatThrownBy(() -> deferredValidator.validate(scenario(new StepSpec("ldap.create",
                Map.of("dn", "js:{ response.body }"))), "t.yaml"))
                .hasMessageContaining("nothing named 'response'");
    }
```

`ACTION`, `scenario(...)` and the imports already exist in `ScenarioValidatorTest`. Check that `ACTION` accepts `dn`, and adjust the `ldap.create` params to that stub's accepted set if needed.

- [ ] **Step 2: Run the tests and confirm they fail to compile**

Run: `./mvnw test -pl idm-test-engine -am -Dtest='ScenarioEngineTest,ScenarioValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR. `DeferredExpression` doesn't exist and `deferredParams` isn't in `StepType`.

- [ ] **Step 3: Add the SPI**

`DeferredExpression.java`:

```java
package com.idm.test.engine.spi;

import java.util.Map;

/**
 * A step parameter the engine did not resolve up front, because the step must evaluate it
 * itself, usually once per attempt against something only the step knows (for example the
 * response it just received). See {@link StepType#deferredParams()}.
 */
public interface DeferredExpression {

    /** The expression exactly as written, e.g. {@code js:{ response.status === 200 }}, for messages. */
    String source();

    /**
     * Evaluates against the scenario's names as they were when the step started, plus
     * {@code bindings}. A binding wins over a scenario name of the same name.
     *
     * @return a plain Java value, as any {@code js:{}} expression produces
     * @throws RuntimeException when the expression throws, or produces {@code undefined}
     */
    Object evaluate(Map<String, Object> bindings);
}
```

In `StepType`, add after `acceptedParams()`:

```java
    /**
     * Parameters the engine must not resolve before {@link #execute}: each arrives as a
     * {@link DeferredExpression} (or a list of them) that the step evaluates itself. Each value
     * must be a whole-value {@code js:{ … }} expression, which the validator enforces.
     */
    default Set<String> deferredParams() {
        return Set.of();
    }

    /**
     * Names the step binds when it evaluates its deferred parameters (for example
     * {@code response}). The validator treats them as known inside those parameters only.
     */
    default Set<String> deferredBindings() {
        return Set.of();
    }
```

- [ ] **Step 4: The engine**

`ScopedExpression.java`:

```java
package com.idm.test.engine;

import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.spi.DeferredExpression;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The engine's {@link DeferredExpression}: a js:{} body plus the scope it was written in. */
final class ScopedExpression implements DeferredExpression {

    private final String source;
    private final String body;
    private final Map<String, Object> scope;
    private final ExpressionEvaluator evaluator;

    ScopedExpression(String source, String body, Map<String, Object> scope, ExpressionEvaluator evaluator) {
        this.source = source;
        this.body = body;
        this.scope = Collections.unmodifiableMap(new LinkedHashMap<>(scope));
        this.evaluator = evaluator;
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public Object evaluate(Map<String, Object> bindings) {
        Map<String, Object> variables = new LinkedHashMap<>(scope);
        variables.putAll(bindings);
        return evaluator.evaluate(body, variables);
    }

    @Override
    public String toString() {
        return source;
    }
}
```

In `ScenarioEngine.runStep`, replace

```java
        Map<String, Object> resolved = (Map<String, Object>) Interpolator.resolve(
                step.params(), scope, evaluator);
```

with

```java
        // Deferred parameters are left for the step to evaluate itself (see
        // StepType.deferredParams); everything else is resolved once, as always.
        Map<String, Object> immediate = new LinkedHashMap<>(step.params());
        Map<String, Object> deferred = new LinkedHashMap<>();
        for (String name : type.deferredParams()) {
            if (immediate.containsKey(name)) {
                deferred.put(name, immediate.remove(name));
            }
        }
        Map<String, Object> resolved = (Map<String, Object>) Interpolator.resolve(immediate, scope, evaluator);
        deferred.forEach((name, value) -> resolved.put(name, defer(value, scope)));
```

and add these helpers to `ScenarioEngine`:

```java
    private Object defer(Object value, Map<String, Object> scope) {
        if (value instanceof List<?> list) {
            List<Object> expressions = new ArrayList<>(list.size());
            list.forEach(item -> expressions.add(defer(item, scope)));
            return expressions;
        }
        String text = String.valueOf(value);
        String body = Interpolator.expressionBody(text);
        if (body == null) {
            // The validator rejects this at load; reaching it means validation was skipped.
            throw new IllegalArgumentException("a deferred parameter must be a js:{ … } expression, got '" + text + "'");
        }
        return new ScopedExpression(text, body, scope, evaluator);
    }

    /** Resolved parameters as the results record them: deferred expressions as their source text. */
    private static Map<String, Object> reportable(Map<String, Object> resolved) {
        Map<String, Object> report = new LinkedHashMap<>();
        resolved.forEach((name, value) -> report.put(name, reportableValue(value)));
        return report;
    }

    private static Object reportableValue(Object value) {
        if (value instanceof DeferredExpression expression) {
            return expression.source();
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof DeferredExpression) {
            return list.stream().map(ScenarioEngine::reportableValue).toList();
        }
        return value;
    }
```

Then, in `runStep`, every `new StepResult(index, step.type(), resolved, …)` becomes `new StepResult(index, step.type(), reportable(resolved), …)`: the wait-on-action rejection, the no-wait path and the waited path. Import `com.idm.test.engine.spi.DeferredExpression` and `java.util.ArrayList`/`java.util.List` if they're missing.

Check the order in `runStep`: `WaitSpec.parse(resolved.remove("wait"), …)` and `resolved.remove("as")` must still run after this block. They do if the block replaces only the `Interpolator.resolve` line.

- [ ] **Step 5: The validator**

In `ScenarioValidator`'s step loop, replace

```java
            requireWellFormedExpressions(step.params(),
                    message -> new ValidationException(sourceName, index, step.type(), message));
            requireKnownReferences(step.params(), known, sourceName, i, step.type());
```

with

```java
            requireWellFormedExpressions(step.params(),
                    message -> new ValidationException(sourceName, index, step.type(), message));
            Map<String, Object> immediate = new LinkedHashMap<>(step.params());
            type.deferredParams().forEach(immediate::remove);
            requireKnownReferences(immediate, known, sourceName, i, step.type());
            for (String name : type.deferredParams()) {
                if (step.params().containsKey(name)) {
                    requireDeferred(name, step.params().get(name), known, type.deferredBindings(),
                            sourceName, i, step.type());
                }
            }
```

and add:

```java
    /**
     * A deferred parameter is evaluated by its step, once per attempt, so it must be a
     * js:{ … } expression (or a non-empty list of them). Its references may name the step's
     * bindings, e.g. {@code response}, as well as everything known at this point.
     */
    private static void requireDeferred(String name, Object value, Set<String> known, Set<String> bindings,
                                        String sourceName, int index, String stepType) {
        List<?> items = value instanceof List<?> list ? list : List.of(value);
        if (items.isEmpty()) {
            throw new ValidationException(sourceName, index, stepType, "'" + name + "' needs at least one condition");
        }
        for (Object item : items) {
            String text = String.valueOf(item);
            if (!(item instanceof String) || Interpolator.expressionBody(text) == null) {
                throw new ValidationException(sourceName, index, stepType,
                        "'" + name + "' must be js:{ … } expressions; got \"" + text + "\"");
            }
        }
        Set<String> visible = new LinkedHashSet<>(known);
        visible.addAll(bindings);
        requireKnownReferences(value, visible, sourceName, index, stepType);
    }
```

`requireWellFormedExpressions` already compiles every expression, including deferred ones. Check the loop's index variable names (`index` versus `i`). The existing code passes `index` to the lambda and `i` to `requireKnownReferences`, so mirror that. Add imports for `LinkedHashMap`/`LinkedHashSet`/`List` if missing.

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips. That includes `NoSpringDependencyTest` and every existing engine test: steps without deferred parameters are unaffected.

- [ ] **Step 7: Falsification**

Make each change below, re-run the two test classes, confirm that the named test fails, then revert:
- Resolve deferred parameters like any other, by deleting the removal loop. `testDeferredParameter` fails: the check is evaluated up front, `attempt` isn't bound, and the error names it.
- Report `resolved` instead of `reportable(resolved)`. `testDeferredParameter`'s `resolvedParams` assertion fails.
- Drop `visible.addAll(bindings)`. `testDeferredCheckAccepted` fails.
- Validate references on `step.params()` instead of `immediate`. `testDeferredCheckAccepted` fails with `nothing named 'response'`.

- [ ] **Step 8: Commit**

```bash
git add idm-test-engine
git commit -m "feat: let a step defer an expression parameter and evaluate it per attempt

ITF-24
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `HttpVerifyStep` and the shared `HttpExchange`

**Files:**
- Create:
  - `idm-test-engine/src/main/java/com/idm/test/engine/steps/HttpExchange.java`
  - `idm-test-engine/src/main/java/com/idm/test/engine/steps/HttpVerifyStep.java`
- Modify:
  - `idm-test-engine/src/main/java/com/idm/test/engine/steps/HttpRequestStep.java` (`execute` uses `HttpExchange`)
  - `idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/TokenFailureText.java` (make the class and `safe` public; nothing else changes)
- Test: `idm-test-engine/src/test/java/com/idm/test/engine/steps/HttpVerifyStepsTest.java` (new)

**Interfaces:**
- Consumes (Task 1): `DeferredExpression`, `StepType.deferredParams()/deferredBindings()`, and the engine behaviour.
- Consumes (existing):
  - `RestTargets.resolveBaseUrl(String step, String target, RestProperties)` → `Resolution` with `baseUrl()`, `error()`, `isFailure()`
  - `RestAuthorization.apply(String step, String target, RestProperties, RestAuthenticator, Map<String,String> headers)` → `StepOutcome` or `null`
  - `IdmRestConnector.exchangeText(String method, String url, String body, Map<String,String> headers)` → `OperationResult<TextResponse>`
  - `TextResponse(int status, Map<String, List<String>> headers, String body)`
- Produces: `public HttpVerifyStep(IdmRestConnector connector, RestProperties properties, RestAuthenticator authenticator)`.

- [ ] **Step 1: Write the failing tests**

`HttpVerifyStepsTest` runs `http.verify` through the real engine, because the checks are deferred:

```java
package com.idm.test.engine.steps;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestClientConfiguration;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("http.verify")
class HttpVerifyStepsTest {

    private static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    private static final IdmRestConnector CONNECTOR = new IdmRestConnector(
            RestClient.builder().requestFactory(RestClientConfiguration.requestFactory()).build());

    private static RestProperties properties() {
        RestProperties properties = new RestProperties();
        RestProperties.RestApiConfig mock = new RestProperties.RestApiConfig();
        mock.setBaseUrl(WIREMOCK.baseUrl());
        properties.getRest().put("mock", mock);
        RestProperties.RestApiConfig secured = new RestProperties.RestApiConfig();
        secured.setBaseUrl(WIREMOCK.baseUrl());
        secured.setUsername("svc");
        secured.setPassword("s3cret");
        properties.getRest().put("secured", secured);
        return properties;
    }

    private static final HttpVerifyStep STEP =
            new HttpVerifyStep(CONNECTOR, properties(), RestAuthenticator.withoutOAuth());

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIREMOCK.resetAll();
    }

    private static StepResult run(Map<String, Object> params) {
        ScenarioEngine engine = new ScenarioEngine(Map.of(STEP.name(), STEP),
                new FixtureFactory(new TestDataGenerator()), new TestExecutor(),
                Duration.ofSeconds(2), Duration.ofMillis(100), new ExpressionEvaluator());
        ScenarioResult result = engine.run(new com.idm.test.engine.model.Scenario("verify", null, Map.of(),
                List.of(new StepSpec("http.verify", params))), "verify.yaml");
        return result.steps().get(0);
    }

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("target", "mock");
        params.put("method", "GET");
        params.put("path", "/users/1");
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    private static void stubJson(int status, String body) {
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("Passes when every check is true, and reads status and json")
    void testChecksPass() {
        stubJson(200, "{\"department\":\"Finance\"}");

        StepResult step = run(params("check", List.of(
                "js:{ response.status === 200 }", "js:{ response.json.department === 'Finance' }")));

        assertThat(step.success()).as(step.message()).isTrue();
        assertThat(step.message()).isEqualTo("GET " + WIREMOCK.baseUrl() + "/users/1 passed 2 check(s) (status 200)");
    }

    @Test
    @DisplayName("Names the first false check with its index, status and body excerpt; later checks are not evaluated")
    void testFirstFalseCheckNamed() {
        stubJson(200, "{\"department\":\"HR\"}");

        StepResult step = run(params("check", List.of(
                "js:{ response.status === 200 }",
                "js:{ response.json.department === 'Finance' }",
                "js:{ response.json.missing.deep === 1 }")));

        assertThat(step.success()).isFalse();
        assertThat(step.message()).isEqualTo("http.verify GET " + WIREMOCK.baseUrl()
                + "/users/1: check 2 of 3 is false: js:{ response.json.department === 'Finance' }"
                + " (status 200, body: {\"department\":\"HR\"})");
    }

    @Test
    @DisplayName("json is parsed for application/json and +json, and null for text or invalid JSON")
    void testJsonParsing() {
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/problem+json; charset=UTF-8").withBody("{\"a\":1}")));
        assertThat(run(params("check", "js:{ response.json.a === 1 }")).success()).isTrue();

        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/plain").withBody("{\"a\":1}")));
        assertThat(run(params("check", "js:{ response.json === null && response.body === '{\"a\":1}' }")).success()).isTrue();

        stubJson(200, "not json");
        assertThat(run(params("check", "js:{ response.json === null }")).success()).isTrue();
    }

    @Test
    @DisplayName("A throwing check is an ordinary failure, retried under wait")
    void testThrowingCheckRetried() {
        stubJson(200, "{}");

        StepResult step = run(params("check", "js:{ response.json.missing.deep === 1 }",
                "wait", Map.of("timeout", "1s", "poll", "1s")));

        assertThat(step.success()).isFalse();
        assertThat(step.message()).contains("check 1 of 1 failed:").endsWith("(last observed value after retrying)");
    }

    @Test
    @DisplayName("A non-boolean result is a final failure that stops a wait at once")
    void testNonBooleanIsFinal() {
        stubJson(200, "{\"status\":\"ACTIVE\"}");

        StepResult step = run(params("check", "js:{ response.json.status }", "wait", Boolean.TRUE));

        assertThat(step.success()).isFalse();
        assertThat(step.attempts()).isEqualTo(1);
        assertThat(step.message()).contains("check 1 of 1 must return true or false, got \"ACTIVE\"")
                .endsWith("(not retried: waiting cannot change this)");
    }

    @Test
    @DisplayName("Reading response.xml fails with the ITF-25 hint")
    void testXmlHint() {
        stubJson(200, "{}");

        StepResult step = run(params("check", "js:{ response.xml.Envelope !== undefined }"));

        assertThat(step.success()).isFalse();
        assertThat(step.message()).contains("response.xml is not available yet: it arrives with ITF-25");
    }

    @Test
    @DisplayName("Under wait, a 404 then a 200 passes on the second attempt")
    void testWaitsForProvisioning() {
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).inScenario("provisioning")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("ready")
                .willReturn(aResponse().withStatus(404).withBody("not yet")));
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).inScenario("provisioning")
                .whenScenarioStateIs("ready")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"active\":true}")));

        StepResult step = run(params("check", List.of("js:{ response.status === 200 }", "js:{ response.json.active }"),
                "wait", Map.of("timeout", "2s", "poll", "1s")));

        assertThat(step.success()).as(step.message()).isTrue();
        assertThat(step.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("as: binds the passing response")
    void testCapture() {
        stubJson(200, "{\"id\":7}");
        StepResult step = run(params("check", "js:{ response.status === 200 }", "as", "account"));
        assertThat(step.success()).as(step.message()).isTrue();
    }

    @Test
    @DisplayName("Target authentication applies, and the author's own header wins")
    void testAuthentication() {
        WIREMOCK.stubFor(get(urlEqualTo("/users/1")).willReturn(aResponse().withStatus(200)));

        run(params("target", "secured", "check", "js:{ response.status === 200 }"));
        WIREMOCK.verify(getRequestedFor(urlEqualTo("/users/1")).withHeader("Authorization", equalTo("Basic c3ZjOnMzY3JldA==")));

        WIREMOCK.resetRequests();
        run(params("target", "secured", "headers", Map.of("authorization", "Basic bad"), "check", "js:{ true }"));
        WIREMOCK.verify(getRequestedFor(urlEqualTo("/users/1")).withHeader("Authorization", equalTo("Basic bad")));
    }

    @Test
    @DisplayName("An unknown target, bad method, non-text body or non-map headers stop a wait after one attempt")
    void testConfigurationErrorsAreFinal() {
        for (Map<String, Object> bad : List.of(
                params("target", "nope", "check", "js:{ true }", "wait", Boolean.TRUE),
                params("method", "TRACE", "check", "js:{ true }", "wait", Boolean.TRUE),
                params("body", Map.of("a", 1), "check", "js:{ true }", "wait", Boolean.TRUE),
                params("headers", "x", "check", "js:{ true }", "wait", Boolean.TRUE))) {
            StepResult step = run(bad);
            assertThat(step.success()).as(bad.toString()).isFalse();
            assertThat(step.attempts()).as(bad.toString()).isEqualTo(1);
        }
    }
}
```

`params(...)` puts later keys over the defaults, so `"target", "nope"` replaces `mock`. The engine runs without the validator here, so `body: {a: 1}` and `headers: x` reach the step. That's intended: those tests exercise the step's own checks.

- [ ] **Step 2: Run the tests and confirm they fail to compile**

Run: `./mvnw test -pl idm-test-engine -am -Dtest=HttpVerifyStepsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `cannot find symbol: class HttpVerifyStep`.

- [ ] **Step 3: Make `TokenFailureText` public**

In `TokenFailureText.java`, change `final class TokenFailureText` to `public final class TokenFailureText` and `static String safe(` to `public static String safe(`. Add one Javadoc sentence: `Also used by http.verify to excerpt a response body in failure messages.` Nothing else changes.

- [ ] **Step 4: `HttpExchange`**

```java
package com.idm.test.engine.steps;

import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds and sends the raw-text request shared by http.request and http.verify, so both apply
 * the same method list, header and body rules, target resolution and authentication. Problems
 * with the step's own parameters or configuration are final failures: waiting cannot fix them.
 */
final class HttpExchange {

    static final Set<String> METHODS = IdmRestConnector.SUPPORTED_METHODS;

    /** A request ready to send, or the failure that stops it. */
    record Prepared(String method, String url, String body, Map<String, String> headers, StepOutcome failure) {

        static Prepared failed(StepOutcome failure) {
            return new Prepared(null, null, null, null, failure);
        }

        boolean isFailure() {
            return failure != null;
        }
    }

    private HttpExchange() {
    }

    static Prepared prepare(String stepName, Map<String, Object> params, RestProperties properties,
                            RestAuthenticator authenticator) {
        String method = String.valueOf(params.get("method")).toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            return Prepared.failed(StepOutcome.finalFailure(stepName + " method '" + params.get("method")
                    + "' is not one of " + new TreeSet<>(METHODS)));
        }

        Object rawHeaders = params.get("headers");
        if (rawHeaders != null && !(rawHeaders instanceof Map)) {
            return Prepared.failed(StepOutcome.finalFailure(stepName + " headers must be a map of header name to value"));
        }

        Object rawBody = params.get("body");
        if (rawBody != null && !(rawBody instanceof String)) {
            return Prepared.failed(StepOutcome.finalFailure(
                    stepName + " body must be a string sent as-is; to send a JSON object use rest.request"));
        }

        String target = String.valueOf(params.get("target"));
        RestTargets.Resolution resolution = RestTargets.resolveBaseUrl(stepName, target, properties);
        if (resolution.isFailure()) {
            return Prepared.failed(StepOutcome.finalFailure(resolution.error()));
        }
        String url = resolution.baseUrl() + String.valueOf(params.get("path"));

        Map<String, String> headers = new LinkedHashMap<>();
        if (rawHeaders instanceof Map<?, ?> map) {
            map.forEach((key, value) -> headers.put(String.valueOf(key), String.valueOf(value)));
        }

        StepOutcome authFailure = RestAuthorization.apply(stepName, target, properties, authenticator, headers);
        if (authFailure != null) {
            return Prepared.failed(authFailure);
        }
        return new Prepared(method, url, (String) rawBody, headers, null);
    }

    static OperationResult<TextResponse> send(IdmRestConnector connector, Prepared request) {
        return connector.exchangeText(request.method(), request.url(), request.body(), request.headers());
    }

    /** The {status, headers, body} view both steps expose. */
    static Map<String, Object> capture(TextResponse response) {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("status", response.status());
        capture.put("headers", response.headers());
        capture.put("body", response.body());
        return capture;
    }
}
```

Rewrite `HttpRequestStep.execute` to use it, with the messages unchanged:

```java
    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        HttpExchange.Prepared request = HttpExchange.prepare("http.request", params, properties, authenticator);
        if (request.isFailure()) {
            return request.failure();
        }
        OperationResult<TextResponse> result = HttpExchange.send(connector, request);
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("http.request failed for " + request.url() + ": " + result.getMessage());
        }
        TextResponse response = result.getData().get();
        return StepOutcome.success(request.method() + " " + request.url() + " returned " + response.status(),
                HttpExchange.capture(response));
    }
```

Keep `static final Set<String> METHODS = HttpExchange.METHODS;` in `HttpRequestStep` if anything references it (`grep -rn "HttpRequestStep.METHODS" idm-test-engine`). Remove the imports that are now unused.

- [ ] **Step 5: `HttpVerifyStep`**

```java
package com.idm.test.engine.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.client.TokenFailureText;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.DeferredExpression;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sends a raw-text request like http.request, then evaluates each js:{} condition in
 * {@code check} against {@code response}. All must return true. Retryable: under wait:, the
 * request and the checks repeat until they pass or the timeout expires. A check that throws is
 * retried (for example before provisioning, response.json can be null); a check that returns
 * something other than true or false is an authoring mistake and ends the wait at once.
 */
public final class HttpVerifyStep implements StepType {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String XML_HINT =
            " (response.xml is not available yet: it arrives with ITF-25; check response.body for now)";

    private final IdmRestConnector connector;
    private final RestProperties properties;
    private final RestAuthenticator authenticator;

    public HttpVerifyStep(IdmRestConnector connector, RestProperties properties, RestAuthenticator authenticator) {
        this.connector = connector;
        this.properties = properties;
        this.authenticator = authenticator;
    }

    @Override
    public String name() {
        return "http.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of("target", "method", "path", "check");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("target", "method", "path", "check", "headers", "body", "wait");
    }

    @Override
    public Set<String> deferredParams() {
        return Set.of("check");
    }

    @Override
    public Set<String> deferredBindings() {
        return Set.of("response");
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        List<DeferredExpression> checks = checksOf(params.get("check"));
        if (checks == null || checks.isEmpty()) {
            return StepOutcome.finalFailure("http.verify 'check' must be js:{ … } expressions");
        }

        HttpExchange.Prepared request = HttpExchange.prepare("http.verify", params, properties, authenticator);
        if (request.isFailure()) {
            return request.failure();
        }
        OperationResult<TextResponse> result = HttpExchange.send(connector, request);
        if (!result.isSuccess() || result.getData().isEmpty()) {
            return StepOutcome.failure("http.verify failed for " + request.url() + ": " + result.getMessage());
        }

        TextResponse response = result.getData().get();
        Map<String, Object> bound = responseOf(response);
        String where = "http.verify " + request.method() + " " + request.url();
        int total = checks.size();
        for (int i = 0; i < total; i++) {
            DeferredExpression check = checks.get(i);
            String label = where + ": check " + (i + 1) + " of " + total;
            String hint = check.source().contains("response.xml") ? XML_HINT : "";
            Object value;
            try {
                value = check.evaluate(Map.of("response", bound));
            } catch (RuntimeException e) {
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return StepOutcome.failure(label + " failed: " + error + context(response) + hint);
            }
            if (!(value instanceof Boolean passed)) {
                return StepOutcome.finalFailure(label + " must return true or false, got " + describe(value) + hint);
            }
            if (!passed) {
                return StepOutcome.failure(label + " is false: " + check.source() + context(response) + hint);
            }
        }

        String message = request.method() + " " + request.url() + " passed " + total
                + " check(s) (status " + response.status() + ")";
        return captureRequested ? StepOutcome.success(message, bound) : StepOutcome.success(message);
    }

    private static List<DeferredExpression> checksOf(Object raw) {
        List<?> items = raw instanceof List<?> list ? list : raw == null ? List.of() : List.of(raw);
        List<DeferredExpression> checks = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof DeferredExpression expression)) {
                return null;
            }
            checks.add(expression);
        }
        return checks;
    }

    /** status, headers and body as http.request captures them, plus the parsed JSON body. */
    private static Map<String, Object> responseOf(TextResponse response) {
        Map<String, Object> bound = new LinkedHashMap<>(HttpExchange.capture(response));
        bound.put("json", jsonOf(response));
        return bound;
    }

    private static Object jsonOf(TextResponse response) {
        List<String> types = response.headers().getOrDefault("content-type", List.of());
        String mediaType = types.isEmpty() ? "" : types.get(0).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!(mediaType.equals("application/json") || mediaType.endsWith("+json")) || response.body().isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(response.body(), Object.class);
        } catch (Exception notJson) {
            return null; // declared JSON but isn't: the check sees null, as IdmRestConnector does
        }
    }

    private static String context(TextResponse response) {
        return " (status " + response.status() + ", body: " + TokenFailureText.safe(response.body(), null) + ")";
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text + "\"";
        }
        return String.valueOf(value);
    }
}
```

The engine module must have Jackson databind on its classpath. It already depends on `jackson-dataformat-yaml`, which brings `jackson-databind`. Confirm with `grep -n jackson idm-test-engine/pom.xml`.

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `./mvnw test -pl idm-test-engine -am`
Expected: BUILD SUCCESS, 0 failures, errors and skips. That includes the existing `HttpStepsTest` and `HttpAuthStepsTest`, which must pass unchanged after the `HttpExchange` refactor, and `NoSpringDependencyTest`.

If `testThrowingCheckRetried`'s message isn't `failed:` because `response.json.missing.deep` produced `undefined` before throwing, check what Rhino reports. `{}` has no `missing`, so `.deep` throws a TypeError. Adjust only the expression, not the rule.

- [ ] **Step 7: Falsification**

Make each change below, re-run `HttpVerifyStepsTest`, confirm that the named test fails, then revert:
- Return `StepOutcome.failure` (not final) for a non-boolean. `testNonBooleanIsFinal` fails on `attempts`.
- Return `finalFailure` for a throwing check. `testThrowingCheckRetried` fails on its suffix.
- Drop the `+json` clause. `testJsonParsing` fails.
- In `HttpExchange.prepare`, return a plain `failure` for an unknown target. `testConfigurationErrorsAreFinal` fails.

- [ ] **Step 8: Commit**

```bash
git add idm-test-engine idm-connector-rest/src/main/java/com/idm/test/connector/rest/client/TokenFailureText.java
git commit -m "feat: add http.verify, checking a response with JavaScript conditions per attempt

ITF-24
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Registration, the reference page and the vocabulary fixture

**Files:**
- Modify:
  - `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/YamlScenarioTest.java` (`registry(...)`)
  - `idm-test-scenarios/src/test/resources/scenarios-vocabulary-fixture/full-vocabulary.yaml`
  - `docs/reference/writing-scenarios.md`
  - `README.md` (the step vocabulary table)
  - `docs/architecture/capabilities.md` (the `http.request` row)
- Test:
  - `idm-test-scenarios/src/test/java/com/idm/test/scenarios/engine/ScenarioNeedsTest.java`
  - the existing `ScenarioReferenceTest` and `YamlScenarioValidationTest`

**Interfaces:**
- Consumes: `new HttpVerifyStep(IdmRestConnector, RestProperties, RestAuthenticator)`.

- [ ] **Step 1: Register it (red first)**

In `YamlScenarioTest.registry(...)`, after `new HttpRequestStep(restConnector, restProperties, restAuthenticator)`, add `new HttpVerifyStep(restConnector, restProperties, restAuthenticator)`, and add the import.

Run: `./mvnw test -pl idm-test-scenarios -am -Dtest=ScenarioReferenceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `testCoverage` FAILS, listing `http.verify` as registered with no section. This is the ITF-10 drift test working.

- [ ] **Step 2: The reference section**

In `docs/reference/writing-scenarios.md`, under `### Raw HTTP (any body, REST or SOAP)` and after the `http.request` section, add:

````markdown
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

`response.xml` (a parsed XML or SOAP body) arrives with ITF-25; until then, check `response.body`.
Checks may also read every name available at this point in the scenario, such as `user.userId`.

**Captures:** the `response` map of the passing attempt.

**Fails when:**
- a check is `false`: the message names it (`check 2 of 3 is false: …`) with the status and the
  start of the body. Retried under `wait:`.
- a check throws, for example `response.json.department` while `response.json` is still `null`.
  Retried under `wait:`.
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
    check: "js:{ response.status === 200 && response.body.includes('<status>ACTIVE</status>') }"
    wait: true
```
````

In `## 5. Waiting`, change the sentence listing the assertions, `(`ldap.verify`, `db.verify`, `rest.verify`)`, to include `http.verify`. In `## 8. What validation rejects at load`, add a row:

`| a \`check:\` that isn't \`js:{ … }\`, or an empty list | \`demo.yaml, step 1 (http.verify): 'check' must be js:{ … } expressions; got "status is 200"\` |`

Capture the real message by running one broken scenario, as ITF-10 did, and paste it exactly.

- [ ] **Step 3: The vocabulary fixture, README and capability matrix**

- **`full-vocabulary.yaml`:** after its `http.request` entry, add a matching `http.verify` entry, with the same target, method and path, plus `check: "js:{ response.status === 200 }"` and `wait: true`. The file must still validate (`YamlScenarioValidationTest.testFullVocabularyValidates`).
- **`README.md`:** in the step vocabulary table, after the `http.request` row, add `| \`http.verify\` | assertion | \`target\`, \`method\`, \`path\`, \`check\` | \`headers\`, \`body\`, \`wait\` (JavaScript conditions on \`response\`) |`.
- **`docs/architecture/capabilities.md`:** in the `YAML \`http.request\`` row's limitation cell, replace "Checking a response in JavaScript (ITF-24) and reading XML from `js:{}` (ITF-25) are open" with "`http.verify` checks a response with JavaScript conditions, retryable with `wait:` (ITF-24); reading XML (`response.xml`) is ITF-25".

- [ ] **Step 4: `ScenarioNeeds` covers it**

Add to `ScenarioNeedsTest`:

```java
    @Test
    @DisplayName("http.verify contributes its target to the REST targets")
    void testHttpVerifyTarget() {
        assertThat(needs("""
                name: v
                steps:
                  - http.verify:
                      target: roleservice
                      method: GET
                      path: /x
                      check: "js:{ true }"
                """).restTargets()).containsExactly("roleservice");
    }
```

This should pass without code changes, because `ScenarioNeeds` maps the `http.` prefix.

- [ ] **Step 5: Run the full suite**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS, 0 failures, errors and skips, including `ScenarioReferenceTest` (now 14 sections) and `YamlScenarioValidationTest`. `grep -c '"status" : "FAILED"' idm-test-scenarios/target/scenario-results.json` prints `0`. Report the total. If `docker info` succeeds, also run `ContainerScenarios` (expect 20/20); otherwise say Docker was unavailable.

- [ ] **Step 6: Commit**

```bash
git add idm-test-scenarios docs/reference/writing-scenarios.md README.md docs/architecture/capabilities.md
git commit -m "feat: register http.verify and document it in the authoring reference

ITF-24
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
