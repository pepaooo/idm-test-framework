package com.idm.test.runner.ui;

import com.idm.test.engine.FixtureFactory;
import com.idm.test.engine.spi.StepType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A JSON Schema (draft 2020-12) of the scenario format for the editor's autocomplete, hover help and
 * inline errors, generated from the registered {@link StepType}s so it cannot drift from what
 * {@code ScenarioValidator} accepts: a step is a single-key map whose key is a step type (or
 * {@code set}); its properties are the type's accepted and required parameters plus
 * {@code description} and {@code as}, and {@code wait} only on a step that can wait (the validator
 * rejects it on an action); unknown parameters are not allowed. A cleanup entry is an action only,
 * without {@code wait} or {@code as}. Parameter values are not typed: the step types declare names
 * only.
 */
public final class StepSchema {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String IDENTIFIER = "^[A-Za-z_][A-Za-z0-9_]*$";

    private StepSchema() {
    }

    /** The schema as JSON text. */
    public static String of(Map<String, StepType> registry) {
        ObjectNode schema = NODES.objectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("title", "idmtest scenario");
        schema.put("type", "object");
        schema.putArray("required").add("name").add("steps");

        ObjectNode properties = schema.putObject("properties");
        properties.set("name", text("What the scenario checks, shown in results."));
        properties.set("description", text("Longer text for people; not used to run anything."));
        properties.set("fixtures", fixtures());
        properties.set("steps", list("#/$defs/step", "What the scenario does, in order."));
        properties.set("cleanup", list("#/$defs/cleanupEntry",
                "Actions that undo the scenario's changes; they run even when a step fails."));

        ObjectNode defs = schema.putObject("$defs");
        ObjectNode step = singleKey(defs.putObject("step"));
        ObjectNode cleanup = singleKey(defs.putObject("cleanupEntry"));
        for (StepType type : registry.values()) {
            step.set(type.name(), stepParams(type, true));
            if (!type.retryable()) {
                cleanup.set(type.name(), stepParams(type, false));
            }
        }
        ObjectNode set = NODES.objectNode();
        set.put("type", "object");
        set.put("description", "Binds names for later steps: each key is a name, each value its value.");
        set.put("minProperties", 1);
        set.putObject("propertyNames").put("pattern", IDENTIFIER);
        step.set("set", set);

        return JSON.writeValueAsString(schema);
    }

    private static ObjectNode fixtures() {
        ObjectNode fixtures = NODES.objectNode();
        fixtures.put("type", "object");
        fixtures.put("description", "Named test data, generated or given as values.");
        ObjectNode fixture = fixtures.putObject("additionalProperties");
        fixture.put("type", "object");
        ObjectNode properties = fixture.putObject("properties");
        ArrayNode generators = properties.putObject("generate").putArray("enum");
        new TreeSet<>(FixtureFactory.SUPPORTED_GENERATORS).forEach(generators::add);
        properties.putObject("values").put("type", "object");
        return fixtures;
    }

    private static ObjectNode list(String itemRef, String description) {
        ObjectNode list = NODES.objectNode();
        list.put("type", "array");
        list.put("description", description);
        list.putObject("items").put("$ref", itemRef);
        return list;
    }

    private static ObjectNode text(String description) {
        ObjectNode node = NODES.objectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    /** Makes {@code node} a map with exactly one of the listed keys; returns its {@code properties}. */
    private static ObjectNode singleKey(ObjectNode node) {
        node.put("type", "object");
        node.put("minProperties", 1);
        node.put("maxProperties", 1);
        node.put("additionalProperties", false);
        return node.putObject("properties");
    }

    private static ObjectNode stepParams(StepType type, boolean inSteps) {
        ObjectNode node = NODES.objectNode();
        node.put("type", "object");
        node.put("description", type.retryable()
                ? "A check; may wait for the lab to catch up (wait)."
                : "An action on the lab.");
        Set<String> names = new TreeSet<>(type.acceptedParams());
        names.addAll(type.requiredParams());
        names.remove("wait");
        ObjectNode properties = node.putObject("properties");
        for (String name : names) {
            properties.putObject(name);
        }
        properties.set("description", text("What this step is for, shown in results."));
        if (inSteps) {
            ObjectNode as = text("Name to keep this step's result under, for later steps.");
            as.put("pattern", IDENTIFIER);
            properties.set("as", as);
            if (type.retryable()) {
                properties.set("wait", waitSchema());
            }
        }
        ArrayNode required = node.putArray("required");
        new TreeSet<>(type.requiredParams()).forEach(required::add);
        node.put("additionalProperties", false);
        return node;
    }

    private static ObjectNode waitSchema() {
        ObjectNode wait = NODES.objectNode();
        wait.put("description", "true for the default timeout and poll, or {timeout, poll} such as 2m and 5s.");
        ArrayNode anyOf = wait.putArray("anyOf");
        anyOf.addObject().put("type", "boolean");
        ObjectNode spec = anyOf.addObject();
        spec.put("type", "object");
        ObjectNode properties = spec.putObject("properties");
        properties.putObject("timeout").put("type", "string");
        properties.putObject("poll").put("type", "string");
        return wait;
    }
}
