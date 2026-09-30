package com.idm.test.engine.result;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a report says about the run itself.
 *
 * @param runner         who ran it, e.g. {@code Real environment}, {@code Containers}, {@code Embedded}
 * @param startedAt      when the run started
 * @param profiles       the active Spring profiles, empty when none
 * @param scenarioFilter the {@code -Dscenario} value, or null
 * @param endpoints      label to URL for what the selected scenarios use; sanitised with
 *                       {@link ReportUrls#sanitise}, sorted by label, null labels and URLs dropped
 */
public record RunInfo(String runner, Instant startedAt, List<String> profiles, String scenarioFilter,
                      Map<String, String> endpoints) {

    public RunInfo {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        Map<String, String> safe = new TreeMap<>();
        if (endpoints != null) {
            endpoints.forEach((label, url) -> {
                if (label != null && url != null) {
                    safe.put(label, ReportUrls.sanitise(url));
                }
            });
        }
        endpoints = Collections.unmodifiableMap(safe);
    }

    /** A run described only by its runner's name, starting now. */
    public static RunInfo minimal(String runner) {
        return new RunInfo(runner, Instant.now(), List.of(), null, Map.of());
    }
}
