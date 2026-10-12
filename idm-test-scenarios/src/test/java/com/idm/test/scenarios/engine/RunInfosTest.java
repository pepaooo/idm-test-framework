package com.idm.test.scenarios.engine;

import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.i18n.ReportLocale;
import com.idm.test.engine.result.RunInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RunInfos")
class RunInfosTest {

    private static List<Scenario> scenarios(String yaml) {
        return List.of(ScenarioLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "t.yaml"));
    }

    private static MockEnvironment lab() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("idm.test.ldap.url", "ldap://old.lab:389");
        environment.setProperty("idm.test.directories.ad.url", "ldaps://ad.lab:636");
        environment.setProperty("idm.test.default-directory", "ad");
        environment.setProperty("idm.test.databases.db-postgres.jdbc-url", "jdbc:postgresql://idm:pw@db.lab/idm");
        environment.setProperty("idm.test.databases.unused.jdbc-url", "jdbc:postgresql://db.lab/unused");
        environment.setProperty("idm.test.soap.url", "https://idm.lab:8543/IDMProv/role/service");
        environment.setProperty("idm.test.soap.resource-url", "https://idm.lab:8543/IDMProv/resource/service");
        environment.setProperty("idm.test.rest.idm.base-url", "https://idm.lab/api");
        return environment;
    }

    @Test
    @DisplayName("Lists only the endpoints the scenarios use, sanitised, with the default directory resolved")
    void testUsedEndpoints() {
        RunInfo run = RunInfos.of("runner.name.realEnvironment", lab(), scenarios("""
                name: t
                steps:
                  - ldap.verify:
                      dn: cn=x,ou=users
                      exists: true
                  - db.verify:
                      database: db-postgres
                      query: SELECT 1
                      expect: { rowCount: 1 }
                  - resources.grant:
                      user: cn=u
                      resource: cn=r
                """));

        assertThat(run.runner()).isEqualTo("Real environment");
        assertThat(run.profiles()).containsExactly("dev");
        assertThat(run.endpoints()).containsOnlyKeys("LDAP ad", "Database db-postgres", "Resource service");
        assertThat(run.endpoints().get("LDAP ad")).isEqualTo("ldaps://ad.lab:636");
        assertThat(run.endpoints().get("Database db-postgres")).isEqualTo("jdbc:postgresql://db.lab/idm");
    }

    @Test
    @DisplayName("The old idm.test.ldap block is the directory 'default'; roles and REST targets are listed when used")
    void testOldBlockRolesAndRest() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("idm.test.ldap.url", "ldap://old.lab:389");
        environment.setProperty("idm.test.soap.url", "https://idm.lab:8543/IDMProv/role/service");
        environment.setProperty("idm.test.rest.idm.base-url", "https://idm.lab/api");

        RunInfo run = RunInfos.of("runner.name.embedded", environment, scenarios("""
                name: t
                steps:
                  - ldap.delete:
                      dn: cn=x,ou=users
                  - roles.grant:
                      user: cn=u
                      roles: [cn=r]
                  - rest.request:
                      target: idm
                      method: GET
                      path: /x
                """));

        assertThat(run.endpoints()).containsOnlyKeys("LDAP default", "Role service", "REST idm");
        assertThat(run.endpoints().get("LDAP default")).isEqualTo("ldap://old.lab:389");
    }

    @Test
    @DisplayName("The report locale comes from idm.test.report.locale, English when unset, and an unsupported one stops")
    void testReportLocaleProperty() {
        MockEnvironment spanish = new MockEnvironment();
        spanish.setProperty("idm.test.report.locale", "es");
        assertThat(RunInfos.of("runner.name.embedded", spanish, List.of()).locale()).isEqualTo(Locale.of("es"));
        assertThat(RunInfos.of("runner.name.embedded", new MockEnvironment(), List.of()).locale()).isEqualTo(Locale.ENGLISH);

        MockEnvironment french = new MockEnvironment();
        french.setProperty("idm.test.report.locale", "fr");
        assertThatThrownBy(() -> RunInfos.of("runner.name.embedded", french, List.of()))
                .isInstanceOf(ReportLocale.UnsupportedLocaleException.class)
                .hasMessage("unsupported report locale 'fr'; supported: en, es");
    }

    @Test
    @DisplayName("The environment variable and system property are read after the property, in that order")
    void testReportLocaleSources() {
        MockEnvironment none = new MockEnvironment();
        assertThat(RunInfos.locale(none, "es", null)).isEqualTo(Locale.of("es"));
        assertThat(RunInfos.locale(none, null, "es")).isEqualTo(Locale.of("es"));
        assertThat(RunInfos.locale(none, " ", null)).isEqualTo(Locale.ENGLISH);
        MockEnvironment english = new MockEnvironment();
        english.setProperty("idm.test.report.locale", "en");
        assertThat(RunInfos.locale(english, "es", "es")).isEqualTo(Locale.ENGLISH);
    }
}
