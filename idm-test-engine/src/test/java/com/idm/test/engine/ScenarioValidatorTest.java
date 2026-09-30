package com.idm.test.engine;

import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;
import com.idm.test.engine.steps.DbExecuteStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScenarioValidator")
class ScenarioValidatorTest {

    // Mirrors LdapCreateStep's real contract: requires 'dn', also accepts 'attributes'.
    private static final StepType ACTION = new StepType() {
        public String name() { return "ldap.create"; }
        public Set<String> requiredParams() { return Set.of("dn"); }
        public Set<String> acceptedParams() { return Set.of("dn", "attributes"); }
        public StepOutcome execute(Map<String, Object> params, boolean captureRequested) { return StepOutcome.success("ok"); }
    };

    // Mirrors LdapVerifyStep's real contract: requires 'dn' and 'expect'; acceptedParams()
    // is left to the interface default, which adds 'wait' because retryable() is true.
    private static final StepType ASSERTION = new StepType() {
        public String name() { return "ldap.verify"; }
        public boolean retryable() { return true; }
        public Set<String> requiredParams() { return Set.of("dn", "expect"); }
        public StepOutcome execute(Map<String, Object> params, boolean captureRequested) { return StepOutcome.success("ok"); }
    };

    private final ScenarioValidator validator = new ScenarioValidator(
            Map.of("ldap.create", ACTION, "ldap.verify", ASSERTION),
            Set.of("random-user"));

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

    private static Scenario scenario(StepSpec... steps) {
        return new Scenario("Test", null,
                Map.of("user", new FixtureSpec("random-user", Map.of())),
                List.of(steps));
    }

    @Test
    @DisplayName("Should accept a scenario whose steps and references are all known")
    void testAcceptsValidScenario() {
        Scenario valid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=${user.userId},ou=users")),
                new StepSpec("ldap.verify", Map.of("dn", "cn=${user.userId},ou=users",
                        "expect", Map.of("givenName", "${user.firstName}"),
                        "wait", Boolean.TRUE)));

