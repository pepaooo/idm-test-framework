package com.idm.test.connector.rest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Configuration for Spring RestClient.
 * Creates RestClient bean for REST API operations.
 * <p>
 * Enables {@link RestProperties} the same way {@code LdapConfiguration} enables
 * {@code LdapProperties} and {@code SoapConfiguration} enables {@code SoapProperties}:
 * {@code RestProperties} is a bare {@code @ConfigurationProperties} class with no
 * {@code @Component}, so without this, no bean exists outside a test harness that patches it
 * in, and neither {@code rest.request} nor {@code rest.verify} can be constructed in a real
 * deployment.
 */
@Configuration
@EnableConfigurationProperties(RestProperties.class)
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

        RestClient client = RestClient.builder()
                .requestFactory(requestFactory())
                .build();

        log.info("RestClient configured successfully");
        return client;
    }

    /**
     * The HTTP request factory every REST step runs on, in production and in tests alike, so
     * that tests exercise exactly the client a real run uses.
     * <p>
     * It is the JDK {@link HttpClient} rather than {@code HttpURLConnection}
     * ({@code SimpleClientHttpRequestFactory}): {@code HttpURLConnection} rejects {@code PATCH}
     * outright and silently drops the body of a {@code GET}, while the JDK client sends any
     * method with a body. The JDK client also lower-cases response header names and may expose
     * HTTP/2 pseudo-headers such as {@code :status}; callers that surface headers normalise them
     * (see {@code HeaderNames}).
     *
     * @return a new request factory with the connection and read timeouts
     */
    public static ClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                // Connection timeout: 10 seconds
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);

        // Read timeout: 30 seconds
        factory.setReadTimeout(Duration.ofSeconds(30));

        return factory;
    }
}
