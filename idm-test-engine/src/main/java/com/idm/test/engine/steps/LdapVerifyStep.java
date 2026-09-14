package com.idm.test.engine.steps;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;

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
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params) {
        String dn = String.valueOf(params.get("dn"));
        Map<String, Object> expected =
                (Map<String, Object>) params.getOrDefault("expect", Map.of());

        OperationResult<Boolean> result = connector.verifyAttributes(dn, expected);

        if (!result.isSuccess()) {
            return StepOutcome.failure(
                    "ldap.verify failed for " + dn + ": " + result.getMessage());
        }

        return StepOutcome.success("verified " + dn);
    }
}
