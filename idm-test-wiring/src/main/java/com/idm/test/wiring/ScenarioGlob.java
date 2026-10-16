package com.idm.test.wiring;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/** The {@code --scenario} / {@code -Dscenario} selection rule shared by every runner. */
public final class ScenarioGlob {

    /** System property naming the scenario file, or glob of files, to run. */
    public static final String SCENARIO_PROPERTY = "scenario";

    private ScenarioGlob() {
    }

    /**
     * Narrows {@code names} to those matching {@code pattern}, a glob tried against each name with
     * and without its {@code .yaml}/{@code .yml} extension. A null or blank pattern returns all
     * names; nothing matching returns an empty list (callers decide the message).
     *
     * @throws IllegalStateException when the pattern is not a valid glob
     */
    public static List<String> select(List<String> names, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return names;
        }
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException("-D" + SCENARIO_PROPERTY + "=" + pattern
                    + " is not a valid glob: " + e.getDescription(), e);
        }
        return names.stream()
                .filter(name -> matcher.matches(Path.of(name)) || matcher.matches(Path.of(stripExtension(name))))
                .toList();
    }

    private static String stripExtension(String name) {
        if (name.endsWith(".yaml")) {
            return name.substring(0, name.length() - ".yaml".length());
        }
        if (name.endsWith(".yml")) {
            return name.substring(0, name.length() - ".yml".length());
        }
        return name;
    }
}
