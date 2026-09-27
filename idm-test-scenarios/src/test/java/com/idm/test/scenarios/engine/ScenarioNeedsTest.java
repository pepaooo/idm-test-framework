package com.idm.test.scenarios.engine;

import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.model.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScenarioNeeds")
class ScenarioNeedsTest {

    private static ScenarioNeeds needs(String yaml) {
        Scenario scenario = ScenarioLoader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "t.yaml");
        return ScenarioNeeds.of(List.of(scenario));
    }

    @Test
    @DisplayName("Each step family sets its own need, and nothing else")
    void testEachFamily() {
        ScenarioNeeds ldap = needs("""
                name: l
                steps:
                  - ldap.delete:
                      dn: cn=x,ou=users
                """);
        assertThat(ldap).isEqualTo(new ScenarioNeeds(true, java.util.Set.of(), false, false, java.util.Set.of()));

        ScenarioNeeds all = needs("""
                name: all
                steps:
                  - db.execute:
                      database: workday
                      statement: DELETE FROM t
                  - db.verify:
                      database: sap
                      query: SELECT 1
                      expect: { rowCount: 1 }
                  - roles.grant:
                      user: cn=u
                      roles: [cn=r]
                  - resources.grant:
                      user: cn=u
                      resource: cn=res
                  - rest.request:
                      target: idm
                      method: GET
                      path: /x
                  - http.request:
                      target: roleservice
                      method: POST
                      path: /y
                """);
        assertThat(all.ldap()).isFalse();
        assertThat(all.databases()).containsExactly("sap", "workday");
        assertThat(all.roles()).isTrue();
        assertThat(all.resources()).isTrue();
        assertThat(all.soap()).isTrue();
        assertThat(all.restTargets()).containsExactly("idm", "roleservice");
    }

    @Test
    @DisplayName("Entries only in cleanup count")
    void testCleanupCounts() {
        ScenarioNeeds needs = needs("""
                name: c
                steps:
                  - set:
                      x: 1
                cleanup:
                  - db.execute:
                      database: sap
                      statement: DELETE FROM t
                  - resources.revoke:
                      user: cn=u
                      resource: cn=res
                """);
        assertThat(needs.databases()).containsExactly("sap");
        assertThat(needs.resources()).isTrue();
        assertThat(needs.roles()).isFalse();
    }

    @Test
    @DisplayName("Run-time names and nulls are skipped; non-strings are converted")
    void testLiteralRules() {
        ScenarioNeeds needs = needs("""
                name: r
                fixtures:
                  cfg:
                    values: { db: sap }
                steps:
                  - db.verify:
                      database: "${cfg.db}"
                      query: SELECT 1
                      expect: { rowCount: 1 }
                  - db.verify:
                      database: 1
                      query: SELECT 1
                      expect: { rowCount: 1 }
                  - rest.request:
                      method: GET
                      path: /x
                """);
        assertThat(needs.databases()).containsExactly("1");
        assertThat(needs.restTargets()).isEmpty();
    }

    @Test
    @DisplayName("A scenario with only set: needs nothing")
    void testNothing() {
        assertThat(needs("""
                name: n
                steps:
                  - set:
                      x: 1
                """)).isEqualTo(new ScenarioNeeds(false, java.util.Set.of(), false, false, java.util.Set.of()));
    }
}
