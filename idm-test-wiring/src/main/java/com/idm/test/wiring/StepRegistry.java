package com.idm.test.wiring;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.engine.spi.StepType;
import com.idm.test.engine.steps.DbExecuteStep;
import com.idm.test.engine.steps.DbVerifyStep;
import com.idm.test.engine.steps.HttpRequestStep;
import com.idm.test.engine.steps.HttpVerifyStep;
import com.idm.test.engine.steps.LdapCreateStep;
import com.idm.test.engine.steps.LdapDeleteStep;
import com.idm.test.engine.steps.LdapUpdateStep;
import com.idm.test.engine.steps.LdapVerifyStep;
import com.idm.test.engine.steps.ResourcesGrantStep;
import com.idm.test.engine.steps.ResourcesRevokeStep;
import com.idm.test.engine.steps.RestRequestStep;
import com.idm.test.engine.steps.RestVerifyStep;
import com.idm.test.engine.steps.RolesGrantStep;
import com.idm.test.engine.steps.RolesRevokeStep;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The 14 step types, by name, wired to the connectors a run uses. */
public final class StepRegistry {

    private StepRegistry() {
    }

    public static Map<String, StepType> of(
            LdapDirectories ldapDirectories,
            MultiDatabaseConnector databaseConnector,
            RoleServiceConnector roleServiceConnector,
            ResourceServiceConnector resourceServiceConnector,
            IdmRestConnector restConnector,
            RestProperties restProperties,
            RestAuthenticator restAuthenticator) {
        Map<String, StepType> registry = new LinkedHashMap<>();
        for (StepType type : List.of(
                new LdapCreateStep(ldapDirectories),
                new LdapVerifyStep(ldapDirectories),
                new LdapUpdateStep(ldapDirectories),
                new LdapDeleteStep(ldapDirectories),
                new DbExecuteStep(databaseConnector),
                new DbVerifyStep(databaseConnector),
                new RolesGrantStep(roleServiceConnector),
                new RolesRevokeStep(roleServiceConnector),
                new ResourcesGrantStep(resourceServiceConnector),
                new ResourcesRevokeStep(resourceServiceConnector),
                new RestRequestStep(restConnector, restProperties, restAuthenticator),
                new RestVerifyStep(restConnector, restProperties, restAuthenticator),
                new HttpRequestStep(restConnector, restProperties, restAuthenticator),
                new HttpVerifyStep(restConnector, restProperties, restAuthenticator))) {
            registry.put(type.name(), type);
        }
        return registry;
    }

    /**
     * The same step types without collaborators, for validating scenarios without connecting
     * anywhere: the validator reads only {@code name()}, {@code retryable()},
     * {@code requiredParams()} and {@code acceptedParams()}.
     */
    public static Map<String, StepType> forValidation() {
        return of(null, null, null, null, null, null, null);
    }
}
