package com.idm.test.wiring;

import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the selected scenarios use: LDAP directories (by name; {@link #DEFAULT_DIRECTORY} for a
 * step that names none), named databases, the Role and Resource services, and
 * REST targets. The real-environment guards check, and the run contacts, only these. Read
 * from every scenario's steps and cleanup. A name containing {@code ${} is only known at run
 * time and is skipped; a null name is skipped; anything else is converted with
 * {@code String.valueOf}, as the steps themselves do.
 */
public record ScenarioNeeds(Set<String> directories, Set<String> databases, boolean roles, boolean resources,
                     Set<String> restTargets) {

    /** Stands for "the default directory" in {@link #directories()}: an ldap.* step that names none. */
    public static final String DEFAULT_DIRECTORY = "<default>";

    public ScenarioNeeds {
        directories = Collections.unmodifiableSortedSet(new TreeSet<>(directories));
        databases = Collections.unmodifiableSortedSet(new TreeSet<>(databases));
        restTargets = Collections.unmodifiableSortedSet(new TreeSet<>(restTargets));
    }

    public static ScenarioNeeds of(List<Scenario> scenarios) {
        Set<String> directories = new TreeSet<>();
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
                    Object directory = step.params().get("directory");
                    if (directory == null) {
                        directories.add(DEFAULT_DIRECTORY);
                    } else {
                        addLiteral(directories, directory);
                    }
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
        return new ScenarioNeeds(directories, databases, roles, resources, targets);
    }

    /** Whether any ldap.* step is used. */
    public boolean ldap() {
        return !directories.isEmpty();
    }

    /** Whether any SOAP service is used, which is when guard 5 checks SOAP credentials. */
    public boolean soap() {
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
