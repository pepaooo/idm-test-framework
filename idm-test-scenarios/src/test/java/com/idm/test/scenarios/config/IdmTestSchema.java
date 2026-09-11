package com.idm.test.scenarios.config;

import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.ObjectClassDefinition;
import com.unboundid.ldap.sdk.schema.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The LDAP schema the scenario tests run against.
 * <p>
 * Standard schema plus what a real eDirectory deployment defines but plain LDAP does
 * not: a custom {@code startDate} attribute, eDirectory's {@code loginDisabled} flag,
 * and permission for person entries to carry {@code c} (country), which RFC 4519
 * defines but only allows on {@code country} entries.
 * <p>
 * Schema validation stays ON. The alternative considered was disabling it, which would
 * have stopped malformed entries failing anywhere; extending the schema keeps the check
 * while letting the tests exercise custom-attribute handling, which is a core concern
 * for an IdM framework and will matter against a real directory.
 */
public final class IdmTestSchema {

    private static final String START_DATE_ATTRIBUTE =
            "( 1.3.6.1.4.1.99999.1.1 NAME 'startDate' "
                    + "DESC 'IdM provisioning start date' "
                    + "EQUALITY caseIgnoreMatch "
                    + "SUBSTR caseIgnoreSubstringsMatch "
                    + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 "
                    + "SINGLE-VALUE X-ORIGIN 'idm-test-framework' )";

    private static final String LOGIN_DISABLED_ATTRIBUTE =
            "( 1.3.6.1.4.1.99999.1.2 NAME 'loginDisabled' "
                    + "DESC 'eDirectory account disabled flag' "
                    + "EQUALITY booleanMatch "
                    + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.7 "
                    + "SINGLE-VALUE X-ORIGIN 'idm-test-framework' )";

    private static final String EXTENDED_OBJECT_CLASS = "inetOrgPerson";
    private static final List<String> ADDITIONAL_OPTIONAL_ATTRIBUTES =
            List.of("startDate", "loginDisabled", "c");

    private IdmTestSchema() {
    }

    /**
     * @return the standard schema extended as described above
     */
    public static Schema edirectoryLikeSchema() throws LDAPException {
        Schema standard = Schema.getDefaultStandardSchema();
        Entry schemaEntry = standard.getSchemaEntry().duplicate();

        schemaEntry.addAttribute("attributeTypes", START_DATE_ATTRIBUTE);
        schemaEntry.addAttribute("attributeTypes", LOGIN_DISABLED_ATTRIBUTE);

        ObjectClassDefinition original = standard.getObjectClass(EXTENDED_OBJECT_CLASS);
        if (original == null) {
            throw new IllegalStateException(
                    "Standard schema does not define " + EXTENDED_OBJECT_CLASS);
        }

        List<String> optional = new ArrayList<>(Arrays.asList(original.getOptionalAttributes()));
        optional.addAll(ADDITIONAL_OPTIONAL_ATTRIBUTES);

        ObjectClassDefinition extended = new ObjectClassDefinition(
                original.getOID(),
                original.getNames(),
                original.getDescription(),
                original.isObsolete(),
                original.getSuperiorClasses(),
                original.getObjectClassType(),
                original.getRequiredAttributes(),
                optional.toArray(new String[0]),
                original.getExtensions());

        schemaEntry.removeAttributeValue("objectClasses", original.toString());
        schemaEntry.addAttribute("objectClasses", extended.toString());

        return Schema.parseSchemaEntry(schemaEntry);
    }
}
