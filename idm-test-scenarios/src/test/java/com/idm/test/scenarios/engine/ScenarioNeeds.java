package com.idm.test.scenarios.engine;

import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the selected scenarios use: LDAP, named databases, the Role and Resource services, and
 * REST targets. The real-environment guards check, and the run contacts, only these. Read
 * from every scenario's steps and cleanup. A name containing {@code ${} is only known at run
 * time and is skipped; a null name is skipped; anything else is converted with
 * {@code String.valueOf}, as the steps themselves do.
 */
record ScenarioNeeds(boolean ldap, Set<String> databases, boolean roles, boolean resources,
                     Set<String> restTargets) {

    ScenarioNeeds {
        databases = Collections.unmodifiableSortedSet(new TreeSet<>(databases));
        restTargets = Collections.unmodifiableSortedSet(new TreeSet<>(restTargets));
    }

    static ScenarioNeeds of(List<Scenario> scenarios) {
        boolean ldap = false;
        boolean roles = false;
        boolean resources = false;
        Set<String> databases = new TreeSet<>();
        Set<String> targets = new TreeSet<>();
        for (Scenario scenario : scenarios) {
            List<StepSpec> entries = new ArrayList<>(scenario.steps());
            entries.addAll(scenario.cleanup());
            for (StepSpec step : entries) {
                String type = step.type();
                if (type.startsWith("ldap.")) {
                    ldap = true;
                } else if (type.startsWith("db.")) {
                    addLiteral(databases, step.params().get("database"));
                } else if (type.startsWith("roles.")) {
                    roles = true;
                } else if (type.startsWith("resources.")) {
                    resources = true;
                } else if (type.startsWith("rest.") || type.startsWith("http.")) {
                    addLiteral(targets, step.params().get("target"));
                }
            }
        }
        return new ScenarioNeeds(ldap, databases, roles, resources, targets);
    }

    /** Whether any SOAP service is used, which is when guard 5 checks SOAP credentials. */
    boolean soap() {
        return roles || resources;
    }

    private static void addLiteral(Set<String> into, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (!text.contains("${")) {
            into.add(text);
        }
    }
}
