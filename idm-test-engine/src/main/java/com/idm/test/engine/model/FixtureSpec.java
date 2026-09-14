package com.idm.test.engine.model;

import java.util.Map;

/**
 * How one fixture is produced.
 *
 * @param generate a generator name such as "random-user", or null for literals only
 * @param values   literal values, which override anything the generator produced
 */
public record FixtureSpec(String generate, Map<String, Object> values) {
}
