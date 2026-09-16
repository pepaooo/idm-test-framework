package com.idm.test.connector.rest.model;

import java.util.List;
import java.util.Map;

/**
 * One HTTP response, in plain Java.
 * <p>
 * Deliberately carries no Spring type. The scenario engine consumes this through
 * {@code rest.request}, and {@code idm-test-engine/src/main} is forbidden from importing
 * {@code org.springframework} — so {@code ResponseEntity} and {@code HttpHeaders} cannot
 * cross that boundary and are converted here instead.
 *
 * @param status  the HTTP status code, including 4xx and 5xx — an error status is an
 *                observable outcome a scenario may assert on, not a failure
 * @param headers response headers, never null; a header may legitimately repeat, so each maps
 *                to a list of values
 * @param body    the parsed JSON body as a Map, List, String, Number or Boolean; the raw text
 *                for a non-JSON response; or null when the response had no body
 */
public record RestResponse(int status, Map<String, List<String>> headers, Object body) {
}
