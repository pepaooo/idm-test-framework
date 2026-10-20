package com.idm.test.runner.ui;

import com.idm.test.engine.spi.StepType;
import com.idm.test.wiring.StepRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Step-vocabulary JSON Schema")
class StepSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, StepType> REGISTRY = StepRegistry.forValidation();

    private static JsonNode schema() {
        return JSON.readTree(StepSchema.of(REGISTRY));
    }

    private static List<String> texts(JsonNode array) {
        List<String> list = new ArrayList<>();
        array.forEach(node -> list.add(node.asString()));
        return list;
    }

    @Test
    @DisplayName("Is a draft 2020-12 schema of a scenario: name, description, fixtures, steps, cleanup")
    void testTopLevel() {
        JsonNode schema = schema();

        assertThat(schema.get("$schema").asString()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.get("type").asString()).isEqualTo("object");
        assertThat(schema.get("properties").propertyNames())
                .containsExactlyInAnyOrder("name", "description", "fixtures", "steps", "cleanup");
        assertThat(texts(schema.get("required"))).containsExactlyInAnyOrder("name", "steps");
        assertThat(schema.at("/properties/fixtures/additionalProperties/properties/generate/enum").isArray()).isTrue();
        assertThat(schema.at("/properties/steps/items/$ref").asString()).isEqualTo("#/$defs/step");
        assertThat(schema.at("/properties/cleanup/items/$ref").asString()).isEqualTo("#/$defs/cleanupEntry");
    }

    @Test
    @DisplayName("A step is a single-key map whose key is one of the 14 step types (or set)")
    void testStepsListAllTypes() {
        JsonNode step = schema().at("/$defs/step");

        assertThat(REGISTRY).hasSize(14);
        assertThat(step.get("minProperties").asInt()).isEqualTo(1);
        assertThat(step.get("maxProperties").asInt()).isEqualTo(1);
        assertThat(step.get("additionalProperties").asBoolean()).isFalse();
        List<String> expected = new ArrayList<>(REGISTRY.keySet());
        expected.add("set");
        assertThat(step.get("properties").propertyNames()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("ldap.verify requires dn and accepts expect, wait, directory, as and description")
    void testLdapVerifyParams() {
        JsonNode verify = schema().at("/$defs/step/properties/ldap.verify");

        assertThat(texts(verify.get("required"))).containsExactly("dn");
        assertThat(verify.get("properties").propertyNames())
                .contains("dn", "expect", "exists", "wait", "directory", "as", "description");
        assertThat(verify.get("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Every type's properties are its accepted parameters; wait only where the step can wait")
    void testEveryTypeMatchesItsStepType() {
        JsonNode steps = schema().at("/$defs/step/properties");

        REGISTRY.forEach((name, type) -> {
            JsonNode node = steps.get(name);
            assertThat(node.get("properties").propertyNames()).as(name)
                    .containsAll(type.acceptedParams())
                    .containsAll(type.requiredParams())
                    .contains("as", "description");
            assertThat(node.get("properties").has("wait")).as(name).isEqualTo(type.retryable());
            assertThat(texts(node.get("required"))).as(name).containsExactlyInAnyOrderElementsOf(type.requiredParams());
        });
    }

    @Test
    @DisplayName("A cleanup entry is an action only, without wait or as")
    void testCleanupEntries() {
        JsonNode cleanup = schema().at("/$defs/cleanupEntry/properties");

        REGISTRY.forEach((name, type) -> {
            assertThat(cleanup.has(name)).as(name).isEqualTo(!type.retryable());
            if (!type.retryable()) {
                assertThat(cleanup.get(name).get("properties").propertyNames()).as(name)
                        .doesNotContain("wait", "as")
                        .contains("description");
            }
        });
        assertThat(cleanup.has("set")).isFalse();
    }
}
