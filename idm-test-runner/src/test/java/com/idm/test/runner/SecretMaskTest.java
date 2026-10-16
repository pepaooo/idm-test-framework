package com.idm.test.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecretMask")
class SecretMaskTest {

    @Test
    @DisplayName("Should mask every value of four characters or more, longest first, and leave shorter ones")
    void testMask() {
        SecretMask mask = new SecretMask(List.of("abcd", "abcdef", "1", "", "xy"));

        assertThat(mask.apply("pw=abcdef and abcd, port 1 xy")).isEqualTo("pw=**** and ****, port 1 xy");
        assertThat(mask.apply(null)).isNull();
    }

    @Test
    @DisplayName("Should mask a stack trace, causes included")
    void testStackTrace() {
        SecretMask mask = new SecretMask(List.of("s3cr3t"));
        Exception e = new IllegalStateException("outer", new NumberFormatException("For input string: \"s3cr3t\""));

        assertThat(mask.stackTrace(e)).contains("NumberFormatException", "****").doesNotContain("s3cr3t");
    }
}
