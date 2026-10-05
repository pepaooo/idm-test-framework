package com.idm.test.engine.spi;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What a step sent and received, recorded so the results and the report can show it under the
 * step. Every implementation masks secrets and enforces the size limits in its constructor, so an
 * unmasked or oversized evidence object cannot exist.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HttpEvidence.class, name = "http"),
        @JsonSubTypes.Type(value = SqlEvidence.class, name = "sql"),
        @JsonSubTypes.Type(value = LdapEvidence.class, name = "ldap")})
public sealed interface Evidence permits HttpEvidence, SqlEvidence, LdapEvidence {

    /** Characters kept of each HTTP request or response body. */
    int MAX_BODY_CHARS = 16_384;

    /** Database rows kept. */
    int MAX_ROWS = 20;

    /** Characters kept of one database or LDAP value. */
    int MAX_VALUE_CHARS = 1_024;

    /** Values kept per LDAP attribute. */
    int MAX_ATTRIBUTE_VALUES = 20;
}
