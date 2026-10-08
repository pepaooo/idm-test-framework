package com.idm.test.connector.rest.config;

import com.idm.test.connector.rest.client.RestAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
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
     * The authenticator every REST step asks for its target's {@code Authorization} header.
     * Spring Boot's OAuth2 client auto-configuration supplies the registration repository and
     * authorized-client service when {@code spring.security.oauth2.client.registration} has
     * entries; without them, only basic and unauthenticated targets work.
     */
    @Bean
    public RestAuthenticator restAuthenticator(
            ObjectProvider<ClientRegistrationRepository> registrations,
            ObjectProvider<OAuth2AuthorizedClientService> clients) {
        return new RestAuthenticator(registrations.getIfAvailable(), clients.getIfAvailable());
    }

    /**
     * The HTTP request factory every REST step runs on, in production and in tests alike, so
     * that tests exercise exactly the client a real run uses.
     * <p>
     * It is the JDK {@link HttpClient} rather than {@code HttpURLConnection}
     * ({@code SimpleClientHttpRequestFactory}): {@code HttpURLConnection} rejects {@code PATCH}
     * outright and silently drops the body of a {@code GET}, while the JDK client sends any
     * method with a body. The JDK client also lower-cases response header names; callers that
     * surface headers normalise them (see {@code HeaderNames}). Like the JDK client's default
     * ({@code Redirect.NEVER}), redirects are not followed: a 3xx is returned as data.
     * <p>
     * The client is pinned to HTTP/1.1. By default it prefers HTTP/2 and, on {@code http://},
     * sends {@code Upgrade: h2c}; a server that accepts (WireMock does) then speaks HTTP/2 while
     * the IdM servers under test speak HTTP/1.1, and the two differ in visible ways (a
     * {@code HEAD} came back with a body over h2c). Pinning keeps tests and real runs on the
     * same protocol.
     *
     * @return a new request factory with a 10 s connect timeout and a 30 s read timeout
     */
    public static ClientHttpRequestFactory requestFactory() {
        // Read timeout: 30 seconds
        return requestFactory(Duration.ofSeconds(30));
    }

    /**
     * {@link #requestFactory()} with a different read timeout, for tests that need one to expire.
     * <p>
     * With the JDK factory the read timeout is an overall deadline, from sending the request
     * until the response body is read (Spring cancels the exchange or closes the body stream when
     * it expires), not an idle timeout between reads as with {@code HttpURLConnection}.
     *
     * @param readTimeout the overall response deadline
     * @return a new request factory
     */
    public static ClientHttpRequestFactory requestFactory(Duration readTimeout) {
        return requestFactory(Duration.ofSeconds(10), readTimeout);
    }

    /**
     * {@link #requestFactory()} with both timeouts given, for a REST target's
     * {@code connect-timeout} and {@code read-timeout}.
     *
     * @param connectTimeout how long to wait for the connection
     * @param readTimeout    the overall response deadline (see {@link #requestFactory(Duration)})
     * @return a new request factory
     */
    public static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
