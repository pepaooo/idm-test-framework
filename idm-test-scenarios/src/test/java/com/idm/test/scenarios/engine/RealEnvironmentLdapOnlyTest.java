package com.idm.test.scenarios.engine;

import com.idm.test.connector.ldap.config.LdapProperties;
import com.idm.test.scenarios.config.IdmTestSchema;
import com.idm.test.utils.ldap.EmbeddedLdapServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The real-environment runner with only LDAP configured: an embedded directory stands in for
 * the real one, and nothing else is supplied. No SOAP resource URL, so the Resource service
 * bean does not exist; the databases stay application.yml's unreachable placeholders; no REST
 * credentials. Before ITF-30 this context could not even start. It runs in the default suite
 * (Test suffix) to keep "only what the scenarios use" true.
 * <p>
 * Like ContainerScenarios, it sets system properties before the Spring context loads, restores
 * them afterwards, and dirties the context so no other class reuses it. It ignores {@code
 * -Dscenario}, because it always runs its own single scenario file regardless of that property.
 * Like every default-suite run, it writes {@code target/real-environment-results.json}. An
 * exported {@code IDM_TEST_PROFILE=test} makes it fail at guard 1 by design, since the class
 * under test refuses to run under the embedded profile.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@DisplayName("Real-environment runner with only LDAP configured")
class RealEnvironmentLdapOnlyTest extends RealEnvironmentScenarios {

    static final String DIR = "scenarios-ldap-only";

    private static final String BASE_DN = "dc=test,dc=com";
    private static final String BIND_DN = "cn=admin,dc=test,dc=com";
    private static final String BIND_PASSWORD = "test";

    private static final Map<String, String> PREVIOUS = new LinkedHashMap<>();
    private static EmbeddedLdapServer ldap;

    @Autowired
    private LdapProperties ldapProperties;

    @BeforeAll
    static void startDirectory() throws Exception {
        ldap = EmbeddedLdapServer.start(BASE_DN, BIND_DN, BIND_PASSWORD, 0, "ldap-init.ldif",
                IdmTestSchema.edirectoryLikeSchema());
        set("idm.test.ldap.url", ldap.getUrl());
        set("idm.test.ldap.base-dn", BASE_DN);
        set("idm.test.ldap.user-dn", BIND_DN);
        set("idm.test.ldap.password", BIND_PASSWORD);
        // The default profile's 5-minute wait would make a regression in this test hang.
        set("idm.test.executor.default-timeout-seconds", "60");
        set("idm.test.executor.default-poll-interval-seconds", "1");
        // This class always runs its own single scenario file; a suite-wide -Dscenario must not
        // make it try to filter to a different one.
        PREVIOUS.put("scenario", System.getProperty("scenario"));
        System.clearProperty("scenario");
    }

    @AfterAll
    static void stopDirectory() {
        PREVIOUS.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
        PREVIOUS.clear();
        if (ldap != null) {
            ldap.close();
        }
    }

    @TestFactory
    @Override
    @DisplayName("The LDAP-only scenario runs with nothing else configured")
    @DirtiesContext
    Stream<DynamicTest> scenarios() throws Exception {
        ContainerScenarios.requireBoundTo(ldapProperties.getUrl(), System.getProperty("idm.test.ldap.url"));
        return super.scenarios();
    }

    @Override
    String scenarioDirectory() {
        return DIR;
    }

    private static void set(String key, String value) {
        if (!PREVIOUS.containsKey(key)) {
            PREVIOUS.put(key, System.getProperty(key));
        }
        System.setProperty(key, value);
    }
}
