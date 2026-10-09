package com.idm.test.engine.spi;

import com.idm.test.engine.result.ReportUrls;
import com.idm.test.engine.result.Secrets;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Array;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masking and truncation for the evidence records' constructors. */
final class EvidenceMasking {

    /** Strict: numbers keep their exact value, and anything after the first value is an error. */
    private static final JsonMapper JSON = JsonMappers.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final char BOM = '\uFEFF';

    /*
     * Every pattern below is linear in the text: names are matched possessively and may only start
     * where a name can start, so a long run of name characters is never re-scanned from each of its
     * positions. Masking runs before truncation, over bodies of any size.
     */

    /** An opening tag (not self-closing); group 1 is the qualified name, group 2 the local name. */
    private static final Pattern XML_OPEN =
            Pattern.compile("<((?:[\\w.-]++:)?+([\\w.-]++))(?:\\s[^<>]*+)?+(?<!/)>");
    private static final Pattern XML_ATTRIBUTE =
            Pattern.compile("(?<![\\w.:-])([\\w.:-]++)(\\s*+=\\s*+)(\"[^\"]*+\"|'[^']*+')");
    /** A name=value pair at the start of the text or right after an {@code &}. */
    private static final Pattern FORM_PAIR = Pattern.compile("(?<![^&])([^=&]++)=([^&]*+)");
    private static final String MORE_VALUES = " more values";
    private static final String TRUNCATED = "… [truncated, ";

    private EvidenceMasking() {
    }

    static Map<String, List<String>> singleValued(Map<String, String> headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, value) -> result.put(name, List.of(value == null ? "" : value)));
        }
        return result;
    }

    /** Secret-named headers masked; {@code Location} and {@code Content-Location} values sanitised as URLs. */
    static Map<String, List<String>> headers(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, List<String>> masked = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            int count = values == null || values.isEmpty() ? 1 : values.size();
            if (Secrets.isSecret(name)) {
                masked.put(name, Collections.nCopies(count, Secrets.MASK));
                return;
            }
            boolean url = "location".equalsIgnoreCase(name) || "content-location".equalsIgnoreCase(name);
            List<String> kept = new ArrayList<>();
            if (values != null) {
                values.forEach(value -> kept.add(url ? ReportUrls.sanitise(value) : value));
            }
            masked.put(name, Collections.unmodifiableList(kept));
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
        if (!trimmed.isEmpty() && trimmed.charAt(0) == BOM) {
            trimmed = trimmed.substring(1).strip();
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON.writeValueAsString(Secrets.maskByKey(JSON.readValue(trimmed, Object.class)));
            } catch (Exception notStrictJson) {
                return JsonText.mask(text);
            }
        }
        if (trimmed.startsWith("<")) {
            return xml(text);
        }
        if (trimmed.indexOf('=') >= 0 && trimmed.chars().noneMatch(Character::isWhitespace)) {
            return form(text);
        }
        return text;
    }

    /**
     * The whole content of every element whose local name is a secret (text, CDATA or child
     * elements) becomes {@value Secrets#MASK}, up to its closing tag or, when there is none, to
     * the end; then secret-named attributes are masked.
     */
    private static String xml(String text) {
        StringBuilder out = new StringBuilder(text.length());
        Matcher open = XML_OPEN.matcher(text);
        int position = 0;
        int search = 0;
        while (search < text.length() && open.find(search)) {
            search = open.end();
            if (!Secrets.isSecret(open.group(2))) {
                continue;
            }
            out.append(text, position, open.end());
            Matcher close = Pattern.compile("</" + Pattern.quote(open.group(1)) + "\\s*>", Pattern.CASE_INSENSITIVE)
                    .matcher(text);
            out.append(Secrets.MASK);
            if (close.find(open.end())) {
                out.append(close.group());
                position = close.end();
            } else {
                position = text.length();
            }
            search = position;
        }
        out.append(text, position, text.length());
        return XML_ATTRIBUTE.matcher(out).replaceAll(match -> {
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

    /** Text cut at {@code limit} with a note; text already cut this way is returned as it is. */
    static String truncate(String text, int limit) {
        if (text == null || text.length() <= limit || alreadyTruncated(text, limit)) {
            return text;
        }
        return text.substring(0, limit) + TRUNCATED + (text.length() - limit) + " more characters]";
    }

    private static boolean alreadyTruncated(String text, int limit) {
        String suffix = " more characters]";
        return text.length() >= limit + TRUNCATED.length() + suffix.length()
                && text.startsWith(TRUNCATED, limit) && text.endsWith(suffix)
                && text.substring(limit + TRUNCATED.length(), text.length() - " more characters]".length())
                        .chars().allMatch(Character::isDigit);
    }

    /**
     * Numbers, booleans and null as they are; binary as its size; arrays as lists of their elements;
     * anything else as text, cut at the value limit.
     */
    static Object value(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes>";
        }
        if (value.getClass().isArray()) {
            List<Object> elements = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                elements.add(value(Array.get(value, i)));
            }
            return Collections.unmodifiableList(elements);
        }
        return truncate(String.valueOf(value), Evidence.MAX_VALUE_CHARS);
    }

    static List<Object> parameters(String sql, List<Object> params) {
        if (params == null) {
            return List.of();
        }
        Set<Integer> secret = SqlPlaceholders.secret(sql);
        List<Object> masked = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            masked.add(secret.contains(i) ? Secrets.MASK : value(params.get(i)));
        }
        return Collections.unmodifiableList(masked);
    }

    /** At most {@link Evidence#MAX_ROWS} rows, null rows skipped. */
    static List<Map<String, Object>> rows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (kept.size() == Evidence.MAX_ROWS) {
                break;
            }
            if (row == null) {
                continue;
            }
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
        List<?> values;
        if (value instanceof Collection<?> collection) {
            values = new ArrayList<>(collection);
        } else if (value != null && value.getClass().isArray() && !(value instanceof byte[])) {
            values = (List<?>) value(value);
        } else {
            return value(value);
        }
        if (values.size() == Evidence.MAX_ATTRIBUTE_VALUES + 1
                && values.get(Evidence.MAX_ATTRIBUTE_VALUES) instanceof String note
                && note.startsWith("… ") && note.endsWith(MORE_VALUES)) {
            // Already capped: the note stands for the values dropped then.
            values = values.subList(0, Evidence.MAX_ATTRIBUTE_VALUES);
            List<Object> kept = new ArrayList<>();
            values.forEach(v -> kept.add(value(v)));
            kept.add(note);
            return Collections.unmodifiableList(kept);
        }
        List<Object> kept = new ArrayList<>();
        values.stream().limit(Evidence.MAX_ATTRIBUTE_VALUES).forEach(v -> kept.add(value(v)));
        if (values.size() > Evidence.MAX_ATTRIBUTE_VALUES) {
            kept.add("… " + (values.size() - Evidence.MAX_ATTRIBUTE_VALUES) + MORE_VALUES);
        }
        return Collections.unmodifiableList(kept);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
