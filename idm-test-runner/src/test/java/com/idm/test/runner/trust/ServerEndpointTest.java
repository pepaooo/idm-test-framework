package com.idm.test.runner.trust;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerEndpointTest {
    @Test
    void parsesDnsIpv4AndBracketedIpv6() {
        assertThat(ServerEndpoint.parse("lab.example:443").host()).isEqualTo("lab.example");
        assertThat(ServerEndpoint.parse("127.0.0.1:636").port()).isEqualTo(636);
        assertThat(ServerEndpoint.parse("[::1]:443").host()).isEqualTo("::1");
    }

    @Test
    void rejectsAmbiguousOrInvalidEndpoints() {
        for (String value : new String[]{"lab.example", "lab.example:0", "lab.example:65536", "::1:443",
                "[::1]", "https://lab.example:443", "lab.example:abc", ":443"}) {
            assertThatThrownBy(() -> ServerEndpoint.parse(value)).as(value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
