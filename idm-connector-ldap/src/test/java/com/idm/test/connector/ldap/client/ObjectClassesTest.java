package com.idm.test.connector.ldap.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Object classes on create")
class ObjectClassesTest {

    @Test
    @DisplayName("Without objectClass, the inetOrgPerson chain is used")
    void testDefault() {
        assertThat(EdirectoryConnector.objectClasses(null))
                .containsExactly("top", "person", "organizationalPerson", "inetOrgPerson");
    }

    @Test
    @DisplayName("A single string or a list replaces the default")
    void testStringAndList() {
        assertThat(EdirectoryConnector.objectClasses("user")).containsExactly("user");
        assertThat(EdirectoryConnector.objectClasses(List.of("top", "person", "organizationalPerson", "user")))
                .containsExactly("top", "person", "organizationalPerson", "user");
    }

    @Test
    @DisplayName("An empty list or a blank value is refused")
    void testEmptyRefused() {
        assertThatThrownBy(() -> EdirectoryConnector.objectClasses(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("objectClass must name at least one object class, with no blank values");
        assertThatThrownBy(() -> EdirectoryConnector.objectClasses(List.of("top", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EdirectoryConnector.objectClasses(Arrays.asList("top", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("objectClass must name at least one object class, with no blank values");
    }
}
