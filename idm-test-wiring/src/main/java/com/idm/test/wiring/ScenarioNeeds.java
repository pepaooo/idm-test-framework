package com.idm.test.wiring;

import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    public static final String LDAP = "ldap";
    public static final String DB = "db";
    public static final String REST = "rest";
    private static final String ROLES = "roles";
    private static final String RESOURCES = "resources";
    /** The parameter that names the connection a step of each named kind uses. */
    private static final Map<String, String> NAME_KEYS = Map.of(LDAP, "directory", DB, "database", REST, "target");

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
        for (StepSpec step : entries(scenarios)) {
            String kind = kind(step.type());
            if (kind == null) {
                continue;
            }
            Object name = NAME_KEYS.containsKey(kind) ? step.params().get(NAME_KEYS.get(kind)) : null;
            switch (kind) {
                case LDAP -> {
                    if (name == null) {
                        directories.add(DEFAULT_DIRECTORY);
                    } else {
                        addLiteral(directories, name);
                    }
                }
                case DB -> addLiteral(databases, name);
                case REST -> addLiteral(targets, name);
                case ROLES -> roles = true;
                default -> resources = true;
            }
        }
        return new ScenarioNeeds(directories, databases, roles, resources, targets);
    }

    /**
     * The kinds of connection ({@value #LDAP}, {@value #DB}, {@value #REST}) that a step of the
     * scenarios names only at run time: a name containing {@code ${}, or a db.* or rest.* step that
     * names none. {@link #of} skips those names, so such a step may use any connection of its kind.
     */
    public static Set<String> unresolvedKinds(List<Scenario> scenarios) {
        Set<String> kinds = new TreeSet<>();
        for (StepSpec step : entries(scenarios)) {
            String kind = kind(step.type());
            String key = kind == null ? null : NAME_KEYS.get(kind);
            if (key == null) {
                continue;
            }
            Object name = step.params().get(key);
            if (name == null ? !LDAP.equals(kind) : String.valueOf(name).contains("${")) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    private static List<StepSpec> entries(List<Scenario> scenarios) {
        List<StepSpec> entries = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            entries.addAll(scenario.steps());
            entries.addAll(scenario.cleanup());
        }
        return entries;
    }

    /** The connection kind a step type uses, or null for a step that uses none. */
    private static String kind(String type) {
        if (type.startsWith("ldap.")) {
            return LDAP;
        } else if (type.startsWith("db.")) {
            return DB;
        } else if (type.startsWith("roles.")) {
            return ROLES;
        } else if (type.startsWith("resources.")) {
            return RESOURCES;
        } else if (type.startsWith("rest.") || type.startsWith("http.")) {
            return REST;
        }
        return null;
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
