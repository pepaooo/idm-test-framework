package com.idm.test.engine.result;

import com.idm.test.engine.spi.JsonMappers;
import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.SqlEvidence;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.idm.test.engine.result.HtmlReportWriter.duration;
import static com.idm.test.engine.result.HtmlReportWriter.esc;
import static com.idm.test.engine.result.HtmlReportWriter.label;

/** Renders a step's evidence into its slot in the report; the evidence is already masked and cut. */
final class EvidenceHtml {

    private static final JsonMapper PRETTY = JsonMappers.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final JsonMapper COMPACT = JsonMappers.builder().build();

    private EvidenceHtml() {
    }

    static void append(StringBuilder html, StepResult step, Locale locale) {
        Evidence evidence = step.evidence();
        if (evidence == null) {
            html.append("<div class=\"evidence\"></div>\n");
            return;
        }
        html.append("<details class=\"evidence\"").append(step.success() ? "" : " open")
                .append("><summary>")
                .append(label("evidence.title", locale)).append(" <span class=\"muted\">")
                .append(esc(summary(evidence, locale)));
        if (step.attempts() != null && step.attempts() > 1) {
            html.append(" ").append(label("evidence.lastAttempt", locale));
        }
        html.append("</span></summary>\n");
        switch (evidence) {
            case HttpEvidence http -> appendHttp(html, http, locale);
            case SqlEvidence sql -> appendSql(html, sql, locale);
            case LdapEvidence ldap -> appendLdap(html, ldap, locale);
        }
        html.append("</details>\n");
    }

    static String summary(Evidence evidence, Locale locale) {
        return switch (evidence) {
            case HttpEvidence http -> http.method() + " " + path(http.url()) + " → "
                    + (http.status() == null ? label("evidence.noResponse", locale)
                    : http.status() + " · " + duration(http.durationMillis(), locale));
            case SqlEvidence sql -> "SQL · "
                    + count(sql.params().size(), "evidence.parameters.one", "evidence.parameters.other", locale) + " · "
                    + (sql.updateCount() != null
                    ? label("evidence.updated", locale,
                            count(sql.updateCount(), "evidence.rows.one", "evidence.rows.other", locale))
                    : sql.totalRows() != null
                    ? count(sql.totalRows(), "evidence.rows.one", "evidence.rows.other", locale)
                    : label("evidence.noResult", locale));
            case LdapEvidence ldap -> ldapSummary(ldap, locale);
        };
    }

    private static String ldapSummary(LdapEvidence ldap, Locale locale) {
        String head = ldap.operation() + " " + ldap.dn();
        if (Boolean.FALSE.equals(ldap.present())) {
            return head + " · " + label("evidence.noEntry", locale);
        }
        if ("exists".equals(ldap.operation())) {
            return head + " · " + label("evidence.present", locale);
        }
        if ("delete".equals(ldap.operation())) {
            return head;
        }
        return head + " · " + count(ldap.attributes().size(), "evidence.attributes.one", "evidence.attributes.other", locale);
    }

    private static void appendHttp(StringBuilder html, HttpEvidence http, Locale locale) {
        html.append("<div class=\"http\"><div class=\"req\"><div class=\"label\">")
                .append(label("evidence.request", locale)).append("</div><pre>")
                .append(esc(http.method() + " " + http.url())).append('\n');
        appendHeaders(html, http.requestHeaders());
        appendBody(html, http.requestBody());
        html.append("</pre></div><div class=\"res\"><div class=\"label\">")
                .append(label("evidence.response", locale)).append("</div><pre>");
        if (http.status() == null) {
            html.append(esc(label("evidence.noResponseError", locale, http.error())));
        } else {
            html.append(esc(http.status() + " · " + duration(http.durationMillis(), locale))).append('\n');
            appendHeaders(html, http.responseHeaders());
            appendBody(html, http.responseBody());
        }
        html.append("</pre></div></div>\n");
    }

    private static void appendHeaders(StringBuilder html, Map<String, List<String>> headers) {
        if (headers == null) {
            return;
        }
        headers.forEach((name, values) -> html.append(esc(name + ": " + String.join(", ", values == null ? List.<String>of() : values))).append('\n'));
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
        } catch (JacksonException notJson) {
            return String.valueOf(body);
        }
    }

    private static void appendSql(StringBuilder html, SqlEvidence sql, Locale locale) {
        html.append("<pre>").append(esc(sql.sql())).append("</pre>\n");
        if (!sql.params().isEmpty()) {
            html.append("<div class=\"sql-params\">")
                    .append(esc(label("evidence.params", locale, compact(sql.params())))).append("</div>\n");
        }
        if (sql.updateCount() != null) {
            html.append("<div>").append(esc(label("evidence.updated", locale,
                    count(sql.updateCount(), "evidence.rows.one", "evidence.rows.other", locale)))).append("</div>\n");
            return;
        }
        if (sql.rows() == null) {
            return;
        }
        if (sql.rows().isEmpty()) {
            html.append("<div>").append(label("evidence.noRows", locale)).append("</div>\n");
            return;
        }
        Set<String> columns = new LinkedHashSet<>();
        sql.rows().forEach(row -> columns.addAll(row.keySet()));
        html.append("<table class=\"rows\"><tr>");
        columns.forEach(column -> html.append("<th>").append(esc(column)).append("</th>"));
        html.append("</tr>\n");
        for (Map<String, Object> row : sql.rows()) {
            html.append("<tr>");
            for (String column : columns) {
                html.append("<td>").append(row.containsKey(column) ? esc(String.valueOf(row.get(column))) : "")
                        .append("</td>");
            }
            html.append("</tr>\n");
        }
        html.append("</table>\n");
        int total = sql.totalRows() != null ? sql.totalRows() : sql.rows().size();
        int more = total - sql.rows().size();
        if (more > 0) {
            html.append("<div class=\"more\">").append(esc(label("evidence.moreRows", locale, more)))
                    .append("</div>\n");
        }
    }

    private static void appendLdap(StringBuilder html, LdapEvidence ldap, Locale locale) {
        html.append("<div class=\"dn\">").append(esc(ldap.dn())).append(" · ").append(esc(ldap.operation()));
        if (ldap.directory() != null) {
            html.append(" · ").append(esc(label("evidence.directory", locale, ldap.directory())));
        }
        if (ldap.present() != null) {
            html.append(" · ").append(label(ldap.present() ? "evidence.present" : "evidence.absent", locale));
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

    private static String count(int n, String oneKey, String otherKey, Locale locale) {
        return label(n == 1 ? oneKey : otherKey, locale, n);
    }

    private static String compact(Object value) {
        try {
            return COMPACT.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }
}
