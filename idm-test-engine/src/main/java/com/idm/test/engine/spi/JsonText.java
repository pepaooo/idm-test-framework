package com.idm.test.engine.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idm.test.engine.result.Secrets;

/**
 * Masking for text that looks like JSON but does not parse strictly (a trailing comma, comments,
 * several documents, cut short, …). It never fails open: it walks the text, tracking string
 * literals, and wherever a key ({@code "…"}, {@code '…'} or a bare name) followed by {@code :} is a
 * secret, its value is replaced by {@code "***"}. A string or scalar value is replaced up to its end;
 * an object or array value is replaced up to its balanced closing bracket, or to the end of the
 * text when it never closes.
 *
 * <p>Residual limits: a comment between a key and its colon hides the key, and over-masking is
 * possible when the text is too broken to tell keys from values.
 */
final class JsonText {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MASKED = "\"" + Secrets.MASK + "\"";

    private JsonText() {
    }

    static String mask(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            int tokenEnd;
            String key;
            if (c == '"' || c == '\'') {
                tokenEnd = stringEnd(text, i);
                key = unquote(text.substring(i, tokenEnd));
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                tokenEnd = i + 1;
                while (tokenEnd < n && (Character.isLetterOrDigit(text.charAt(tokenEnd))
                        || "_$-".indexOf(text.charAt(tokenEnd)) >= 0)) {
                    tokenEnd++;
                }
                key = text.substring(i, tokenEnd);
            } else {
                out.append(c);
                i++;
                continue;
            }
            out.append(text, i, tokenEnd);
            int colon = skipSpace(text, tokenEnd);
            if (colon < n && text.charAt(colon) == ':' && Secrets.isSecret(key)) {
                int valueStart = skipSpace(text, colon + 1);
                out.append(text, tokenEnd, valueStart).append(MASKED);
                i = valueEnd(text, valueStart);
            } else {
                i = tokenEnd;
            }
        }
        return out.toString();
    }

    /** The index just after the string literal starting at {@code start}, or the end of the text. */
    private static int stringEnd(String text, int start) {
        char quote = text.charAt(start);
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return text.length();
    }

    private static String unquote(String literal) {
        if (literal.startsWith("\"")) {
            try {
                return JSON.readValue(literal, String.class);
            } catch (Exception notAStringLiteral) {
                // fall through to the raw text
            }
        }
        boolean closed = literal.length() > 1 && literal.charAt(literal.length() - 1) == literal.charAt(0);
        return literal.substring(1, closed ? literal.length() - 1 : literal.length());
    }

    private static int skipSpace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int valueEnd(String text, int start) {
        if (start >= text.length()) {
            return start;
        }
        char first = text.charAt(start);
        if (first == '"' || first == '\'') {
            return stringEnd(text, start);
        }
        if (first == '{' || first == '[') {
            int depth = 0;
            int i = start;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '"' || c == '\'') {
                    i = stringEnd(text, i);
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                } else if ((c == '}' || c == ']') && --depth == 0) {
                    return i + 1;
                }
                i++;
            }
            return text.length();
        }
        int i = start;
        while (i < text.length() && ",}]\r\n".indexOf(text.charAt(i)) < 0) {
            i++;
        }
        return i;
    }
}
