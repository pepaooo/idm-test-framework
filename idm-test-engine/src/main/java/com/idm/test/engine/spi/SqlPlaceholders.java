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

    private SqlPlaceholders() {
    }

    /**
     * Indexes of the {@code ?} placeholders bound to a secret-named column. MySQL reads {@code #} as
     * a line comment and {@code \'} as an escaped quote, other databases do not; when the statement
     * holds either, every reading is analysed and the results are combined, so a secret is masked
     * whichever dialect runs it (at worst a parameter is masked that need not be).
     */
    static Set<Integer> secret(String sql) {
        if (sql == null) {
            return Set.of();
        }
        Set<Integer> secret = new HashSet<>(analyse(plain(sql, false, false)));
        boolean hash = sql.indexOf('#') >= 0;
        boolean backslash = sql.indexOf('\\') >= 0;
        if (hash) {
            secret.addAll(analyse(plain(sql, true, false)));
        }
        if (backslash) {
            secret.addAll(analyse(plain(sql, false, true)));
        }
        if (hash && backslash) {
            secret.addAll(analyse(plain(sql, true, true)));
        }
        return secret;
    }

    private static Set<Integer> analyse(String plain) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < plain.length(); i++) {
            if (plain.charAt(i) == '?') {
                positions.add(i);
            }
        }
        Set<Integer> secretPositions = new HashSet<>();
        insertValues(plain, secretPositions);
        for (int position : positions) {
            if (comparedToSecret(plain, position)) {
                secretPositions.add(position);
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
     * Whether the {@code ?} at {@code position} follows {@code column =}, {@code <>}, {@code !=} or
     * {@code LIKE} with a secret-named (optionally qualified) column. It reads backwards from the
     * {@code ?} and never past the previous {@code ?}, so all placeholders together take one pass.
     */
    static boolean comparedToSecret(String plain, int position) {
        int i = skipSpaceBack(plain, position - 1);
        if (i < 0) {
            return false;
        }
        char c = plain.charAt(i);
        if (c == '=') {
            i--;
            if (i >= 0 && plain.charAt(i) == '!') {
                i--;
            }
        } else if (c == '>' && i > 0 && plain.charAt(i - 1) == '<') {
            i -= 2;
        } else if (i >= 3 && plain.regionMatches(true, i - 3, "like", 0, 4) && (i == 3 || !isWord(plain.charAt(i - 4)))) {
            i -= 4;
        } else {
            return false;
        }
        int end = skipSpaceBack(plain, i) + 1;
        int start = end;
        while (start > 0 && (isWord(plain.charAt(start - 1)) || plain.charAt(start - 1) == '.')) {
            start--;
        }
        String column = plain.substring(start, end);
        return !column.isEmpty() && Secrets.isSecret(column.substring(column.lastIndexOf('.') + 1));
    }

    private static int skipSpaceBack(String text, int from) {
        int i = from;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        return i;
    }

    private static boolean isWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /**
     * The statement with positions kept: {@code '…'} literals and {@code --} / {@code /* *}{@code /}
     * comments become spaces, and identifier quotes ({@code "}, {@code `}, {@code [ ]}) become
     * spaces while the quoted name stays. {@code hashComments} also blanks {@code # …} line comments;
     * {@code backslashEscapes} lets {@code \} escape the next character inside a literal.
     */
    static String plain(String sql, boolean hashComments, boolean backslashEscapes) {
        char[] out = sql.toCharArray();
        int i = 0;
        while (i < out.length) {
            char c = out[i];
            if (c == '\'') {
                int end = i + 1;
                while (end < out.length) {
                    if (backslashEscapes && out[end] == '\\') {
                        end += 2;
                        continue;
                    }
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
            } else if ((c == '-' && i + 1 < out.length && out[i + 1] == '-') || (c == '#' && hashComments)) {
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
