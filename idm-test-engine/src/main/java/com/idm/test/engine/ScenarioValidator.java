package com.idm.test.engine;

import com.idm.test.engine.expr.ExpressionEvaluator;
import com.idm.test.engine.expr.ExpressionEvaluator.ExpressionException;
import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.model.WaitSpec;
import com.idm.test.engine.spi.StepType;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Checks a scenario before anything executes.
 * <p>
 * Validating up front matters more here than in most systems: a scenario can spend minutes
 * waiting for provisioning, and discovering a typo after that wait is expensive. Every
 * message names the file and the step, because the reader may not be a Java developer.
 */
public final class ScenarioValidator {

    /**
     * Stand-ins for whatever timeout/poll defaults the engine will actually run with. Only
     * used to probe {@link WaitSpec#parse} for a shape or duration-string error; when a
     * {@code wait} value is well-formed, the real defaults never come into play here.
     */
    private static final Duration PLACEHOLDER_DURATION = Duration.ofSeconds(1);

    /** Reserved: handled by the engine itself rather than by a registered step type. */
    private static final String SET_STEP = "set";

    /**
     * The rule for a literal {@code as:}/{@code set:} name: a letter or underscore, followed by
     * letters, digits or underscores. Deliberately excludes {@code $}, {@code {} and {@code }} —
     * the characters that would let {@code ${...}} or {@code js:{ }} sneak a computed or
     * referenced name past validation (see the "as: is read raw by the validator" finding).
     */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<String, StepType> registry;
    private final Set<String> generatorNames;
    private final ExpressionEvaluator evaluator;

    /**
     * @param registry       known step types, by name
     * @param generatorNames fixture generator names {@code FixtureFactory} understands —
     *                       taken as a parameter, rather than read off {@code FixtureFactory}
     *                       directly, so this module has no hidden coupling to it; callers
     *                       wire the two together explicitly (see {@code FixtureFactory
     *                       .SUPPORTED_GENERATORS})
     */
    public ScenarioValidator(Map<String, StepType> registry, Set<String> generatorNames) {
        this(registry, generatorNames, new ExpressionEvaluator());
    }

    /**
     * @param registry       known step types, by name
     * @param generatorNames fixture generator names {@code FixtureFactory} understands
     * @param evaluator      used only to check a {@code js:{ }} expression compiles — see
     *                       {@link ExpressionEvaluator#validate(String)}. Never used to evaluate
     *                       an expression: validation must not run scenario logic.
     */
    public ScenarioValidator(
            Map<String, StepType> registry, Set<String> generatorNames,
            ExpressionEvaluator evaluator) {
        if (registry.containsKey(SET_STEP)) {
            throw new IllegalArgumentException(
                    "'" + SET_STEP + "' is a reserved step type and cannot be registered");
        }
        this.registry = registry;
        this.generatorNames = generatorNames;
        this.evaluator = evaluator;
    }

