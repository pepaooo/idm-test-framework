package com.idm.test.engine;

import com.idm.test.engine.i18n.KeyedArgumentException;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.utils.data.TestDataGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FixtureFactory")
class FixtureFactoryTest {

    private final FixtureFactory factory = new FixtureFactory(new TestDataGenerator());

    @Test
    @DisplayName("Should generate a user with the attributes scenarios reference")
    void testGeneratesRandomUser() {
        Map<String, Object> built = factory.build(
                Map.of("user", new FixtureSpec("random-user", Map.of())));

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) built.get("user");

        assertThat(user).containsKeys(
                "userId", "firstName", "lastName", "email", "department", "jobTitle");
        assertThat(user.get("userId")).asString().isNotBlank();
    }

    @Test
    @DisplayName("Should give each generated user a distinct id")
    void testGeneratedUsersAreDistinct() {
        Map<String, FixtureSpec> spec = Map.of("user", new FixtureSpec("random-user", Map.of()));

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) factory.build(spec).get("user");
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) factory.build(spec).get("user");

        assertThat(first.get("userId")).isNotEqualTo(second.get("userId"));
    }

    @Test
    @DisplayName("Should let literal values override generated ones")
    void testLiteralsOverrideGenerated() {
        Map<String, Object> built = factory.build(Map.of("user",
                new FixtureSpec("random-user", Map.of("department", "Engineering"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) built.get("user");

        assertThat(user).containsEntry("department", "Engineering");
    }

    @Test
    @DisplayName("Should support a fixture of literals with no generator")
    void testLiteralsOnly() {
        Map<String, Object> built = factory.build(Map.of("role",
                new FixtureSpec(null, Map.of("dn", "cn=SAP_USER,ou=roles"))));

        assertThat(built).containsEntry("role", Map.of("dn", "cn=SAP_USER,ou=roles"));
    }

    @Test
    @DisplayName("Should name the unknown generator and list what is available")
    void testRejectsUnknownGenerator() {
        assertThatThrownBy(() -> factory.build(
                Map.of("user", new FixtureSpec("random-widget", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("random-widget")
                .hasMessageContaining("random-user");
    }

    @Test
    @DisplayName("An unknown generator reads in Spanish, English unchanged")
    void testUnknownGeneratorRendersInSpanish() {
        assertThatThrownBy(() -> factory.build(Map.of("user", new FixtureSpec("nope", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown generator 'nope'. Available: [random-user]")
                .satisfies(e -> assertThat(Messages.render(((KeyedArgumentException) e).detail(), Locale.of("es")))
                        .isEqualTo("generador desconocido 'nope'. Disponibles: [random-user]"));
    }
}
