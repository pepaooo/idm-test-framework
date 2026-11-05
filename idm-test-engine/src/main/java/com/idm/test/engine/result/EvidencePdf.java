package com.idm.test.engine.result;

import com.idm.test.engine.spi.CommandEvidence;
import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.spi.FileEvidence;
import com.idm.test.engine.spi.HttpEvidence;
import com.idm.test.engine.spi.LdapEvidence;
import com.idm.test.engine.spi.SqlEvidence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.idm.test.engine.result.EvidenceHtml.count;
import static com.idm.test.engine.result.HtmlReportWriter.duration;
import static com.idm.test.engine.result.HtmlReportWriter.label;

/**
 * Draws a step's evidence into the detailed PDF, with the same fields in the same order as
 * {@link EvidenceHtml}. Evidence is unbounded text, so it goes in paragraphs and monospace blocks,
 * which continue across pages, never in table cells, which cannot.
 */
final class EvidencePdf {

    private EvidencePdf() {
    }

    static void append(PdfPages pages, StepResult step, Locale locale) {
        Evidence evidence = step.evidence();
        if (evidence == null) {
            return;
        }
        pages.muted(label("evidence.title", locale) + " · " + EvidenceHtml.summary(evidence, locale)
                + (step.attempts() != null && step.attempts() > 1 ? " " + label("evidence.lastAttempt", locale) : ""));
        switch (evidence) {
            case HttpEvidence http -> http(pages, http, locale);
            case SqlEvidence sql -> sql(pages, sql, locale);
            case LdapEvidence ldap -> ldap(pages, ldap, locale);
            case FileEvidence file -> file(pages, file, locale);
            case CommandEvidence command -> command(pages, command, locale);
        }
    }

    private static void http(PdfPages pages, HttpEvidence http, Locale locale) {
        StringBuilder request = new StringBuilder(http.method() + " " + http.url()).append('\n');
        headers(request, http.requestHeaders());
        body(request, http.requestBody());
        pages.muted(label("evidence.request", locale));
        pages.mono(request.toString());

        StringBuilder response = new StringBuilder();
        if (http.status() == null) {
            response.append(label("evidence.noResponseError", locale, http.error()));
        } else {
            response.append(http.status() + " · " + duration(http.durationMillis(), locale)).append('\n');
            headers(response, http.responseHeaders());
            body(response, http.responseBody());
        }
        pages.muted(label("evidence.response", locale));
        pages.mono(response.toString());
    }

    private static void headers(StringBuilder text, Map<String, List<String>> headers) {
        if (headers == null) {
            return;
        }
        headers.forEach((name, values) -> text.append(name).append(": ")
                .append(String.join(", ", values == null ? List.<String>of() : values)).append('\n'));
    }

    private static void body(StringBuilder text, Object body) {
        if (body == null || body instanceof String s && s.isEmpty()) {
            return;
        }
        text.append('\n').append(EvidenceHtml.pretty(body));
    }

    private static void sql(PdfPages pages, SqlEvidence sql, Locale locale) {
        pages.mono(sql.sql());
        if (!sql.params().isEmpty()) {
            pages.muted(label("evidence.params", locale, EvidenceHtml.compact(sql.params())));
        }
        if (sql.updateCount() != null) {
            pages.paragraph(label("evidence.updated", locale,
                    count(sql.updateCount(), "evidence.rows.one", "evidence.rows.other", locale)));
            return;
        }
        if (sql.rows() == null) {
            return;
        }
        if (sql.rows().isEmpty()) {
            pages.paragraph(label("evidence.noRows", locale));
            return;
        }
        Set<String> columns = new LinkedHashSet<>();
        sql.rows().forEach(row -> columns.addAll(row.keySet()));
        List<String> header = List.copyOf(columns);
        List<List<String>> cells = new ArrayList<>();
        for (Map<String, Object> row : sql.rows()) {
            List<String> line = new ArrayList<>();
            for (String column : header) {
                line.add(row.containsKey(column) ? String.valueOf(row.get(column)) : "");
            }
            cells.add(line);
        }
        pages.mono(grid(header, cells));
        moreRows(pages, sql.totalRows(), sql.rows().size(), locale);
    }

