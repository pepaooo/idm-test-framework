package com.idm.test.engine.model;

import java.util.Map;

/**
 * One step as written in the file: its type name and its raw, uninterpolated parameters.
 * The engine resolves {@code ${...}} in these parameters before handing them to a step.
 */
public record StepSpec(String type, Map<String, Object> params) {
}
