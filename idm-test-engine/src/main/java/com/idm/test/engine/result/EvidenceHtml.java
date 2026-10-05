package com.idm.test.engine.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.SqlEvidence;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.idm.test.engine.result.HtmlReportWriter.duration;
import static com.idm.test.engine.result.HtmlReportWriter.esc;

/** Renders a step's evidence into its slot in the report; the evidence is already masked and cut. */
final class EvidenceHtml {

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper COMPACT = new ObjectMapper();

    private EvidenceHtml() {
    }

    static void append(StringBuilder html, StepResult step) {
        Evidence evidence = step.evidence();
        if (evidence == null) {
            html.append("<div class=\"evidence\"></div>\n");
            return;
        }
        html.append("<details class=\"evidence\"").append(step.success() ? "" : " open")
                .append("><summary>Evidence <span class=\"muted\">").append(esc(summary(evidence)));
        if (step.attempts() != null && step.attempts() > 1) {
            html.append(" (last attempt)");
        }
        html.append("</span></summary>\n");
        switch (evidence) {
            case HttpEvidence http -> appendHttp(html, http);
            case SqlEvidence sql -> appendSql(html, sql);
            case LdapEvidence ldap -> appendLdap(html, ldap);
        }
        html.append("</details>\n");
    }

    static String summary(Evidence evidence) {
        return switch (evidence) {
            case HttpEvidence http -> http.method() + " " + path(http.url()) + " → "
                    + (http.status() == null ? "no response" : http.status() + " · " + duration(http.durationMillis()));
            case SqlEvidence sql -> "SQL · " + count(sql.params().size(), "parameter") + " · "
                    + (sql.updateCount() != null ? "updated " + count(sql.updateCount(), "row")
                    : sql.totalRows() != null ? count(sql.totalRows(), "row") : "no result");
            case LdapEvidence ldap -> ldapSummary(ldap);
        };
    }

    private static String ldapSummary(LdapEvidence ldap) {
        String head = ldap.operation() + " " + ldap.dn();
        if (Boolean.FALSE.equals(ldap.present())) {
            return head + " · no entry";
        }
        if ("exists".equals(ldap.operation())) {
            return head + " · present";
        }
        if ("delete".equals(ldap.operation())) {
            return head;
        }
        return head + " · " + count(ldap.attributes().size(), "attribute");
    }

    private static void appendHttp(StringBuilder html, HttpEvidence http) {
        html.append("<div class=\"http\"><div class=\"req\"><div class=\"label\">Request</div><pre>")
                .append(esc(http.method() + " " + http.url())).append('\n');
        appendHeaders(html, http.requestHeaders());
        appendBody(html, http.requestBody());
        html.append("</pre></div><div class=\"res\"><div class=\"label\">Response</div><pre>");
        if (http.status() == null) {
            html.append(esc("no response: " + http.error()));
        } else {
            html.append(esc(http.status() + " · " + duration(http.durationMillis()))).append('\n');
            appendHeaders(html, http.responseHeaders());
            appendBody(html, http.responseBody());
        }
        html.append("</pre></div></div>\n");
    }

    private static void appendHeaders(StringBuilder html, Map<String, List<String>> headers) {
        if (headers == null) {
            return;
        }
        headers.forEach((name, values) -> html.append(esc(name + ": " + String.join(", ", values))).append('\n'));
    }

    private static void appendBody(StringBuilder html, Object body) {
        if (body == null || body instanceof String text && text.isEmpty()) {
            return;
        }
        html.append('\n').append(esc(pretty(body)));
    }

    /** JSON pretty-printed; any other text as it is. */
    private static String pretty(Object body) {
        try {
            if (body instanceof String text) {
                String trimmed = text.strip();
                if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                    return text;
                }
                return PRETTY.writeValueAsString(PRETTY.readValue(trimmed, Object.class));
            }
            return PRETTY.writeValueAsString(body);
        } catch (JsonProcessingException notJson) {
            return String.valueOf(body);
        }
    }

    private static void appendSql(StringBuilder html, SqlEvidence sql) {
        html.append("<pre>").append(esc(sql.sql())).append("</pre>\n");
        if (!sql.params().isEmpty()) {
            html.append("<div class=\"sql-params\">params: ").append(esc(compact(sql.params()))).append("</div>\n");
        }
        if (sql.updateCount() != null) {
            html.append("<div>updated ").append(esc(count(sql.updateCount(), "row"))).append("</div>\n");
            return;
        }
        if (sql.rows() == null) {
            return;
        }
        if (sql.rows().isEmpty()) {
            html.append("<div>no rows</div>\n");
            return;
        }
        html.append("<table class=\"rows\"><tr>");
        sql.rows().get(0).keySet().forEach(column -> html.append("<th>").append(esc(column)).append("</th>"));
        html.append("</tr>\n");
        for (Map<String, Object> row : sql.rows()) {
            html.append("<tr>");
            row.values().forEach(value -> html.append("<td>").append(esc(String.valueOf(value))).append("</td>"));
            html.append("</tr>\n");
        }
        html.append("</table>\n");
        int more = sql.totalRows() - sql.rows().size();
        if (more > 0) {
            html.append("<div class=\"more\">… ").append(more).append(" more rows</div>\n");
        }
    }

    private static void appendLdap(StringBuilder html, LdapEvidence ldap) {
        html.append("<div class=\"dn\">").append(esc(ldap.dn())).append(" · ").append(esc(ldap.operation()));
        if (ldap.directory() != null) {
            html.append(" · directory ").append(esc(ldap.directory()));
        }
        if (ldap.present() != null) {
            html.append(ldap.present() ? " · present" : " · absent");
        }
        html.append("</div>\n");
        if (ldap.attributes().isEmpty()) {
            return;
        }
        html.append("<table class=\"rows\">\n");
        ldap.attributes().forEach((name, value) -> {
            html.append("<tr><th>").append(esc(name)).append("</th><td>");
            if (value instanceof Collection<?> values) {
                html.append(String.join("<br>", values.stream().map(v -> esc(String.valueOf(v))).toList()));
            } else {
                html.append(esc(String.valueOf(value)));
            }
            html.append("</td></tr>\n");
        });
        html.append("</table>\n");
    }

    private static String path(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getRawPath() == null || uri.getRawPath().isEmpty()) {
                return url;
            }
            return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException | NullPointerException e) {
            return String.valueOf(url);
        }
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String compact(Object value) {
        try {
            return COMPACT.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