    private static void moreRows(PdfPages pages, Integer totalRows, int shown, Locale locale) {
        int total = totalRows != null ? totalRows : shown;
        if (total - shown > 0) {
            pages.muted(label("evidence.moreRows", locale, total - shown));
        }
    }

    /** Characters one 8 pt DejaVu Sans Mono line holds across the page's text width, about 104. */
    private static final int MONO_LINE = 100;

    /**
     * Columns padded to their widest value, so the block reads as a table and still splits line by line. When
     * the padded table is wider than a mono line it would wrap into noise, so each record is written as
     * {@code column: value} lines with a blank line between records.
     */
    private static String grid(List<String> header, List<List<String>> rows) {
        int[] widths = new int[header.size()];
        List<List<String>> all = new ArrayList<>();
        all.add(header.stream().map(EvidencePdf::oneLine).toList());
        rows.forEach(row -> all.add(row.stream().map(EvidencePdf::oneLine).toList()));
        for (List<String> row : all) {
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        StringBuilder out = new StringBuilder();
        int width = (widths.length - 1) * 3;
        for (int w : widths) {
            width += w;
        }
        if (width > MONO_LINE) {
            for (List<String> row : all.subList(1, all.size())) {
                for (int i = 0; i < widths.length; i++) {
                    out.append(all.getFirst().get(i)).append(": ").append(row.get(i)).append('\n');
                }
                out.append('\n');
            }
            return out.toString();
        }
        for (List<String> row : all) {
            for (int i = 0; i < widths.length; i++) {
                String value = row.get(i);
                out.append(value);
                if (i < widths.length - 1) {
                    out.append(" ".repeat(widths[i] - value.length())).append(" | ");
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String oneLine(String value) {
        return value.replace("\r", "").replace("\n", "\\n");
    }

    private static void ldap(PdfPages pages, LdapEvidence ldap, Locale locale) {
        StringBuilder head = new StringBuilder(ldap.dn() + " · " + ldap.operation());
        if (ldap.directory() != null) {
            head.append(" · ").append(label("evidence.directory", locale, ldap.directory()));
        }
        if (ldap.present() != null) {
            head.append(" · ").append(label(ldap.present() ? "evidence.present" : "evidence.absent", locale));
        }
        pages.paragraph(head.toString());
        if (ldap.attributes().isEmpty()) {
            return;
        }
        StringBuilder attributes = new StringBuilder();
        ldap.attributes().forEach((name, value) -> {
            if (value instanceof Collection<?> values) {
                values.forEach(v -> attributes.append(name).append(": ").append(v).append('\n'));
            } else {
                attributes.append(name).append(": ").append(value).append('\n');
            }
        });
        pages.mono(attributes.toString());
    }

    private static void file(PdfPages pages, FileEvidence file, Locale locale) {
        StringBuilder head = new StringBuilder(file.file()).append(" · ")
                .append(label("evidence.file.folder", locale, file.folder()));
        if (file.size() != null) {
            head.append(" · ").append(label("evidence.file.size", locale, file.size()));
        }
        pages.paragraph(head.toString());
        if (file.header().isEmpty()) {
            return;
        }
        if (file.rows().isEmpty()) {
            pages.paragraph(label("evidence.noRows", locale));
        } else {
            List<String> header = new ArrayList<>();
            header.add("#");
            header.addAll(file.header());
            List<List<String>> cells = new ArrayList<>();
            for (FileEvidence.Row row : file.rows()) {
                List<String> line = new ArrayList<>();
                // a selected row is marked, as the HTML bolds it
                line.add((row.selected() ? "*" : "") + row.number());
                for (String column : file.header()) {
                    Object value = row.values().get(column);
                    line.add(value == null ? "" : String.valueOf(value));
                }
                cells.add(line);
            }
            pages.mono(grid(header, cells));
        }
        moreRows(pages, file.totalRows(), file.rows().size(), locale);
    }

    private static void command(PdfPages pages, CommandEvidence command, Locale locale) {
        if (command.argv().isEmpty()) {
            return;
        }
        pages.mono(EvidenceHtml.commandLine(command));
        stream(pages, label("evidence.command.stdout", locale), command.stdout());
        stream(pages, label("evidence.command.stderr", locale), command.stderr());
    }

    private static void stream(PdfPages pages, String name, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        pages.muted(name);
        pages.mono(text);
    }
}
