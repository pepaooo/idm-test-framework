package com.idm.test.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Interpolator")
class InterpolatorTest {

    private static final Map<String, Object> SCOPE = Map.of(
            "user", Map.of("userId", "test.123", "firstName", "Ada"));

    @Test
    @DisplayName("Should replace a reference embedded in a larger string")
    void testReplacesEmbeddedReference() {
        Object result = Interpolator.resolve("cn=${user.userId},ou=users", SCOPE);

        assertThat(result).isEqualTo("cn=test.123,ou=users");
    }

    @Test
    @DisplayName("Should replace several references in one string")
    void testReplacesMultipleReferences() {
        Object result = Interpolator.resolve("${user.firstName} <${user.userId}>", SCOPE);

        assertThat(result).isEqualTo("Ada <test.123>");
    }

    @Test
    @DisplayName("Should walk nested maps and lists")
    void testWalksNestedStructures() {
        Object result = Interpolator.resolve(
                Map.of("expect", Map.of("givenName", "${user.firstName}"),
                       "roles", List.of("${user.userId}")),
                SCOPE);

        assertThat(result).isEqualTo(
                Map.of("expect", Map.of("givenName", "Ada"),
                       "roles", List.of("test.123")));
    }

    @Test
    @DisplayName("Should leave values without references untouched")
    void testLeavesPlainValues() {
        assertThat(Interpolator.resolve("ou=users", SCOPE)).isEqualTo("ou=users");
        assertThat(Interpolator.resolve(42, SCOPE)).isEqualTo(42);
        assertThat(Interpolator.resolve(null, SCOPE)).isNull();
    }

    @Test
    @DisplayName("Should name the offending reference when it cannot be resolved")
    void testFailsOnUnknownReference() {
        assertThatThrownBy(() -> Interpolator.resolve("cn=${missing.id}", SCOPE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${missing.id}");
    }

    @Test
    @DisplayName("Should name the offending reference when an intermediate path is missing")
    void testFailsOnUnknownNestedPath() {
        assertThatThrownBy(() -> Interpolator.resolve("${user.absent}", SCOPE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${user.absent}");
    }

    @Test
    @DisplayName("Should collect the root name of every reference for validation")
    void testCollectsReferenceRoots() {
        assertThat(Interpolator.references(
                Map.of("dn", "cn=${user.userId}", "note", "${created.body.id}")))
                .containsExactlyInAnyOrder("user", "created");
    }
}