        assertThatCode(() -> validator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should accept a step supplying exactly its accepted parameters")
    void testAcceptsStepWithExactlyAcceptedParameters() {
        Scenario valid = scenario(
                new StepSpec("ldap.create", Map.of(
                        "dn", "cn=${user.userId},ou=users",
                        "attributes", Map.of("sn", "Lovelace"))),
                new StepSpec("ldap.verify", Map.of(
                        "dn", "cn=${user.userId},ou=users",
                        "expect", Map.of("sn", "Lovelace"),
                        "wait", Boolean.TRUE)));

        assertThatCode(() -> validator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should name the unknown step type and list the available ones")
    void testRejectsUnknownStepType() {
        Scenario invalid = scenario(new StepSpec("ldap.summon", Map.of("dn", "cn=x")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.summon")
                .hasMessageContaining("ldap.create");
    }

    @Test
    @DisplayName("Should reject a step with no dn")
    void testRejectsMissingDn() {
        Scenario invalid = scenario(new StepSpec("ldap.create", Map.of()));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("dn");
    }

    @Test
    @DisplayName("Should reject a step missing a required parameter other than 'dn', naming it")
    void testRejectsMissingRequiredParameter() {
        // This is the direct fix for the vacuous-assertion bug: a consultant who omits
        // (or mistypes) 'expect' must be rejected before the step ever runs, rather than
        // silently comparing against an empty map and reporting success.
        Scenario invalid = scenario(
                new StepSpec("ldap.verify", Map.of("dn", "cn=${user.userId},ou=users")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.verify")
                .hasMessageContaining("expect");
    }

    @Test
    @DisplayName("Should reject an unknown parameter, naming it and listing what's accepted")
    void testRejectsUnknownParameter() {
        Scenario invalid = scenario(
                new StepSpec("ldap.verify", Map.of(
                        "dn", "cn=${user.userId},ou=users",
                        "expect", Map.of("givenName", "Ada"),
                        // the typo from the bug report: 'expct' instead of 'expect'
                        "expct", Map.of("givenName", "Ada"))));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.verify")
                .hasMessageContaining("expct")
                .hasMessageContaining("dn")
                .hasMessageContaining("expect")
                .hasMessageContaining("wait");
    }

    @Test
    @DisplayName("Should reject a typo'd parameter written instead of the required one, "
            + "naming the missing parameter and hinting at the stray one")
    void testRejectsTypoInsteadOfRequiredParameter() {
        // Unlike testRejectsUnknownParameter above, this supplies ONLY the typo ('expct') and
        // never the real 'expect' — the actual real-world shape of the bug report, where a
        // consultant writes the wrong key rather than an extra one. The required-parameter
        // check fires first (deliberately — see the comment above it in ScenarioValidator),
        // so this must not fall through to the unknown-parameter scan and must not have both
        // checks report the same mistake. The message still gets a hint at 'expct' from
        // unrecognizedHint(), without moving or duplicating the check that produces it.
        Scenario invalid = scenario(
                new StepSpec("ldap.verify", Map.of(
                        "dn", "cn=${user.userId},ou=users",
                        "expct", Map.of("givenName", "Ada"))));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.verify")
                .hasMessageContaining("missing required parameter 'expect'")
                .hasMessageContaining("expct");
    }

    @Test
    @DisplayName("Should accept 'wait' and a required parameter even when a retryable step's "
            + "acceptedParams() override omits them")
    void testAcceptsWaitAndRequiredParamDespiteNarrowAcceptedParamsOverride() {
        // Guards the fix for the coupling bug: LdapCreateStep's hardcoded Set.of(...)
        // override is the template later step types will copy. A retryable step following
        // that template (e.g. a "db.verify" with optional 'database'/'query') could override
        // acceptedParams() and forget 'wait' entirely, or even forget its own required
        // parameter — this must not make ScenarioValidator reject either as unknown.
        StepType retryableWithNarrowOverride = new StepType() {
            public String name() { return "db.verify"; }
            public boolean retryable() { return true; }
            public Set<String> requiredParams() { return Set.of("dn"); }
            // Deliberately omits both 'wait' and the step's own required 'dn'.
            public Set<String> acceptedParams() { return Set.of("database", "query"); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) { return StepOutcome.success("ok"); }
        };
        ScenarioValidator localValidator = new ScenarioValidator(
                Map.of("db.verify", retryableWithNarrowOverride), Set.of("random-user"));

        Scenario valid = scenario(new StepSpec("db.verify", Map.of(
                "dn", "cn=${user.userId},ou=users",
                "database", "sap",
                "wait", Boolean.TRUE)));

        assertThatCode(() -> localValidator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject a reference to a fixture that does not exist")
    void testRejectsUnknownReference() {
        Scenario invalid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=${absent.userId},ou=users")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("Should reject wait on an action, since only assertions retry")
    void testRejectsWaitOnAction() {
        Scenario invalid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=x,ou=users", "wait", Boolean.TRUE)));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("wait")
                .hasMessageContaining("ldap.create");
    }

    @Test
    @DisplayName("Should reject a scenario with no steps")
    void testRejectsEmptyScenario() {
        assertThatThrownBy(() -> validator.validate(scenario(), "empty.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one step");
    }

    @Test
    @DisplayName("Should reject a wait with a malformed duration string, naming the file and value")
    void testRejectsWaitWithBadDurationString() {
        // The exact late failure ScenarioValidator exists to prevent: without this check,
        // '30x' would only be discovered once WaitSpec.parse runs inside the engine, after
        // every earlier step (and this scenario's own earlier steps) has already executed.
        Scenario invalid = scenario(
                new StepSpec("ldap.verify", Map.of(
                        "dn", "cn=${user.userId},ou=users",
                        "expect", Map.of("givenName", "Ada"),
                        "wait", Map.of("timeout", "30x"))));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.verify")
                .hasMessageContaining("30x");
    }

    @Test
    @DisplayName("Should reject a wait of the wrong shape, naming the file and value")
    void testRejectsWaitOfWrongType() {
        // 'wait' must be true or a map with timeout/poll; a bare string is the wrong shape
        // entirely, distinct from the bad-duration-string case above.
        Scenario invalid = scenario(
                new StepSpec("ldap.verify", Map.of(
                        "dn", "cn=${user.userId},ou=users",
                        "expect", Map.of("givenName", "Ada"),
                        "wait", "30s")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.verify")
                .hasMessageContaining("30s");
    }

    @Test
    @DisplayName("Should reject a fixture with an unknown generator, naming it and listing what's available")
    void testRejectsUnknownGenerator() {
        Scenario invalid = new Scenario("Test", null,
                Map.of("user", new FixtureSpec("randmo-user", Map.of())),
                List.of(new StepSpec("ldap.create", Map.of("dn", "cn=${user.userId},ou=users"))));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("randmo-user")
                .hasMessageContaining("random-user");
    }

    @Test
    @DisplayName("Should accept a reference to a name bound by an earlier as:")
    void testAcceptsReferenceToEarlierCapture() {
        Scenario valid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=x,ou=users", "as", "created")),
                new StepSpec("ldap.verify", Map.of("dn", "${created}", "expect", Map.of())));

        assertThatCode(() -> validator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject a reference to a name bound only by a later step")
    void testRejectsForwardReference() {
        Scenario invalid = scenario(
                new StepSpec("ldap.verify", Map.of("dn", "${created}", "expect", Map.of())),
                new StepSpec("ldap.create", Map.of("dn", "cn=x,ou=users", "as", "created")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("created")
                .hasMessageContaining("bad.yaml");
    }

    @Test
    @DisplayName("Should accept a set: step and the names it binds")
    void testAcceptsSetStep() {
        Scenario valid = scenario(
                new StepSpec("set", Map.of("upper", "js:{ user.userId.toUpperCase() }")),
                new StepSpec("ldap.create", Map.of("dn", "cn=${upper},ou=users")));

        assertThatCode(() -> validator.validate(valid, "ok.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject a set: step with no entries")
    void testRejectsEmptySet() {
        Scenario invalid = scenario(new StepSpec("set", Map.of()));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    @DisplayName("Should reject an expression embedded in a larger string, at load time")
    void testRejectsEmbeddedExpressionAtValidation() {
        Scenario invalid = scenario(new StepSpec("ldap.create",
                Map.of("dn", "cn=js:{ user.firstName },ou=users")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.create")
                .hasMessageContaining("whole value");
    }

    @Test
    @DisplayName("Should reject an unclosed expression, at load time")
    void testRejectsUnclosedExpressionAtValidation() {
        Scenario invalid = scenario(new StepSpec("ldap.create",
                Map.of("dn", "js:{ user.firstName")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.create")
                .hasMessageContaining("not closed");
    }

    @Test
    @DisplayName("Should reject an expression with a JavaScript syntax error, at load time")
    void testRejectsSyntaxErrorExpressionAtValidation() {
        // Structurally well-formed (the braces balance), but not valid JavaScript inside.
        // Interpolator's structural check alone would let this through; only compiling it
        // catches the syntax error, and it must be caught here, not three minutes into a wait.
        Scenario invalid = scenario(new StepSpec("ldap.create",
                Map.of("dn", "js:{ user.firstName.( }")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.create");
    }

    @Test
    @DisplayName("Should reject an empty expression, at load time")
    void testRejectsEmptyExpressionAtValidation() {
        Scenario invalid = scenario(new StepSpec("ldap.create",
                Map.of("dn", "js:{}")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.create")
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Should reject a malformed expression inside a set: step's value too")
    void testRejectsMalformedExpressionInSetStep() {
        Scenario invalid = scenario(new StepSpec("set", Map.of("bad", "js:{}")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("set")
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Should reject an as: that shadows an existing fixture name")
    void testRejectsAsShadowingFixture() {
        Scenario invalid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=x,ou=users", "as", "user")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.create")
                .hasMessageContaining("user")
                .hasMessageContaining("shadow");
    }

    @Test
    @DisplayName("Should reject an as: that shadows an earlier as:")
    void testRejectsAsShadowingEarlierAs() {
        Scenario invalid = scenario(
                new StepSpec("ldap.create", Map.of("dn", "cn=x,ou=users", "as", "created")),
                new StepSpec("ldap.create", Map.of("dn", "cn=y,ou=users", "as", "created")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("created")
                .hasMessageContaining("shadow");
    }

    @Test
    @DisplayName("Should reject a set: entry that shadows an existing fixture name")
    void testRejectsSetShadowingFixture() {
        Scenario invalid = scenario(new StepSpec("set", Map.of("user", "1")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("user")
                .hasMessageContaining("shadow");
    }

    @Test
    @DisplayName("Should reject a set: entry that shadows an earlier set: entry")
    void testRejectsSetShadowingEarlierSet() {
        Scenario invalid = scenario(
                new StepSpec("set", Map.of("greeting", "1")),
                new StepSpec("set", Map.of("greeting", "2")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("greeting")
                .hasMessageContaining("shadow");
    }

    @Test
    @DisplayName("Should reject an as: written as a ${...} reference instead of a literal name")
    void testRejectsAsWrittenAsReference() {
        Scenario invalid = scenario(new StepSpec("ldap.create",
                Map.of("dn", "cn=x,ou=users", "as", "${user.userId}")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.create")
                .hasMessageContaining("as:")
                .hasMessageContaining("literal");
    }

    @Test
    @DisplayName("Should reject an as: written as a js:{ } expression instead of a literal name")
    void testRejectsAsWrittenAsExpression() {
        Scenario invalid = scenario(new StepSpec("ldap.create",
                Map.of("dn", "cn=x,ou=users", "as", "js:{ 'created' }")));

        assertThatThrownBy(() -> validator.validate(invalid, "bad.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("bad.yaml")
                .hasMessageContaining("ldap.create")
                .hasMessageContaining("literal");
    }

    @Test
    @DisplayName("Should reject a step type registry containing 'set', since set is reserved for the engine")
    void testRejectsSetStepTypeInRegistry() {
        StepType reservedSet = new StepType() {
            public String name() { return "set"; }
            public Set<String> requiredParams() { return Set.of(); }
            public StepOutcome execute(Map<String, Object> params, boolean captureRequested) { return StepOutcome.success("ok"); }
        };

        assertThatThrownBy(() -> new ScenarioValidator(
                Map.of("set", reservedSet),
                Set.of("random-user")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set")
                .hasMessageContaining("reserved");
    }

    private static Scenario scenarioWithCleanup(List<StepSpec> steps, StepSpec... cleanup) {
        return new Scenario("Test", null,
                Map.of("user", new FixtureSpec("random-user", Map.of())),
                steps, List.of(cleanup));
    }

    private static final List<StepSpec> CREATE_AS_CREATED = List.of(
            new StepSpec("ldap.create", Map.of("dn", "cn=${user.userId}", "as", "created")));

    @Test
    @DisplayName("Cleanup may reference a name bound by any step")
    void testCleanupReferencesAnyBinding() {
        assertThatCode(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "${created}"))), "c.yaml"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Cleanup may not reference a name bound nowhere")
    void testCleanupRejectsUnknownReference() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "${nowhere}"))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cleanup entry 1 (ldap.create)")
                .hasMessageContaining("nowhere");
    }

    @Test
    @DisplayName("Cleanup rejects an assertion")
    void testCleanupRejectsAssertion() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.verify", Map.of("dn", "cn=x", "expect", Map.of()))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cleanup entry 1 (ldap.verify)")
                .hasMessageContaining("assertion");
    }

    @Test
    @DisplayName("Cleanup rejects wait:")
    void testCleanupRejectsWait() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "cn=x", "wait", true))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'wait' is not allowed on a cleanup entry");
    }

    @Test
    @DisplayName("Cleanup rejects as:")
    void testCleanupRejectsAs() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "cn=x", "as", "again"))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'as' is not allowed on a cleanup entry");
    }

    @Test
    @DisplayName("Cleanup rejects set:")
    void testCleanupRejectsSet() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("set", Map.of("x", "1"))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cleanup entry 1 (set)")
                .hasMessageContaining("set: is not allowed");
    }

    @Test
    @DisplayName("Cleanup rejects an unknown step type, a missing required parameter and an unknown parameter")
    void testCleanupRejectsBadEntries() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.nope", Map.of())), "c.yaml"))
                .hasMessageContaining("cleanup entry 1 (ldap.nope)").hasMessageContaining("unknown step type");
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of())), "c.yaml"))
                .hasMessageContaining("missing required parameter 'dn'");
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "cn=x", "bogus", 1))), "c.yaml"))
                .hasMessageContaining("unknown parameter 'bogus'");
    }

    @Test
    @DisplayName("Should reject db.execute's removed undo: parameter")
    void testDbExecuteRejectsUndo() {
        ScenarioValidator dbValidator = new ScenarioValidator(
                Map.of("db.execute", new DbExecuteStep(null)),
                Set.of("random-user"));

        Scenario withUndo = scenario(new StepSpec("db.execute", Map.of(
                "database", "test-db",
                "statement", "DELETE FROM users WHERE user_id = ?",
                "undo", Map.of("statement", "x"))));

        assertThatThrownBy(() -> dbValidator.validate(withUndo, "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("unknown parameter 'undo'");
    }

    @Test
    @DisplayName("Should reject a misspelled root inside js:{} at load time")
    void testRejectsUnknownRootInsideExpression() {
        assertThatThrownBy(() -> validator.validate(scenario(
                new StepSpec("ldap.create", Map.of("dn", "js:{ usr.userId }"))), "t.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("usr");
    }

    @Test
    @DisplayName("Should reject a js:{} read of a name bound only by a later step")
    void testRejectsForwardReferenceInsideExpression() {
        assertThatThrownBy(() -> validator.validate(scenario(
                new StepSpec("ldap.create", Map.of("dn", "js:{ created + ',ou=users' }")),
                new StepSpec("ldap.create", Map.of("dn", "cn=x", "as", "created"))), "t.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("created");
    }

    @Test
    @DisplayName("Cleanup js:{} may read a name bound by any step")
    void testCleanupExpressionReadsAnyBinding() {
        assertThatCode(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "js:{ created + ',x' }"))), "c.yaml"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Cleanup js:{} may not read a name bound nowhere")
    void testCleanupExpressionRejectsUnknownRoot() {
        assertThatThrownBy(() -> validator.validate(scenarioWithCleanup(CREATE_AS_CREATED,
                new StepSpec("ldap.create", Map.of("dn", "js:{ nowhere.dn }"))), "c.yaml"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cleanup entry 1")
                .hasMessageContaining("nowhere");
    }

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
    @DisplayName("A null check: (YAML 'check:' with no value, or 'check: ~') is reported, not an NPE")
    void testDeferredCheckRejectsNull() {
        Map<String, Object> params = new HashMap<>();
        params.put("check", null);

        assertThatThrownBy(() -> deferredValidator.validate(scenario(new StepSpec("probe.verify", params)), "t.yaml"))
                .isInstanceOf(ValidationException.class)
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

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    @Test
    @DisplayName("A description is accepted on an action, an assertion, set: and a cleanup entry")
    void testDescriptionAccepted() {
        Scenario described = scenarioWithCleanup(
                List.of(
                        new StepSpec("set", params("description", "Pick a DN", "dn", "cn=${user.userId},ou=users")),
                        new StepSpec("ldap.create", params("description", "Create ${user.userId}", "dn", "${dn}")),
                        new StepSpec("ldap.verify", params("description", "Check it", "dn", "${dn}",
                                "expect", Map.of("sn", "x")))),
                new StepSpec("ldap.create", params("description", "Tidy ${dn}", "dn", "${dn}")));

        assertThatCode(() -> validator.validate(described, "d.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A description must be text, not a map, a number or null")
    void testDescriptionMustBeText() {
        for (Object bad : new Object[]{Map.of("a", "b"), 42, null}) {
            Scenario scenario = scenario(new StepSpec("ldap.create", params("description", bad, "dn", "x")));

            assertThatThrownBy(() -> validator.validate(scenario, "d.yaml"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("step 1 (ldap.create): 'description' must be text")
                    .hasMessageNotContaining("js:");
        }
    }

    @Test
    @DisplayName("A description may not be a js:{} expression")
    void testDescriptionRejectsExpression() {
        Scenario scenario = scenario(new StepSpec("ldap.create",
                params("description", "js:{ user.userId }", "dn", "x")));

        assertThatThrownBy(() -> validator.validate(scenario, "d.yaml"))
                .hasMessageContaining("'description' must be text; js:{ … } is not allowed here");

        Scenario inCleanup = scenarioWithCleanup(List.of(new StepSpec("ldap.create", params("dn", "x"))),
                new StepSpec("ldap.create", params("description", "js:{ 1 }", "dn", "x")));
        assertThatThrownBy(() -> validator.validate(inCleanup, "d.yaml"))
                .hasMessageContaining("cleanup entry 1 (ldap.create): 'description' must be text; js:{ … } is not allowed here");
    }

    @Test
    @DisplayName("A description's ${…} names must be known, with the usual scope rules")
    void testDescriptionReferences() {
        Scenario unknown = scenario(new StepSpec("ldap.create", params("description", "for ${nobody}", "dn", "x")));
        assertThatThrownBy(() -> validator.validate(unknown, "d.yaml"))
                .hasMessageContaining("references '${nobody...}'");

        Scenario laterBinding = scenarioWithCleanup(
                List.of(new StepSpec("ldap.create", params("dn", "x", "as", "created"))),
                new StepSpec("ldap.create", params("description", "undo ${created}", "dn", "x")));
        assertThatCode(() -> validator.validate(laterBinding, "d.yaml")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("On set:, description is the step's description and binds no variable")
    void testDescriptionOnSetBindsNothing() {
        Scenario scenario = scenario(
                new StepSpec("set", params("description", "names", "dn", "x")),
                new StepSpec("ldap.create", params("dn", "${description}")));

        assertThatThrownBy(() -> validator.validate(scenario, "d.yaml"))
                .hasMessageContaining("step 2 (ldap.create)")
                .hasMessageContaining("references '${description...}'");

        Scenario onlyDescription = scenario(new StepSpec("set", params("description", "nothing")));
        assertThatThrownBy(() -> validator.validate(onlyDescription, "d.yaml"))
                .hasMessageContaining("a set: step needs at least one name to bind");
    }
}
