package com.idm.test.engine.spi;

import com.idm.test.engine.result.Secrets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the {@code ?} placeholders of a SQL statement that bind a secret-named column, by
 * {@code INSERT … (cols) VALUES (…), (…)} position or by {@code col = ?} / {@code <>} / {@code !=} /
 * {@code LIKE ?} comparison.
 */
final class SqlPlaceholders {

    private static final Pattern INSERT = Pattern.compile("(?is)\\binsert\\s+into\\s+[^()]+?\\(");
    private static final Pattern VALUES = Pattern.compile("(?is)\\s*values\\s*\\(");
    private static final Pattern NEXT_ROW = Pattern.compile("\\s*,\\s*\\(");
    private static final Pattern COMPARISON = Pattern.compile("(?is)([\\w.]+)\\s*(?:=|<>|!=|\\blike)\\s*$");

    private SqlPlaceholders() {
    }

    /** Indexes of the {@code ?} placeholders bound to a secret-named column. */
    static Set<Integer> secret(String sql) {
        if (sql == null) {
            return Set.of();
        }
        String plain = plain(sql);
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < plain.length(); i++) {
            if (plain.charAt(i) == '?') {
                positions.add(i);
            }
        }
        Set<Integer> secretPositions = new HashSet<>();
        insertValues(plain, secretPositions);
        for (int position : positions) {
            Matcher comparison = COMPARISON.matcher(plain.substring(0, position));
            if (comparison.find()) {
                String column = comparison.group(1);
                if (Secrets.isSecret(column.substring(column.lastIndexOf('.') + 1))) {
                    secretPositions.add(position);
                }
            }
        }
        Set<Integer> secret = new HashSet<>();
        for (int i = 0; i < positions.size(); i++) {
            if (secretPositions.contains(positions.get(i))) {
                secret.add(i);
            }
        }
        return secret;
    }

    /**
     * The statement with positions kept: {@code '…'} literals and {@code --} / {@code /* *}{@code /}
     * comments become spaces, and identifier quotes ({@code "}, {@code `}, {@code [ ]}) become
     * spaces while the quoted name stays.
     */
    static String plain(String sql) {
        char[] out = sql.toCharArray();
        int i = 0;
        while (i < out.length) {
            char c = out[i];
            if (c == '\'') {
                int end = i + 1;
                while (end < out.length) {
                    if (out[end] == '\'') {
                        if (end + 1 < out.length && out[end + 1] == '\'') {
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    end++;
                }
                i = blank(out, i, end + 1);
            } else if (c == '-' && i + 1 < out.length && out[i + 1] == '-') {
                int end = i;
                while (end < out.length && out[end] != '\n') {
                    end++;
                }
                i = blank(out, i, end);
            } else if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
                int close = sql.indexOf("*/", i + 2);
                i = blank(out, i, close < 0 ? out.length : close + 2);
            } else if (c == '"' || c == '`' || c == '[') {
                char closing = c == '[' ? ']' : c;
                int end = i + 1;
                while (end < out.length && out[end] != closing) {
                    end++;
                }
                out[i] = ' ';
                if (end < out.length) {
                    out[end] = ' ';
                }
                i = end + 1;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int blank(char[] text, int from, int to) {
        int end = Math.min(to, text.length);
        for (int i = from; i < end; i++) {
            if (text[i] != '\n') {
                text[i] = ' ';
            }
        }
        return end;
    }

    /** Marks every {@code ?} in the n-th value of each VALUES row whose n-th column is a secret. */
    private static void insertValues(String plain, Set<Integer> secretPositions) {
        Matcher insert = INSERT.matcher(plain);
        int from = 0;
        while (from < plain.length() && insert.find(from)) {
            from = insert.end();
            List<int[]> columnRanges = tuple(plain, insert.end());
            if (columnRanges == null) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (int[] range : columnRanges) {
                String column = plain.substring(range[0], range[1]).strip();
                columns.add(column.substring(column.lastIndexOf('.') + 1).strip());
            }
            int after = columnRanges.get(columnRanges.size() - 1)[1] + 1;
            Matcher row = VALUES.matcher(plain).region(after, plain.length());
            while (row.lookingAt()) {
                List<int[]> items = tuple(plain, row.end());
                if (items == null) {
                    break;
                }
                for (int k = 0; k < items.size() && k < columns.size(); k++) {
                    if (Secrets.isSecret(columns.get(k))) {
                        for (int p = items.get(k)[0]; p < items.get(k)[1]; p++) {
                            if (plain.charAt(p) == '?') {
                                secretPositions.add(p);
                            }
                        }
                    }
                }
                after = items.get(items.size() - 1)[1] + 1;
                from = after;
                row = NEXT_ROW.matcher(plain).region(after, plain.length());
            }
        }
    }

    /**
     * The top-level items of the parenthesised list starting just after {@code (} at {@code start},
     * as [from, to) ranges; the last range ends at the closing {@code )}. Null when it never closes.
     */
    private static List<int[]> tuple(String plain, int start) {
        List<int[]> items = new ArrayList<>();
        int depth = 0;
        int itemStart = start;
        for (int i = start; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            } else if (c == ')') {
                items.add(new int[]{itemStart, i});
                return items;
            } else if (c == ',' && depth == 0) {
                items.add(new int[]{itemStart, i});
                itemStart = i + 1;
            }
        }
        return null;
    }
}
