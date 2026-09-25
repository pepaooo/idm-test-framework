package com.idm.test.connector.rest.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HeaderNames.normalize")
class HeaderNamesTest {

    @Test
    @DisplayName("Lower-cases mixed-case names")
    void testLowerCases() {
        Map<String, List<String>> input = new LinkedHashMap<>();
        input.put("X-Custom-Header", List.of("v"));

        assertThat(HeaderNames.normalize(input))
                .containsExactly(Map.entry("x-custom-header", List.of("v")));
    }

    @Test
    @DisplayName("Merges names that differ only in case, values in encounter order")
    void testMergesCaseVariants() {
        Map<String, List<String>> input = new LinkedHashMap<>();
        input.put("Set-Cookie", List.of("a=1"));
        input.put("set-cookie", List.of("b=2", "c=3"));

        assertThat(HeaderNames.normalize(input))
                .containsExactly(Map.entry("set-cookie", List.of("a=1", "b=2", "c=3")));
    }

    @Test
    @DisplayName("Drops HTTP/2 pseudo-headers such as :status")
    void testDropsPseudoHeaders() {
        Map<String, List<String>> input = new LinkedHashMap<>();
        input.put(":status", List.of("200"));
        input.put("Content-Type", List.of("text/plain"));

        assertThat(HeaderNames.normalize(input))
                .containsExactly(Map.entry("content-type", List.of("text/plain")));
    }

    @Test
    @DisplayName("Preserves the order of first occurrence")
    void testPreservesOrder() {
        Map<String, List<String>> input = new LinkedHashMap<>();
        input.put("Zeta", List.of("1"));
        input.put("Alpha", List.of("2"));
        input.put("Mid", List.of("3"));

        assertThat(HeaderNames.normalize(input).keySet()).containsExactly("zeta", "alpha", "mid");
    }
}
