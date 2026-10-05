package com.idm.test.engine.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Evidence masking and size limits")
class EvidenceTest {

    private static HttpEvidence http(Map<String, List<String>> requestHeaders, Object requestBody, Object responseBody) {
        return new HttpEvidence("POST", "https://idm.lab/api", requestHeaders, requestBody, 200,
                Map.of("Set-Cookie", List.of("s=1"), "Content-Type", List.of("application/json")), responseBody, 5, null);
    }

    @Test
    @DisplayName("Secret-named headers are masked, others kept")
    void headers() {
        HttpEvidence evidence = http(Map.of("Authorization", List.of("Bearer abc"), "Accept", List.of("x")), null, null);
        assertThat(evidence.requestHeaders()).containsEntry("Authorization", List.of("***")).containsEntry("Accept", List.of("x"));
        assertThat(evidence.responseHeaders()).containsEntry("Set-Cookie", List.of("***"))
                .containsEntry("Content-Type", List.of("application/json"));
        assertThat(HttpEvidence.headersOf(Map.of("X-Api-Key", "k"))).containsEntry("X-Api-Key", List.of("k"));
    }

    @Test
    @DisplayName("JSON structures and JSON text are masked by key at any depth")
    @SuppressWarnings("unchecked")
    void json() {
        HttpEvidence evidence = http(Map.of(),
                Map.of("user", Map.of("password", "p", "name", "n")),
                "{\"apiToken\":\"t0k\",\"id\":1}");
        Map<String, Object> user = (Map<String, Object>) ((Map<String, Object>) evidence.requestBody()).get("user");
        assertThat(user).containsEntry("password", "***").containsEntry("name", "n");
        assertThat(evidence.responseBody()).isEqualTo("{\"apiToken\":\"***\",\"id\":1}");
    }

    @Test
    @DisplayName("XML elements and attributes with secret names are masked, with or without a prefix")
    void xml() {
        HttpEvidence evidence = http(Map.of(),
                "<s:Envelope><s:Body><login><user>jdoe</user><ns:password>p4ss</ns:password></login></s:Body></s:Envelope>",
                "<auth clientSecret=\"x1\" user=\"u\"/>");
        assertThat((String) evidence.requestBody()).contains("<ns:password>***</ns:password>")
                .contains("<user>jdoe</user>").doesNotContain("p4ss");
        assertThat((String) evidence.responseBody()).contains("clientSecret=\"***\"").contains("user=\"u\"")
                .doesNotContain("x1");
    }

    @Test
    @DisplayName("Form bodies are masked by parameter name")
    void form() {
        HttpEvidence evidence = http(Map.of(), "grant_type=client_credentials&client_secret=abc&scope=x", null);
        assertThat(evidence.requestBody()).isEqualTo("grant_type=client_credentials&client_secret=***&scope=x");
    }

    @Test
    @DisplayName("URLs lose user info and secret query values")
    void url() {
        HttpEvidence evidence = new HttpEvidence("GET", "https://u:pw9@idm.lab/x?token=abc&q=1",
                Map.of(), null, null, null, null, 0, "refused");
        assertThat(evidence.url()).doesNotContain("pw9").doesNotContain("abc").contains("token=***").contains("q=1");
    }

    @Test
    @DisplayName("Bodies are cut at 16 384 characters; an oversized structure becomes text")
    void bodyLimit() {
        HttpEvidence text = http(Map.of(), "x".repeat(Evidence.MAX_BODY_CHARS + 10), null);
        assertThat((String) text.requestBody()).startsWith("x".repeat(Evidence.MAX_BODY_CHARS))
                .endsWith("… [truncated, 10 more characters]");

        HttpEvidence structure = http(Map.of(), Map.of("data", "y".repeat(20_000)), null);
        assertThat(structure.requestBody()).isInstanceOf(String.class);
        assertThat((String) structure.requestBody()).contains("[truncated, ");
    }

    @Test
    @DisplayName("SQL parameters bound to secret columns are masked; others are kept")
    void sqlParameters() {
        assertThat(SqlEvidence.update("db", "INSERT INTO accounts (username, pwd, note) VALUES (?, ?, ?)",
                new Object[]{"jdoe", "S3cret!", "n"}, 1).params()).containsExactly("jdoe", "***", "n");
        assertThat(SqlEvidence.failed("db", "SELECT * FROM t WHERE username = ? AND password = ?",
                new Object[]{"jdoe", "S3cret!"}).params()).containsExactly("jdoe", "***");
        assertThat(SqlEvidence.update("db", "UPDATE t SET \"Password\" = ? WHERE id = ?",
                new Object[]{"S3cret!", 7}, 1).params()).containsExactly("***", 7);
        assertThat(SqlEvidence.failed("db", "SELECT * FROM t WHERE t.api_key LIKE ?",
                new Object[]{"k"}).params()).containsExactly("***");
        assertThat(SqlEvidence.failed("db", "SELECT '?' AS q FROM t WHERE token <> ?",
                new Object[]{"v"}).params()).containsExactly("***");
        assertThat(SqlEvidence.failed("db", "SELECT * FROM t WHERE f(?) > 1",
                new Object[]{"S3cret!"}).params()).containsExactly("S3cret!");
    }

    @Test
    @DisplayName("Rows: at most 20 kept, secret columns masked, long values cut, binary shown as its size")
    void rows() {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(Map.of("id", i));
        }
        SqlEvidence capped = SqlEvidence.query("db", "SELECT id FROM t", new Object[0], many);
        assertThat(capped.rows()).hasSize(20);
        assertThat(capped.totalRows()).isEqualTo(25);

        Map<String, Object> row = new HashMap<>();
        row.put("PASSWORD", "hunter2");
        row.put("NOTE", "z".repeat(1_100));
        row.put("PHOTO", new byte[]{1, 2, 3});
        row.put("EMPTY", null);
        SqlEvidence one = SqlEvidence.query("db", "SELECT * FROM t", new Object[0], List.of(row));
        assertThat(one.rows().get(0)).containsEntry("PASSWORD", "***").containsEntry("PHOTO", "<3 bytes>")
                .containsEntry("EMPTY", null);
        assertThat((String) one.rows().get(0).get("NOTE")).endsWith("… [truncated, 76 more characters]");
    }

    @Test
    @DisplayName("LDAP: secret attributes masked, at most 20 values per attribute")
    void ldap() {
        List<String> members = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            members.add("cn=m" + i);
        }
        LdapEvidence evidence = new LdapEvidence(null, "cn=u,ou=users", "create",
                Map.of("userPassword", "x", "unicodePwd", List.of("y"), "member", members, "sn", "U"), null);
        assertThat(evidence.attributes()).containsEntry("userPassword", "***").containsEntry("unicodePwd", "***")
                .containsEntry("sn", "U");
        List<?> kept = (List<?>) evidence.attributes().get("member");
        assertThat(kept).hasSize(21);
        assertThat(kept.get(20)).isEqualTo("… 5 more values");
    }

    @Test
    @DisplayName("Factories set the operation and presence")
    void ldapFactories() {
        assertThat(LdapEvidence.deleted("ad", "cn=u").operation()).isEqualTo("delete");
        assertThat(LdapEvidence.exists(null, "cn=u", true).present()).isTrue();
        assertThat(LdapEvidence.absent(null, "cn=u", "read").present()).isFalse();
        assertThat(LdapEvidence.read(null, "cn=u", Map.of("cn", "u")).attributes()).containsEntry("cn", "u");
    }
}
