package com.idm.test.connector.rest.model;

import java.util.List;
import java.util.Map;

/**
 * One HTTP response exactly as it arrived: the status, the headers with lower-case names, and
 * the body as text (never null, empty when there was none).
 */
public record TextResponse(int status, Map<String, List<String>> headers, String body) {
}