    /**
     * @throws ValidationException on the first problem found
     */
    public void validate(Scenario scenario, String sourceName) {
        if (scenario.steps().isEmpty()) {
            throw new ValidationException(sourceName, "a scenario needs at least one step");
        }

        for (Map.Entry<String, FixtureSpec> fixture : scenario.fixtures().entrySet()) {
            String generator = fixture.getValue().generate();
            if (generator != null && !generatorNames.contains(generator)) {
                throw new ValidationException(sourceName,
                        "fixture '" + fixture.getKey() + "' uses unknown generator '"
                                + generator + "'. Available: " + new TreeSet<>(generatorNames));
            }
        }

        Set<String> known = new LinkedHashSet<>(scenario.fixtures().keySet());

        for (int i = 0; i < scenario.steps().size(); i++) {
            StepSpec step = scenario.steps().get(i);

            if (SET_STEP.equals(step.type())) {
                if (step.params().isEmpty()) {
                    throw new ValidationException(sourceName, i, step.type(),
                            "a set: step needs at least one name to bind");
                }
                for (Map.Entry<String, Object> entry : step.params().entrySet()) {
                    requireWellFormedExpressions(entry.getValue(), sourceName, i, step.type());
                    requireKnownReferences(entry.getValue(), known, sourceName, i, step.type());
                    requireFreshBinding(entry.getKey(), known, sourceName, i, step.type(), "set:");
                    known.add(entry.getKey());   // later entries may read earlier ones
                }
                continue;
            }

            StepType type = registry.get(step.type());

            if (type == null) {
                throw new ValidationException(sourceName, i, step.type(),
                        "unknown step type. Available: " + new TreeSet<>(registry.keySet()));
            }

            // Don't trust acceptedParams() alone: an override (as LdapCreateStep's hardcoded
            // Set.of(...) demonstrates, and every later step type will copy that template)
            // could drop a required parameter or 'wait' without anyone noticing, and this
            // validator would then reject a perfectly valid scenario as if the parameter
            // were unknown. The effective accepted set always includes requiredParams() and,
            // for a retryable step, 'wait' — regardless of what acceptedParams() returns. See
            // StepType.acceptedParams() javadoc.
            Set<String> accepted = new LinkedHashSet<>(type.acceptedParams());
            accepted.addAll(type.requiredParams());
            accepted.add("as");                 // any step's result may be captured
            if (type.retryable()) {
                accepted.add("wait");
            }

            for (String required : new TreeSet<>(type.requiredParams())) {
                if (!step.params().containsKey(required)) {
                    throw new ValidationException(sourceName, i, step.type(),
                            "missing required parameter '" + required + "'"
                                    + unrecognizedHint(step, accepted));
                }
            }

            // Checked before the generic accepted-parameter scan below so this step type's
            // own explanation wins: 'wait' on a non-retryable step is never in that step's
            // accepted set either, and the two checks must not both fire for the same cause.
            if (step.params().containsKey("wait")) {
                if (!type.retryable()) {
                    throw new ValidationException(sourceName, i, step.type(),
                            "'wait' is only valid on assertions, and this step is an action");
                }

                // WaitSpec.parse is otherwise only called once the step actually runs, which
                // is exactly the late failure this validator exists to prevent: a bad shape
                // (e.g. a string where a map or `true` belongs) or a bad duration string
                // (e.g. '30x') would only surface after every earlier step had executed.
                //
                // NOTE: this parses 'wait' before interpolation, while ScenarioEngine parses
                // it after resolving ${...} references (see runStep). A future config-style
                // fixture producing something like `wait: {timeout: ${cfg.timeout}}` would
                // therefore be falsely rejected here even though the engine would resolve it
                // fine at runtime. Not reachable today: every current fixture generator only
                // ever produces user data, never a value a 'wait' would reference.
                try {
                    WaitSpec.parse(step.params().get("wait"), PLACEHOLDER_DURATION, PLACEHOLDER_DURATION);
                } catch (IllegalArgumentException e) {
                    throw new ValidationException(sourceName, i, step.type(),
                            "invalid 'wait': " + e.getMessage());
                }
            }

            for (String supplied : step.params().keySet()) {
                if (!accepted.contains(supplied)) {
                    throw new ValidationException(sourceName, i, step.type(),
                            "unknown parameter '" + supplied + "'. " + step.type()
                                    + " accepts: " + new TreeSet<>(accepted));
                }
            }

            requireWellFormedExpressions(step.params(), sourceName, i, step.type());
            requireKnownReferences(step.params(), known, sourceName, i, step.type());

            Object asRaw = step.params().get("as");
            if (asRaw != null) {
                String asName = String.valueOf(asRaw);
                if (!IDENTIFIER.matcher(asName).matches()) {
                    throw new ValidationException(sourceName, i, step.type(),
                            "'as: " + asName + "' is not a valid name — as: must be a literal "
                                    + "identifier (a letter or underscore, followed by letters, "
                                    + "digits or underscores), not an expression or a reference");
                }
                requireFreshBinding(asName, known, sourceName, i, step.type(), "as:");
                known.add(asName);
            }
        }
    }

