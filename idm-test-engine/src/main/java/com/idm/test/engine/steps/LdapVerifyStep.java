package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Set;

/**
 * Asserts that an LDAP entry carries the expected attribute values.
 * <p>
 * Retryable: provisioning is asynchronous, so the engine may call this repeatedly under a
 * {@code wait} until it passes or the timeout expires.
 */
public final class LdapVerifyStep implements StepType {

    private final EdirectoryConnector connector;

    public LdapVerifyStep(EdirectoryConnector connector) {
        this.connector = connector;
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
        // 'expect' is required, not defaulted to empty: an empty map would make every
        // comparison in EdirectoryConnector.verifyAttributes vacuously true, so a typo'd
        // key (e.g. "expct") must fail validation instead of silently verifying nothing.
        return Set.of("dn", "expect");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String dn = String.valueOf(params.get("dn"));
        Map<String, Object> expected =
                (Map<String, Object>) params.getOrDefault("expect", Map.of());

        if (expected.isEmpty()) {
            // requiredParams() catches a missing or misspelled expect:, but a present-but-
            // empty one slips past a containsKey check and makes every comparison in
            // verifyAttributes vacuously true — "verified" having compared nothing. Same
            // trap, one step further in; db.verify guards the identical case.
            return StepOutcome.failure("ldap.verify for " + dn
                    + " has an empty expect: block — it would verify nothing");
        }

        OperationResult<Boolean> result = connector.verifyAttributes(dn, expected);

        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "ldap.verify failed for " + dn + ": " + result.getMessage());
        }

        if (!captureRequested) {
            // Nobody declared as: for this step, so there is nothing to capture — skip the
            // follow-up read entirely rather than paying for it on every retry attempt of an
            // assertion nobody is going to read the value of.
            return StepOutcome.success("verified " + dn);
        }

        // Read once, only after verification passes and only because a capture was actually
        // requested, so a polling assertion does not double its read load on every attempt.
        OperationResult<LdapUser> entry = connector.read(dn);
        if (!entry.isSuccess() || entry.getData().isEmpty()) {
            // Verification passed but the entry could not be re-read for its as: capture.
            // Reporting success with an empty map would let `${v.cn}` miss silently later, so
            // this fails the step instead of fabricating a value.
            return StepOutcome.failure("ldap.verify passed for " + dn
                    + " but the follow-up read for its as: capture failed: " + entry.getMessage());
        }

        return StepOutcome.success("verified " + dn, null, LdapAttributes.of(entry.getData().get()));
    }
}
