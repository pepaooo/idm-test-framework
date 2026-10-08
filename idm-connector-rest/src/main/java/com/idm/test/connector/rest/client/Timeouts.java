package com.idm.test.connector.rest.client;

import java.time.Duration;

/**
 * A REST target's connect and read timeouts. Requests with the same pair share one HTTP client.
 *
 * @param connect how long to wait for the connection
 * @param read    the overall deadline for the response (see RestClientConfiguration#requestFactory)
 */
public record Timeouts(Duration connect, Duration read) {

    /** The timeouts of a target that sets none: 10 s to connect, 30 s for the response. */
    public static final Timeouts DEFAULT = new Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(30));

    /** From configuration's milliseconds; a missing value takes the default. Does not validate. */
    public static Timeouts ofMillis(Integer connectMillis, Integer readMillis) {
        return new Timeouts(
                connectMillis == null ? DEFAULT.connect() : Duration.ofMillis(connectMillis),
                readMillis == null ? DEFAULT.read() : Duration.ofMillis(readMillis));
    }
}
