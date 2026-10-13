package com.idm.test.engine;

import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.model.StepSpec;
import com.idm.test.engine.spi.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a scenario file into the model. Parsing only — see ScenarioValidator for checks.
 */
public final class ScenarioLoader {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            // Jackson 3 rejects a second YAML document; Jackson 2 loaded the first and ignored the rest.
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ScenarioLoader() {
    }

    /**
     * @param yaml       the scenario file's contents; closed by this method
     * @param sourceName the file name, used in error messages
     */
    @SuppressWarnings("unchecked")
    public static Scenario load(InputStream yaml, String sourceName) {
        if (yaml == null) {
            throw new ValidationException(sourceName, Message.of("validation.load.notFound"));
        }

        Map<String, Object> root;
        try (InputStream in = yaml) {
            root = YAML.readValue(in, Map.class);
        } catch (JacksonException | IOException e) {
            throw new ValidationException(sourceName, Message.of("validation.load.parse", e.getMessage()), e);
        }

        if (root == null) {
            throw new ValidationException(sourceName, Message.of("validation.load.empty"));
        }

        String name = asString(root.get("name"));
        if (name == null || name.isBlank()) {
            throw new ValidationException(sourceName, Message.of("validation.load.nameRequired"));
        }

        return new Scenario(
                name,
                asString(root.get("description")),
                readFixtures(root.get("fixtures"), sourceName),
                readSteps(root.get("steps"), sourceName, "steps"),
                readSteps(root.get("cleanup"), sourceName, "cleanup"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, FixtureSpec> readFixtures(Object raw, String sourceName) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> fixtures)) {
            throw new ValidationException(sourceName, Message.of("validation.load.fixturesNotMap"));
        }

        Map<String, FixtureSpec> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : fixtures.entrySet()) {
            String fixtureName = entry.getKey().toString();
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                throw new ValidationException(sourceName, Message.of("validation.load.fixtureNotMap", fixtureName));
            }
            Object values = body.get("values");
            Map<String, Object> valuesMap;
            if (values == null) {
                valuesMap = Map.of();
            } else if (values instanceof Map<?, ?>) {
                valuesMap = (Map<String, Object>) values;
            } else {
                throw new ValidationException(sourceName, Message.of("validation.load.fixtureValuesNotMap",
                        fixtureName, values.getClass().getSimpleName()));
            }
            result.put(fixtureName, new FixtureSpec(
                    asString(body.get("generate")),
                    valuesMap));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<StepSpec> readSteps(Object raw, String sourceName, String section) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> steps)) {
            throw new ValidationException(sourceName, Message.of("validation.load.sectionNotList", section));
        }

        List<StepSpec> result = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Object element = steps.get(i);
            if (!(element instanceof Map<?, ?> step) || step.size() != 1) {
                throw new ValidationException(sourceName, Message.of("steps".equals(section)
                        ? "validation.load.stepShape" : "validation.load.cleanupShape", i + 1));
            }

            Map.Entry<?, ?> only = step.entrySet().iterator().next();
            String stepType = only.getKey().toString();
            Object params = only.getValue();
            Map<String, Object> paramsMap;
            if (params == null) {
                paramsMap = Map.of();
            } else if (params instanceof Map<?, ?>) {
                paramsMap = (Map<String, Object>) params;
            } else {
                Message message = Message.of("validation.load.paramsNotMap", params.getClass().getSimpleName());
                throw "steps".equals(section)
                        ? new ValidationException(sourceName, i, stepType, message)
                        : ValidationException.forCleanup(sourceName, i, stepType, message);
            }
            result.add(new StepSpec(stepType, paramsMap));
        }
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
