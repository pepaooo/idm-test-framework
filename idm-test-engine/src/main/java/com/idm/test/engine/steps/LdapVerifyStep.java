package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.connector.ldap.client.LdapValueMatcher;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Asserts that an LDAP entry carries the expected attribute values — a value, the exact set of a
 * multi-valued attribute in any order, or {@code contains}/{@code excludes} (see LdapValueMatcher).
 * <p>
 * Retryable: provisioning is asynchronous, so the engine may call this repeatedly under a
 * {@code wait} until it passes or the timeout expires.
 */
public final class LdapVerifyStep implements StepType {

    private final LdapDirectories directories;

    public LdapVerifyStep(LdapDirectories directories) {
        this.directories = directories;
    }

    @Override
    public String name() {
        return "ldap.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Set<String> requiredParams() {
        // Only 'dn' is unconditionally required. 'expect' and 'exists' are each optional on
        // their own but exactly one must be present — a rule requiredParams() cannot express,
        // so execute() enforces it before any I/O. See rejectMalformedAssertion.
        return Set.of("dn");
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of("dn", "expect", "exists", "wait", "directory");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String dn = String.valueOf(params.get("dn"));
        LdapTarget target = LdapTarget.of("ldap.verify", params, directories);
        if (target.isFailure()) {
            return target.failure();
        }
        EdirectoryConnector connector = target.connector();
        String where = target.at(dn);

        StepOutcome rejection = rejectMalformedAssertion(params, where);
        if (rejection != null) {
            return rejection;
        }

        if (params.containsKey("exists")) {
            return verifyExistence(connector, dn, where, (Boolean) params.get("exists"));
        }

        Map<String, Object> expected = (Map<String, Object>) params.get("expect");

        OperationResult<Boolean> result = connector.verifyAttributes(dn, expected);

        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "ldap.verify failed for " + where + ": " + result.getMessage());
        }

        if (!captureRequested) {
            // Nobody declared as: for this step, so there is nothing to capture — skip the
            // follow-up read entirely rather than paying for it on every retry attempt of an
            // assertion nobody is going to read the value of.
            return StepOutcome.success("verified " + where);
        }

        // Read once, only after verification passes and only because a capture was actually
        // requested, so a polling assertion does not double its read load on every attempt.
        OperationResult<Map<String, Object>> entry = connector.readAttributes(dn);
        if (!entry.isSuccess() || entry.getData().isEmpty()) {
            // Verification passed but the entry could not be re-read for its as: capture.
            // Reporting success with an empty map would let `${v.cn}` miss silently later, so
            // this fails the step instead of fabricating a value.
            return StepOutcome.failure("ldap.verify passed for " + where
                    + " but the follow-up read for its as: capture failed: " + entry.getMessage());
        }

        // Every value of every attribute: a multi-valued one is a list, so it can be written
        // back by an ldap.update in cleanup.
        Map<String, Object> captured = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        captured.putAll(entry.getData().get());
        captured.put("dn", dn);
        return StepOutcome.success("verified " + where, captured);
    }

    /**
     * Asserts only presence or absence, with no attribute comparison.
     * <p>
     * Absence is what makes a de-provisioning testable: after a delete, the honest assertion
     * is that the entry is gone, and until now the DSL could only assert that an entry still
     * had particular values.
     */
    private StepOutcome verifyExistence(EdirectoryConnector connector, String dn, String where,
                                        boolean shouldExist) {
        OperationResult<Boolean> result = connector.exists(dn);

        if (!result.isSuccess()) {
            return StepOutcome.failure("ldap.verify could not check whether " + where
                    + " exists: " + result.getMessage());
        }

        boolean present = Boolean.TRUE.equals(result.getData().orElse(false));
        if (present != shouldExist) {
            return StepOutcome.failure("ldap.verify expected " + where + " to be "
                    + (shouldExist ? "present" : "absent") + " but it was "
                    + (present ? "present" : "absent"));
        }

        return StepOutcome.success(
                "verified " + where + " is " + (shouldExist ? "present" : "absent"));
    }

    /**
     * Exactly one of {@code expect} or {@code exists} must be supplied, and each must have the
     * right shape.
     * <p>
     * Making {@code expect} optional is what creates the risk here: "neither supplied" would
     * verify nothing at all while looking like an assertion. That defect class has been found
     * eight times in this project, so the rule is stated positively and checked before any I/O.
     */
    private static StepOutcome rejectMalformedAssertion(Map<String, Object> params, String where) {
        boolean hasExpect = params.containsKey("expect");
        boolean hasExists = params.containsKey("exists");

        if (hasExpect && hasExists) {
            return StepOutcome.finalFailure("ldap.verify for " + where + " supplies both 'expect' and "
                    + "'exists'; expect already implies the entry exists, so give only one");
        }
        if (!hasExpect && !hasExists) {
            return StepOutcome.finalFailure("ldap.verify for " + where + " supplies neither 'expect' "
                    + "nor 'exists' — it would verify nothing. Assert attributes with expect:, "
                    + "or presence with exists:");
        }
        if (hasExists && !(params.get("exists") instanceof Boolean)) {
            return StepOutcome.finalFailure("ldap.verify for " + where + " has an 'exists' that is not "
                    + "true or false");
        }
        if (hasExpect) {
            if (!(params.get("expect") instanceof Map<?, ?> expect)) {
                return StepOutcome.finalFailure("ldap.verify for " + where
                        + " has an expect that is not a map of attribute names to expected values");
            }
            if (expect.isEmpty()) {
                return StepOutcome.finalFailure("ldap.verify for " + where
                        + " has an empty expect: block — it would verify nothing");
            }

            // The hole one level in: a misspelled attribute name (`titel:` instead of
            // `title:`) parses as an entry whose value is null, indistinguishable from an
            // explicit `expect: { telephoneNumber: null }` written to assert an attribute is
            // gone — and EdirectoryConnector.valuesEqual(null, null) is true, so either would
            // report "verified" having compared nothing. This is the same trap
            // DbVerifyStep.rejectMalformedExpect guards for its row matcher, found for the
            // eighth time in this project.
            for (Map.Entry<?, ?> attribute : expect.entrySet()) {
                if (attribute.getValue() == null) {
                    return StepOutcome.finalFailure("ldap.verify for " + where
                            + " has a null expected value for attribute '" + attribute.getKey()
                            + "' — a missing or misspelled attribute name parses the same way "
                            + "and would verify nothing. Check for a typo, or assert exists: "
                            + "false to check for absence instead.");
                }
                String problem = LdapValueMatcher.shapeProblem(attribute.getValue());
                if (problem != null) {
                    return StepOutcome.finalFailure("ldap.verify for " + where + ": attribute '"
                            + attribute.getKey() + "' " + problem);
                }
            }
        }

        return null;
    }
}