    /**
     * Rejects an {@code as:} or {@code set:} name that is already bound — by a fixture, an
     * earlier {@code as:}, or an earlier {@code set:} entry. Without this, {@code as: user}
     * silently overwrites the {@code user} fixture: the validator would see {@code known}
     * already contains {@code user} and pass, and {@code ${user.userId}} would then fail at run
     * time pointing at the wrong step.
     */
    private static void requireFreshBinding(
            String name, Set<String> known, String sourceName, int index, String stepType,
            String keyword) {
        if (known.contains(name)) {
            throw new ValidationException(sourceName, index, stepType,
                    keyword + " '" + name + "' shadows an existing name — '" + name
                            + "' is already bound by a fixture, an earlier as:, or an earlier "
                            + "set: entry");
        }
    }

    /**
     * Checks every {@code js:{ ... }} expression in {@code value} is well-formed, before
     * anything runs: not embedded in a larger string, properly closed, non-empty, and
     * syntactically valid JavaScript. {@code Interpolator.references} deliberately swallows a
     * malformed expression (it is a query and must not throw), so nothing else in this
     * validator would otherwise catch one — it would only surface once the scenario actually
     * ran, possibly after a multi-minute wait on an earlier step.
     * <p>
     * Compiles but never evaluates: see {@link ExpressionEvaluator#validate(String)}. Expressions
     * compute values, they never perform actions, and validating one must not be an action
     * either.
     */
    private void requireWellFormedExpressions(
            Object value, String sourceName, int index, String stepType) {
        if (value instanceof String text) {
            String expression;
            try {
                expression = Interpolator.expressionBody(text);
            } catch (IllegalArgumentException malformed) {
                throw new ValidationException(sourceName, index, stepType, malformed.getMessage());
            }
            if (expression != null) {
                try {
                    evaluator.validate(expression);
                } catch (ExpressionException syntaxError) {
                    throw new ValidationException(
                            sourceName, index, stepType, syntaxError.getMessage());
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> requireWellFormedExpressions(v, sourceName, index, stepType));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> requireWellFormedExpressions(v, sourceName, index, stepType));
        }
    }

    /**
     * Every {@code ${...}} reference must name something that exists at this point in the
     * scenario. Checking order matters: a reference to a name bound by a *later* step is a
     * mistake worth catching at load, not three minutes into a provisioning wait.
     */
    private static void requireKnownReferences(
            Object value, Set<String> known, String sourceName, int index, String stepType) {

        for (String reference : Interpolator.references(value)) {
            if (!known.contains(reference)) {
                throw new ValidationException(sourceName, index, stepType,
                        "references '${" + reference + "...}' but nothing named '" + reference
                                + "' has been declared or bound at this point");
            }
        }
    }

    /**
     * Flags supplied parameter names that {@code accepted} does not recognize, so a missing
     * required parameter caused by a typo (e.g. {@code expct} instead of {@code expect})
     * names the stray key instead of only the parameter that never showed up. Returns "" when
     * every supplied name is recognized, so the plain "missing required parameter" message is
     * unchanged in the common case.
     * <p>
     * This only enriches the message the required-parameter check already throws; it does not
     * move or duplicate that check, so it cannot double-report alongside the 'wait' or
     * accepted-parameter checks below.
     */
    private static String unrecognizedHint(StepSpec step, Set<String> accepted) {
        Set<String> unrecognized = new TreeSet<>(step.params().keySet());
        unrecognized.removeAll(accepted);
        if (unrecognized.isEmpty()) {
            return "";
        }
        return " (also supplied but not recognized, possibly a typo: " + unrecognized + ")";
    }
}
