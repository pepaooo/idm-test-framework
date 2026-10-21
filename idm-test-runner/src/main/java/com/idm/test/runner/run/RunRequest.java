package com.idm.test.runner.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What to run: a workspace's scenarios against one profile, as {@code idmtest run} does.
 *
 * @param workspace           the workspace folder
 * @param profile             the profile, {@code config/application-<profile>.yml}
 * @param scenarios           scenario patterns (as {@code --scenario} takes one); empty runs them all
 * @param locale              the run's language; null lets {@code IDM_TEST_LOCALE}, {@code -Didm.test.locale}
 *                            and then the profile's {@code idm.test.report.locale} decide
 * @param environmentOverride stands in for the process's environment variables when not null (a test
 *                            hook); null in production
 */
public record RunRequest(Path workspace, String profile, List<String> scenarios, Locale locale,
                         Map<String, String> environmentOverride) {

    public RunRequest {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        environmentOverride = environmentOverride == null ? null : Map.copyOf(environmentOverride);
    }
}
