package com.idm.test.scenarios.engine;

import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.connector.ldap.config.LdapProperties;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.result.RunInfo;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the report's description of a run from the Spring environment and the scenarios it
 * selected: its profiles, its -Dscenario filter, and the endpoints those scenarios use (the same
 * needs the real-environment guards check). URLs are sanitised by {@link RunInfo} itself.
 */
final class RunInfos {

    private RunInfos() {
    }

    static RunInfo of(String runner, Environment environment, List<Scenario> scenarios) {
        ScenarioNeeds needs = ScenarioNeeds.of(scenarios);
        Binder binder = Binder.get(environment);
        Map<String, String> endpoints = new LinkedHashMap<>();

        directoryUrls(environment, binder, needs.directories())
                .forEach((name, url) -> endpoints.put("LDAP " + name, url));

        Map<String, DatabaseProperties.DatabaseConfig> databases = binder
                .bind("idm.test.databases", Bindable.mapOf(String.class, DatabaseProperties.DatabaseConfig.class))
                .orElse(Map.of());
        for (String database : needs.databases()) {
            DatabaseProperties.DatabaseConfig config = databases.get(database);
            endpoints.put("Database " + database, config == null ? null : config.getJdbcUrl());
        }

        if (needs.roles()) {
            endpoints.put("Role service", environment.getProperty("idm.test.soap.url"));
        }
        if (needs.resources()) {
            endpoints.put("Resource service", environment.getProperty("idm.test.soap.resource-url"));
        }

        Map<String, RestProperties.RestApiConfig> targets = binder
                .bind("idm.test.rest", Bindable.mapOf(String.class, RestProperties.RestApiConfig.class))
                .orElse(Map.of());
        for (String target : needs.restTargets()) {
            RestProperties.RestApiConfig config = targets.get(target);
            endpoints.put("REST " + target, config == null ? null : config.getBaseUrl());
        }

        return new RunInfo(runner, Instant.now(), List.of(environment.getActiveProfiles()),
                System.getProperty(ScenarioSupport.SCENARIO_PROPERTY), endpoints);
    }

    /** Directory name to URL for the directories used, the default one resolved as the guards resolve it. */
    private static Map<String, String> directoryUrls(Environment environment, Binder binder, Set<String> used) {
        Map<String, LdapProperties> named = binder
                .bind("idm.test.directories", Bindable.mapOf(String.class, LdapProperties.class))
                .orElse(Map.of());
        String legacyUrl = environment.getProperty("idm.test.ldap.url");
        Set<String> names = new TreeSet<>(named.keySet());
        if (named.isEmpty() || (legacyUrl != null && !legacyUrl.isBlank())) {
            names.add(LdapDirectories.DEFAULT);
        }
        Optional<String> defaultName;
        try {
            defaultName = LdapDirectories.resolveDefault(environment.getProperty("idm.test.default-directory"), names);
        } catch (IllegalStateException unknownDefault) {
            defaultName = Optional.empty();
        }

        Map<String, String> urls = new LinkedHashMap<>();
        for (String requested : used) {
            String name = ScenarioNeeds.DEFAULT_DIRECTORY.equals(requested) ? defaultName.orElse(null) : requested;
            if (name == null) {
                continue;
            }
            LdapProperties config = named.get(name);
            urls.put(name, config != null ? config.getUrl()
                    : LdapDirectories.DEFAULT.equals(name) ? legacyUrl : null);
        }
        return urls;
    }
}
