package com.idm.test.engine;

import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.model.WaitSpec;
import com.idm.test.engine.spi.StepType;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

    private final Map<String, StepType> registry;
    private final Set<String> generatorNames;

    /**
     * @param registry       known step types, by name
     * @param generatorNames fixture generator names {@code FixtureFactory} understands —
     *                       taken as a parameter, rather than read off {@code FixtureFactory}
     *                       directly, so this module has no hidden coupling to it; callers
     *                       wire the two together explicitly (see {@code FixtureFactory
     *                       .SUPPORTED_GENERATORS})
     */
    public ScenarioValidator(Map<String, StepType> registry, Set<String> generatorNames) {
        this.registry = registry;
        this.generatorNames = generatorNames;
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

        Set<String> known = scenario.fixtures().keySet();

        for (int i = 0; i < scenario.steps().size(); i++) {
            StepSpec step = scenario.steps().get(i);
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

            for (String reference : Interpolator.references(step.params())) {
                if (!known.contains(reference)) {
                    throw new ValidationException(sourceName, i, step.type(),
                            "references '${" + reference + "...}' but no fixture named '"
                                    + reference + "' is declared");
                }
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
