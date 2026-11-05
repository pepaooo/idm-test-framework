package com.idm.test.engine.result;

import com.idm.test.engine.spi.CommandEvidence;
import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.spi.FileEvidence;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.SqlEvidence;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EvidencePdf")
class EvidencePdfTest {

    private static String render(Evidence evidence, Integer attempts) throws IOException {
        StepResult step = new StepResult(0, "t.type", Map.of(), true, 5, "ok", attempts, attempts == null ? null : 10L,
                null, evidence);
        try (PdfPages pages = new PdfPages("header", Locale.ENGLISH)) {
            EvidencePdf.append(pages, step, Locale.ENGLISH);
            try (PDDocument doc = Loader.loadPDF(pages.finish())) {
                return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            }
        }
    }

    @Test
    @DisplayName("HTTP shows the request line, headers and body, then the status, headers and body")
    void http() throws IOException {
        HttpEvidence http = new HttpEvidence("POST", "https://idm.lab/api/users",
                Map.of("Content-Type", List.of("application/json")), "{\"name\":\"REQ-BODY\"}", 201,
                Map.of("X-Trace", List.of("abc-1")), "{\"id\":\"RES-BODY\"}", 120, null);

        String text = render(http, null);

        assertThat(text).contains("Evidence").contains("POST /api/users")
                .contains("Request").contains("POST https://idm.lab/api/users")
                .contains("Content-Type: application/json").contains("REQ-BODY")
                .contains("Response").contains("201").contains("X-Trace: abc-1").contains("RES-BODY");
    }

    @Test
    @DisplayName("HTTP without a response shows the error")
    void httpNoResponse() throws IOException {
        String text = render(new HttpEvidence("GET", "https://idm.lab/x", null, null, null, null, null, 0, "refused"),
                null);

        assertThat(text).contains("no response: refused");
    }

    @Test
    @DisplayName("SQL shows the statement, its params and the rows")
    void sql() throws IOException {
        SqlEvidence sql = SqlEvidence.query("db", "SELECT cn FROM users WHERE id = ?", new Object[]{"ID-77"},
                List.of(Map.of("cn", "ROW-ONE")));

        String text = render(sql, null);

        assertThat(text).contains("SELECT cn FROM users WHERE id = ?").contains("params: [\"ID-77\"]")
                .contains("cn").contains("ROW-ONE");
    }

    @Test
    @DisplayName("SQL update shows the count; an empty result says so")
    void sqlUpdateAndEmpty() throws IOException {
        assertThat(render(SqlEvidence.update("db", "DELETE FROM t", new Object[0], 3), null)).contains("updated 3 rows");
        assertThat(render(SqlEvidence.query("db", "SELECT 1", new Object[0], List.of()), null)).contains("no rows");
    }

    @Test
    @DisplayName("LDAP shows the DN, operation, directory and each attribute value")
    void ldap() throws IOException {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("cn", "Ana Ruiz");
        attributes.put("memberOf", List.of("cn=GROUP-A,o=data", "cn=GROUP-B,o=data"));
        LdapEvidence ldap = new LdapEvidence("edir", "cn=ana,ou=users,o=data", "read", attributes, true);

        String text = render(ldap, null);

        assertThat(text).contains("cn=ana,ou=users,o=data").contains("read").contains("directory edir")
                .contains("present").contains("cn: Ana Ruiz").contains("memberOf: cn=GROUP-A,o=data")
                .contains("memberOf: cn=GROUP-B,o=data");
    }

    @Test
    @DisplayName("a file shows its path, folder, size and rows, and says how many more there are")
    void file() throws IOException {
        FileEvidence file = FileEvidence.read("/data/out", "users.csv", 2048, List.of("uid", "mail"),
                List.of(List.of("u1", "u1@lab"), List.of("u2", "FILE-CELL")), Set.of(1));

        String text = render(file, null);

        assertThat(text).contains("users.csv").contains("folder /data/out").contains("2048 bytes")
                .contains("uid").contains("mail").contains("u1@lab").contains("FILE-CELL");
    }

    @Test
    @DisplayName("a command shows its argv, exit code and output")
    void command() throws IOException {
        CommandEvidence command = CommandEvidence.ran("sync", List.of("/bin/sync", "--dir", "my dir"), Set.of(), 3, 250,
                false, "STDOUT-LINE", "STDERR-LINE", 11, 11);

        String text = render(command, null);

        assertThat(text).contains("/bin/sync --dir \"my dir\"").contains("exit 3")
                .contains("stdout").contains("STDOUT-LINE").contains("stderr").contains("STDERR-LINE");
    }

    @Test
    @DisplayName("a command that did not start says so")
    void commandNotStarted() throws IOException {
        assertThat(render(CommandEvidence.notStarted("sync"), null)).contains("not started");
    }

    @Test
    @DisplayName("every kept row of a long result is drawn, down to the last")
    void manyRowsKeepLast() throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            rows.add(Map.of("n", "VALUE-" + i));
        }
        SqlEvidence sql = SqlEvidence.query("db", "SELECT n FROM big", new Object[0], rows);
        String lastKept = String.valueOf(sql.rows().getLast().get("n"));

        assertThat(render(sql, null)).contains(lastKept);
    }

    @Test
    @DisplayName("a step without evidence draws nothing")
    void noEvidence() throws IOException {
        assertThat(render(null, null)).doesNotContain("Evidence");
    }
}
