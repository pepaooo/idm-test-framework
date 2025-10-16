package com.idm.test.connector.rest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configuration for Spring RestClient.
 * Creates RestClient bean for REST API operations.
 */
@Configuration
public class RestClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfiguration.class);

    /**
     * Creates a RestClient bean with default configuration.
     * Uses Spring 6.2's modern RestClient API.
     *
     * @return configured RestClient
     */
    @Bean
    public RestClient restClient() {
        log.info("Configuring RestClient with default settings");

        ClientHttpRequestFactory requestFactory = createRequestFactory();

        RestClient client = RestClient.builder()
                .requestFactory(requestFactory)
                .build();

        log.info("RestClient configured successfully");
        return client;
    }

    /**
     * Creates HTTP request factory with timeout settings.
     *
     * @return configured request factory
     */
    private ClientHttpRequestFactory createRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        // Connection timeout: 10 seconds
        factory.setConnectTimeout(Duration.ofSeconds(10));

        // Read timeout: 30 seconds
        factory.setReadTimeout(Duration.ofSeconds(30));

        return factory;
    }
}
