package com.idm.test.engine.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("INSERT: the n-th top-level value of every VALUES row binds the n-th column, function calls included")
    void sqlInsertRows() {
        assertThat(SqlEvidence.update("db", "INSERT INTO t (a, b, password) VALUES (?, LOWER(?), ?)",
                new Object[]{"x", "y", "S3cret!"}, 1).params()).containsExactly("x", "y", "***");
        assertThat(SqlEvidence.update("db", "INSERT INTO accounts (created_at, username, password) VALUES (NOW(), ?, ?)",
                new Object[]{"jdoe", "S3cret!"}, 1).params()).containsExactly("jdoe", "***");
        assertThat(SqlEvidence.update("db", "INSERT INTO t (u, password) VALUES (?, ?), (?, ?)",
                new Object[]{"a", "S1", "b", "S2"}, 2).params()).containsExactly("a", "***", "b", "***");
        assertThat(SqlEvidence.update("db", "INSERT INTO t (a, password) VALUES ('x,y', LOWER(?))",
                new Object[]{"S3cret!"}, 1).params()).containsExactly("***");
    }

    @Test
    @DisplayName("SQL comments neither count as placeholders nor hide a comparison")
    void sqlComments() {
        assertThat(SqlEvidence.failed("db", "SELECT * FROM t -- who?\n WHERE password = ?",
                new Object[]{"S3cret!"}).params()).containsExactly("***");
        assertThat(SqlEvidence.failed("db", "SELECT * FROM t /* a? */ WHERE password = /* why? */ ?",
                new Object[]{"S3cret!"}).params()).containsExactly("***");
        assertThat(SqlEvidence.failed("db", null, new Object[]{"v"}).params()).containsExactly("v");
    }

    @Test
    @DisplayName("XML: the whole content of a secret element is masked (CDATA, child elements, after a BOM)")
    void xmlContent() {
        assertThat((String) http(Map.of(), "<login><password><![CDATA[p4ss]]></password></login>", null).requestBody())
                .isEqualTo("<login><password>***</password></login>");
        assertThat((String) http(Map.of(), "<password>\n  <value>p4ss</value>\n</password>", null).requestBody())
                .isEqualTo("<password>***</password>");
        assertThat((String) http(Map.of(), "﻿<login><password>p4ss</password></login>", null).requestBody())
                .doesNotContain("p4ss").contains("<password>***</password>");
        assertThat((String) http(Map.of(), "<a><password/><b>keep</b><Password x=\"1\">p4ss", null).requestBody())
                .contains("<b>keep</b>").doesNotContain("p4ss");
    }

    @Test
    @DisplayName("JSON text that does not parse strictly is still masked, never returned as it was")
    void malformedJson() {
        assertThat((String) http(Map.of(), "{\"password\":\"p4ss\",}", null).requestBody())
                .isEqualTo("{\"password\":\"***\",}");
        assertThat((String) http(Map.of(), "{\"a\":1} {\"password\":\"p4ss\"}", null).requestBody())
                .isEqualTo("{\"a\":1} {\"password\":\"***\"}");
        assertThat((String) http(Map.of(), "{/* c */ \"apiKey\": 12345, 'token': true, }", null).requestBody())
                .doesNotContain("12345").doesNotContain("true");
        assertThat((String) http(Map.of(), "{\"credentials\": {\"user\": \"u\", \"pass\": \"p4ss\"}, \"id\": 1,}", null)
                .requestBody()).isEqualTo("{\"credentials\": \"***\", \"id\": 1,}");
        assertThat((String) http(Map.of(), "{\"secret\": [\"p4ss\", {\"x\": \"q\"", null).requestBody())
                .isEqualTo("{\"secret\": \"***\"");
    }

    @Test
    @DisplayName("JSON text keeps exact numbers")
    void jsonNumbers() {
        assertThat(http(Map.of(), "{\"n\":1.10,\"big\":12345678901234567890.5}", null).requestBody())
                .isEqualTo("{\"n\":1.10,\"big\":12345678901234567890.5}");
    }

    @Test
    @DisplayName("Structure bodies: sets and arrays are masked, and the result cannot be changed")
    @SuppressWarnings("unchecked")
    void structureBodies() {
        HttpEvidence set = http(Map.of(), Map.of("creds", Set.of(Map.of("password", "p4ss"))), null);
        assertThat(set.requestBody().toString()).doesNotContain("p4ss");
        HttpEvidence array = http(Map.of(), Map.of("creds", new Object[]{Map.of("password", "p4ss")}), null);
        assertThat(array.requestBody().toString()).doesNotContain("p4ss");

        HttpEvidence evidence = http(Map.of(), Map.of("user", Map.of("name", "n")), null);
        Map<String, Object> body = (Map<String, Object>) evidence.requestBody();
        assertThatThrownBy(() -> body.put("password", "leak")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<String, Object>) body.get("user")).put("password", "leak"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Form bodies with valueless names or empty segments are masked")
    void formEdges() {
        assertThat(http(Map.of(), "flag&password=p4ss", null).requestBody()).isEqualTo("flag&password=***");
        assertThat(http(Map.of(), "a=1&&client_secret=p4ss", null).requestBody()).isEqualTo("a=1&&client_secret=***");
    }

    @Test
    @DisplayName("Error text and Location headers are sanitised as URLs")
    void urlsInFreeText() {
        HttpEvidence evidence = new HttpEvidence("GET", "https://idm.lab/x", Map.of(), null, 302,
                Map.of("Location", List.of("https://idm.lab/cb?token=zz&q=1")), null, 0,
                "refused by https://u:pw9@idm.lab/x?token=zz");
        assertThat(evidence.responseHeaders().get("Location")).containsExactly("https://idm.lab/cb?token=***&q=1");
        assertThat(evidence.error()).doesNotContain("pw9").doesNotContain("zz");
    }

    @Test
    @DisplayName("Null rows and null row entries are tolerated")
    void nullRows() {
        SqlEvidence none = SqlEvidence.query("db", "SELECT 1", null, null);
        assertThat(none.rows()).isNull();
        assertThat(none.totalRows()).isNull();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(null);
        rows.add(Map.of("id", 1));
        assertThat(SqlEvidence.query("db", "SELECT id FROM t", new Object[0], rows).rows())
                .containsExactly(Map.of("id", 1));
    }

    @Test
    @DisplayName("Array values show their elements; binary stays its size")
    void arrayValues() {
        LdapEvidence ldap = new LdapEvidence(null, "cn=u", "read",
                Map.of("mail", new String[]{"a@x", "b@x"}, "jpegPhoto", new byte[]{1, 2, 3}), null);
        assertThat(ldap.attributes()).containsEntry("mail", List.of("a@x", "b@x")).containsEntry("jpegPhoto", "<3 bytes>");
        SqlEvidence sql = SqlEvidence.query("db", "SELECT * FROM t", new Object[0],
                List.of(Map.of("TAGS", new String[]{"x", "y"})));
        assertThat(sql.rows().get(0)).containsEntry("TAGS", List.of("x", "y"));
    }

    @Test
    @DisplayName("Building evidence again from its own values adds no second truncation note")
    void rebuildIsStable() {
        HttpEvidence once = http(Map.of(), "x".repeat(Evidence.MAX_BODY_CHARS + 10), null);
        HttpEvidence twice = http(Map.of(), once.requestBody(), null);
        assertThat(twice.requestBody()).isEqualTo(once.requestBody());

        List<String> members = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            members.add("cn=m" + i);
        }
        LdapEvidence first = new LdapEvidence(null, "cn=g", "read", Map.of("member", members), null);
        LdapEvidence again = new LdapEvidence(null, "cn=g", "read", first.attributes(), null);
        assertThat(again.attributes()).isEqualTo(first.attributes());
    }
}
