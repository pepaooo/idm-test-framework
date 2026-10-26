package com.idm.test.runner.ui.lab;

import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.wiring.DirectoryScenarioSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** A strict scan used before deletion; unreadable or dynamic scenarios are never treated as unused. */
final class ScenarioReferenceScan {

    record Result(List<String> referenced, List<String> uncertain) {
    }

    record Inspection(Map<String, List<StepSpec>> entries, List<String> invalid) {
    }

    static Inspection inspect(Path scenarios) {
        Map<String, List<StepSpec>> entries = new LinkedHashMap<>();
        List<String> invalid = new ArrayList<>();
        DirectoryScenarioSource source = new DirectoryScenarioSource(scenarios);
        List<String> names;
        try {
            names = source.names();
        } catch (IOException | RuntimeException e) {
            return new Inspection(Map.of(), List.of("scenarios/"));
        }
        for (String name : names) {
            Path path = scenarios.resolve(name);
            try {
                if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(scenarios.toRealPath())) {
                    invalid.add(name);
                    continue;
                }
                try (InputStream in = source.open(name)) {
                    if (in == null) {
                        invalid.add(name);
                        continue;
                    }
                    Scenario scenario = ScenarioLoader.load(in, name);
                    entries.put(name, entries(scenario));
                }
            } catch (IOException | RuntimeException e) {
                invalid.add(name);
            }
        }
        return new Inspection(Map.copyOf(entries), List.copyOf(invalid));
    }

    static Result scan(Inspection inspection, LabSettings.SystemRef ref, String defaultDirectoryId,
                       boolean hasNamedDefaultDirectory) {
        List<String> referenced = new ArrayList<>();
        List<String> uncertain = new ArrayList<>(inspection.invalid());
        for (Map.Entry<String, List<StepSpec>> scenario : inspection.entries().entrySet()) {
            String name = scenario.getKey();
            boolean used = false;
            for (StepSpec step : scenario.getValue()) {
                String type = step.type();
                Object target = null;
                boolean relevant = false;
                if (type.startsWith("ldap.") && ref.kind() == LabSettings.Kind.LDAP) {
                    target = step.params().get("directory");
                    relevant = true;
                } else if (type.startsWith("db.") && ref.kind() == LabSettings.Kind.DATABASE) {
                    target = step.params().get("database");
                    relevant = true;
                } else if ((type.startsWith("rest.") || type.startsWith("http.")) && ref.kind() == LabSettings.Kind.REST) {
                    target = step.params().get("target");
                    relevant = true;
                } else if ((type.startsWith("roles.") || type.startsWith("resources.")) && ref.kind() == LabSettings.Kind.SOAP) {
                    used = true;
                }
                if (!relevant) continue;
                if (target != null && String.valueOf(target).contains("${")) {
                    uncertain.add(name);
                } else if (ref.kind() == LabSettings.Kind.LDAP) {
                    if (target == null && defaultDirectoryId == null) {
                        uncertain.add(name);
                        continue;
                    }
                    String id = target == null ? defaultDirectoryId : "directory:" + target;
                    used |= ref.id().equals(id) || !hasNamedDefaultDirectory && target != null
                            && "default".equals(String.valueOf(target))
                            && ref.id().equals("ldap");
                } else if (target == null) {
                    uncertain.add(name);
                } else {
                    used |= ref.name() != null && ref.name().equals(String.valueOf(target));
                }
            }
            if (used) referenced.add(name);
        }
        return new Result(List.copyOf(referenced), List.copyOf(uncertain));
    }

    private static List<StepSpec> entries(Scenario scenario) {
        List<StepSpec> all = new ArrayList<>(scenario.steps());
        all.addAll(scenario.cleanup());
        return all;
    }
}
