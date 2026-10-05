package com.idm.test.engine.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idm.test.engine.result.Secrets;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masking and truncation for the evidence records' constructors. */
final class EvidenceMasking {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A leaf element: {@code <ns:name attrs>text</ns:name>}. */
    private static final Pattern XML_ELEMENT =
            Pattern.compile("<((?:[\\w.-]+:)?([\\w.-]+))(\\s[^<>]*)?>([^<]*)</\\1\\s*>");
    private static final Pattern XML_ATTRIBUTE =
            Pattern.compile("([\\w.:-]+)(\\s*=\\s*)(\"[^\"]*\"|'[^']*')");
    private static final Pattern FORM =
            Pattern.compile("[^\\s=&]+=[^&\\s]*(?:&[^\\s=&]+=[^&\\s]*)*");
    private static final Pattern FORM_PAIR = Pattern.compile("([^=&]+)=([^&]*)");
    private static final Pattern INSERT = Pattern.compile(
            "(?is)\\binsert\\s+into\\s+[\\w.]+\\s*\\(([^)]*)\\)\\s*values\\s*\\(([^)]*)\\)");
    private static final Pattern COMPARISON = Pattern.compile("(?is)([\\w.]+)\\s*(?:=|<>|!=|\\blike)\\s*$");

    private EvidenceMasking() {
    }

    static Map<String, List<String>> singleValued(Map<String, String> headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, value) -> result.put(name, List.of(value == null ? "" : value)));
        }
        return result;
    }

    static Map<String, List<String>> headers(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, List<String>> masked = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            int count = values == null || values.isEmpty() ? 1 : values.size();
            masked.put(name, Secrets.isSecret(name)
                    ? Collections.nCopies(count, Secrets.MASK)
                    : Collections.unmodifiableList(new ArrayList<>(values == null ? List.of() : values)));
        });
        return Collections.unmodifiableMap(masked);
    }

    static Object body(Object body) {
        if (body == null) {
            return null;
        }
        Object masked = body instanceof String text ? text(text) : Secrets.maskByKey(body);
        if (masked instanceof String text) {
            return truncate(text, Evidence.MAX_BODY_CHARS);
        }
        String serialised = json(masked);
        return serialised.length() > Evidence.MAX_BODY_CHARS ? truncate(serialised, Evidence.MAX_BODY_CHARS) : masked;
    }

    /** JSON, XML or form text with secret values masked; any other text unchanged. */
    static String text(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON.writeValueAsString(Secrets.maskByKey(JSON.readValue(trimmed, Object.class)));
            } catch (JsonProcessingException notJson) {
                return text;
            }
        }
        if (trimmed.startsWith("<")) {
            return xml(text);
        }
        if (FORM.matcher(trimmed).matches()) {
            return form(text);
        }
        return text;
    }

    private static String xml(String text) {
        String elements = XML_ELEMENT.matcher(text).replaceAll(match -> Matcher.quoteReplacement(
                Secrets.isSecret(match.group(2))
                        ? "<" + match.group(1) + (match.group(3) == null ? "" : match.group(3)) + ">"
                                + Secrets.MASK + "</" + match.group(1) + ">"
                        : match.group()));
        return XML_ATTRIBUTE.matcher(elements).replaceAll(match -> {
            String name = match.group(1);
            String local = name.substring(name.indexOf(':') + 1);
            if (name.startsWith("xmlns") || !Secrets.isSecret(local)) {
                return Matcher.quoteReplacement(match.group());
            }
            char quote = match.group(3).charAt(0);
            return Matcher.quoteReplacement(name + match.group(2) + quote + Secrets.MASK + quote);
        });
    }

    private static String form(String text) {
        return FORM_PAIR.matcher(text).replaceAll(match -> {
            String name;
            try {
                name = URLDecoder.decode(match.group(1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                name = match.group(1);
            }
            return Matcher.quoteReplacement(Secrets.isSecret(name) ? match.group(1) + "=" + Secrets.MASK : match.group());
        });
    }

    static String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "… [truncated, " + (text.length() - limit) + " more characters]";
    }

    /** Numbers, booleans and null as they are; binary as its size; anything else as text, cut at the value limit. */
    static Object value(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes>";
        }
        return truncate(String.valueOf(value), Evidence.MAX_VALUE_CHARS);
    }

    static List<Object> parameters(String sql, List<Object> params) {
        if (params == null) {
            return List.of();
        }
        Set<Integer> secret = secretParameters(sql);
        List<Object> masked = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            masked.add(secret.contains(i) ? Secrets.MASK : value(params.get(i)));
        }
        return Collections.unmodifiableList(masked);
    }

    /** Indexes of the {@code ?} placeholders bound to a secret-named column. */
    static Set<Integer> secretParameters(String sql) {
        if (sql == null) {
            return Set.of();
        }
        // Quote characters become spaces so positions stay the same.
        String plain = sql.replaceAll("[\"`\\[\\]]", " ");
        List<Integer> positions = placeholders(plain);
        Set<Integer> secret = new HashSet<>();

        Matcher insert = INSERT.matcher(plain);
        while (insert.find()) {
            String[] columns = insert.group(1).split(",");
            int offset = insert.start(2);
            int from = 0;
            String[] values = insert.group(2).split(",", -1);
            for (int column = 0; column < values.length; column++) {
                String value = values[column];
                if (value.strip().equals("?") && column < columns.length && Secrets.isSecret(columns[column].strip())) {
                    int index = positions.indexOf(offset + from + value.indexOf('?'));
                    if (index >= 0) {
                        secret.add(index);
                    }
                }
                from += value.length() + 1;
            }
        }

        for (int i = 0; i < positions.size(); i++) {
            Matcher comparison = COMPARISON.matcher(plain.substring(0, positions.get(i)));
            if (comparison.find()) {
                String column = comparison.group(1);
                if (Secrets.isSecret(column.substring(column.lastIndexOf('.') + 1))) {
                    secret.add(i);
                }
            }
        }
        return secret;
    }

    private static List<Integer> placeholders(String sql) {
        List<Integer> positions = new ArrayList<>();
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (c == '?' && !quoted) {
                positions.add(i);
            }
        }
        return positions;
    }

    static List<Map<String, Object>> rows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), Evidence.MAX_ROWS))) {
            Map<String, Object> copy = new LinkedHashMap<>();
            row.forEach((column, value) -> copy.put(column, Secrets.isSecret(column) ? Secrets.MASK : value(value)));
            kept.add(Collections.unmodifiableMap(copy));
        }
        return Collections.unmodifiableList(kept);
    }

    static Map<String, Object> attributes(Map<String, Object> attributes) {
        if (attributes == null) {
            return Map.of();
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        attributes.forEach((name, value) -> masked.put(name,
                Secrets.isSecret(name) ? Secrets.MASK : attributeValue(value)));
        return Collections.unmodifiableMap(masked);
    }

    private static Object attributeValue(Object value) {
        if (value instanceof Collection<?> values) {
            List<Object> kept = new ArrayList<>();
            values.stream().limit(Evidence.MAX_ATTRIBUTE_VALUES).forEach(v -> kept.add(value(v)));
            if (values.size() > Evidence.MAX_ATTRIBUTE_VALUES) {
                kept.add("… " + (values.size() - Evidence.MAX_ATTRIBUTE_VALUES) + " more values");
            }
            return Collections.unmodifiableList(kept);
        }
        return value(value);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
