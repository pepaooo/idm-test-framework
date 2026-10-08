package com.idm.test.connector.rest.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idm.test.connector.rest.config.RestClientConfiguration;
import com.idm.test.connector.rest.model.TextResponse;
import com.idm.test.core.model.OperationResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("REST per-target timeouts")
class RestTimeoutsTest {

    private static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void reset() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withBody("ok").withFixedDelay(2_000)));
    }

    private static IdmRestConnector connector() {
        return new IdmRestConnector(RestClient.builder()
                .requestFactory(RestClientConfiguration.requestFactory()).build());
    }

    private static String slow() {
        return "http://localhost:" + WIREMOCK.port() + "/slow";
    }

    @Test
    @DisplayName("A short read timeout fails fast; the default client still succeeds")
    void readTimeoutApplies() {
        IdmRestConnector connector = connector();
        Timeouts quick = new Timeouts(Duration.ofSeconds(10), Duration.ofMillis(500));

        long started = System.currentTimeMillis();
        OperationResult<TextResponse> timedOut = connector.exchangeText("GET", slow(), null, Map.of(), quick);
        assertThat(timedOut.isSuccess()).isFalse();
        assertThat(System.currentTimeMillis() - started).isLessThan(1_500);

        assertThat(connector.exchangeText("GET", slow(), null, Map.of(), Timeouts.DEFAULT).isSuccess()).isTrue();
        assertThat(connector.exchange("GET", slow(), null, Map.of(), quick).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("Equal timeouts share one client; different ones get their own; the default needs none")
    void clientsCachedPerPair() {
        IdmRestConnector connector = connector();
        WIREMOCK.stubFor(get(urlEqualTo("/fast")).willReturn(aResponse().withStatus(200)));
        String fast = "http://localhost:" + WIREMOCK.port() + "/fast";

        connector.exchangeText("GET", fast, null, Map.of(), Timeouts.DEFAULT);
        connector.exchangeText("GET", fast, null, Map.of(), null);
        assertThat(connector.timeoutClientCount()).isZero();

        connector.exchangeText("GET", fast, null, Map.of(), new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)));
        connector.exchangeText("GET", fast, null, Map.of(), new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertThat(connector.timeoutClientCount()).isEqualTo(1);

        connector.exchange("GET", fast, null, Map.of(), new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(6)));
        assertThat(connector.timeoutClientCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("ofMillis fills a missing value with the default")
    void ofMillis() {
        assertThat(Timeouts.ofMillis(null, null)).isEqualTo(Timeouts.DEFAULT);
        assertThat(Timeouts.ofMillis(2_000, null)).isEqualTo(new Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(30)));
    }
}
