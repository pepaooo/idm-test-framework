package com.idm.test.runner.ui.lab;

import com.idm.test.runner.ui.WorkspaceFiles;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sets keys of a profile's settings file ({@code config/application-<profile>.yml}) in place. SnakeYAML
 * only reads; an edit changes lines of the text, found by the key's dotted path, and leaves every other
 * byte as it was: comments, blank lines, indentation, each line's own ending (CRLF or LF) and a byte
 * order mark. In order of preference, an edit
 * <ol>
 * <li>replaces the value of the key's line, keeping a comment after it;</li>
 * <li>uncomments the matching line of a commented template ({@code idmtest init} writes one), and the
 *     commented lines of its parent keys, then replaces its value;</li>
 * <li>adds the key — and its missing parents — under its deepest existing parent, at the file's
 *     indentation, after that parent's last line.</li>
 * </ol>
 * Every result is read back: it must hold the new value at the key and every other setting unchanged,
 * or the edit is refused ({@link CannotEditException}), so a file is never written into something that
 * means more than was asked. Values YAML would read as something else ({@code yes}, {@code 012}, a
 * {@code #}, {@code : }, a leading {@code *}, {@code &} or {@code !}, an empty value) are single-quoted.
 * Keys match as Spring binds them ({@code baseDn}, {@code base_dn} and {@code base-dn} are one key).
 * Messages never contain a value.
 */
public final class ProfileYamlEditor {

    /** The shared settings file, which the workbench never writes. */
    public static final String SHARED_FILE = "application.yml";

    private static final Pattern KEY_LINE = Pattern.compile(
            "^( *)(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^']|'')*'|[^\\s\"'#{}\\[\\],&*!|>%@`][^:#]*?)[ \\t]*:(?=[ \\t]|$)(.*)$");
    private static final Pattern COMMENT_LINE = Pattern.compile("^( *)# ?(.*)$");
    /**
     * A key on a commented line that counts as a template line: a simple key not starting with a capital,
     * so an explanation such as {@code # Databases: one entry per database} is not taken for one.
     */
    private static final Pattern SIMPLE_KEY = Pattern.compile("[a-z0-9_][A-Za-z0-9_.-]*");
    private static final String LIST_SEGMENT = "[]";

    private ProfileYamlEditor() {
    }

    /** An edit that cannot be done without changing more than the key; the reason names no value. */
    public static final class CannotEditException extends RuntimeException {
        CannotEditException(String reason) {
            super(reason);
        }
    }

    // --- files ----------------------------------------------------------------------------------

    /** Sets {@code key} to {@code value} in {@code file}, creating the file (and its folder) when missing. */
    public static void set(Path file, String key, String value) throws IOException {
        Map<String, String> one = new LinkedHashMap<>();
        one.put(key, value);
        setAll(file, one);
    }

    /**
     * Sets each key in order and writes the file once, atomically, under the lock editor saves of the
     * file take. Never writes {@code application.yml}.
     *
     * @throws IllegalArgumentException for {@code application.yml}
     * @throws CannotEditException      when a key cannot be set in place; nothing is written then
     */
    public static void setAll(Path file, Map<String, String> values) throws IOException {
        if (file.getFileName().toString().equalsIgnoreCase(SHARED_FILE)) {
            throw new IllegalArgumentException("the workbench never writes " + SHARED_FILE);
        }
        WorkspaceFiles.locked(file, () -> {
            boolean exists = Files.exists(file);
            String text = exists ? decode(Files.readAllBytes(file)) : "";
            for (Map.Entry<String, String> entry : values.entrySet()) {
                text = edit(text, entry.getKey(), entry.getValue());
            }
            if (!exists) {
                Files.createDirectories(file.toAbsolutePath().getParent());
            }
            WorkspaceFiles.writeAtomically(file, text.getBytes(StandardCharsets.UTF_8),
                    PosixFilePermissions.fromString("rw-r--r--"));
            return null;
        });
    }

    /** The settings of {@code file}, flattened ({@link #flatten}); empty when it does not exist. */
    public static Map<String, String> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        return flatten(decode(Files.readAllBytes(file)));
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new CannotEditException("the file is not UTF-8 text");
        }
    }

    // --- reading --------------------------------------------------------------------------------

    /**
     * Every scalar of {@code text} by its dotted path as written ({@code idm.test.ldap.url}); list
     * elements as {@code path[i]}; a null value as an empty string; scalars as their text.
     *
     * @throws CannotEditException when the text is not YAML, or not one mapping
     */
    public static Map<String, String> flatten(String text) {
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        List<Object> documents = new ArrayList<>();
        try {
            for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(body)) {
                if (document != null) {
                    documents.add(document);
                }
            }
        } catch (RuntimeException e) {
            throw new CannotEditException("the file is not valid YAML");
        }
        if (documents.size() > 1) {
            throw new CannotEditException("the file has several YAML documents");
        }
        Map<String, String> flat = new LinkedHashMap<>();
        if (documents.isEmpty()) {
            return flat;
        }
        if (!(documents.getFirst() instanceof Map<?, ?> root)) {
            throw new CannotEditException("the file is not a mapping of keys");
        }
        flatten("", root, flat);
        return flat;
    }

    private static void flatten(String prefix, Object value, Map<String, String> into) {
        switch (value) {
            case Map<?, ?> map -> map.forEach((key, inner) ->
                    flatten(prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key, inner, into));
            case List<?> list -> {
                for (int i = 0; i < list.size(); i++) {
                    flatten(prefix + "[" + i + "]", list.get(i), into);
                }
            }
            case null -> into.put(prefix, "");
            default -> into.put(prefix, String.valueOf(value));
        }
    }

    /** The value of {@code key} in {@code flat}, matching keys as Spring does; null when absent. */
    public static String get(Map<String, String> flat, String key) {
        String wanted = uniform(key);
        String found = null;
        for (Map.Entry<String, String> entry : flat.entrySet()) {
            if (uniform(entry.getKey()).equals(wanted)) {
                found = entry.getValue();
            }
        }
        return found;
    }

    /** {@code key} as Spring compares names: lower case, without {@code -} and {@code _}. */
    static String uniform(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /**
     * The value written on the commented template line for {@code key} (comment and quotes removed),
     * or null when there is no such line: {@code ${IDM_TEST_SAP_DB_PASSWORD}} for the template's
     * {@code idm.test.databases.sap.password}.
     */
    public static String commentedValue(String text, String key) {
        Doc doc = Doc.parse(text);
        Structure virtual = Structure.of(doc, true);
        Integer line = virtual.lines.get(uniform(key));
        if (line == null || !virtual.commented(line)) {
            return null;
        }
        Matcher matcher = KEY_LINE.matcher(virtual.texts.get(line));
        if (!matcher.matches()) {
            return null;
        }
        String value = Rest.parse(matcher.group(3)).value;
        if (value.length() >= 2 && (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    // --- editing --------------------------------------------------------------------------------

    /**
     * {@code text} with {@code key} set to {@code value}, everything else as it was.
     *
     * @throws CannotEditException      when that cannot be done in place
     * @throws IllegalArgumentException when the value has a line break or another control character
     */
    public static String edit(String text, String key, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7f) {
                throw new IllegalArgumentException("a setting's value cannot contain a line break or control character");
            }
        }
        String[] segments = key.split("\\.", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("not a dotted key");
            }
        }
        Map<String, String> before = flatten(text);
        String scalar = scalar(value);
        Doc doc = Doc.parse(text);
        List<String> attempts = new ArrayList<>();
        try {
            String preferred = edit(doc, segments, scalar, true);
            if (preferred != null) {
                attempts.add(preferred);
            }
        } catch (CannotEditException e) {
            // the template's lines do not fit; adding the key may still
        }
        String created = edit(doc, segments, scalar, false);
        if (created != null) {
            attempts.add(created);
        }
        for (String attempt : attempts) {
            if (keepsTheRest(before, attempt, key, value)) {
                return attempt;
            }
        }
        throw new CannotEditException("'" + key + "' cannot be set in place in this file");
    }

    private static String edit(Doc doc, String[] segments, String scalar, boolean useTemplate) {
        Structure active = Structure.of(doc, false);
        Integer exact = active.lines.get(uniform(String.join(".", segments)));
        if (exact != null) {
            return replaceValue(doc, active, exact, scalar);
        }
        int activeDepth = deepest(active, segments);
        if (useTemplate) {
            Structure virtual = Structure.of(doc, true);
            for (int depth = segments.length; depth > activeDepth; depth--) {
                Integer line = virtual.lines.get(path(segments, depth));
                if (line == null) {
                    continue;
                }
                List<Integer> chain = virtual.chains.get(line);
                boolean collides = chain.stream().anyMatch(i -> virtual.commented(i)
                        && active.lines.containsKey(virtual.paths.get(i)));
                if (collides) {
                    continue;
                }
                Doc uncommented = doc.copy();
                for (int i : chain) {
                    if (virtual.commented(i)) {
                        uncommented.texts.set(i, virtual.texts.get(i));
                    }
                }
                return edit(uncommented, segments, scalar, false);
            }
            return null;
        }
        return create(doc, active, segments, activeDepth, scalar);
    }

    /** How many leading segments of the path exist in {@code structure}. */
    private static int deepest(Structure structure, String[] segments) {
        for (int depth = segments.length; depth > 0; depth--) {
            if (structure.lines.containsKey(path(segments, depth))) {
                return depth;
            }
        }
        return 0;
    }

    private static String path(String[] segments, int depth) {
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (i > 0) {
                path.append('.');
            }
            path.append(uniform(segments[i]));
        }
        return path.toString();
    }

    private static String replaceValue(Doc doc, Structure active, int line, String scalar) {
        String text = doc.texts.get(line);
        Matcher matcher = KEY_LINE.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        Rest rest = Rest.parse(matcher.group(3));
        if (rest.value.isEmpty() && active.hasChildren(line)) {
            throw new CannotEditException("'" + active.paths.get(line) + "' holds other keys, not a value");
        }
        if (rest.value.startsWith("|") || rest.value.startsWith(">")) {
            throw new CannotEditException("'" + active.paths.get(line) + "' is a multi-line value");
        }
        String newRest;
        if (rest.value.isEmpty()) {
            newRest = " " + scalar + (rest.comment == null ? "" : " " + rest.comment);
        } else {
            newRest = rest.leading + scalar + (rest.comment == null ? "" : rest.between + rest.comment);
        }
        Doc result = doc.copy();
        result.texts.set(line, text.substring(0, matcher.start(3)) + newRest);
        return result.join();
    }

    private static String create(Doc doc, Structure active, String[] segments, int depth, String scalar) {
        int insertAt;
        int childIndent;
        if (depth == 0) {
            insertAt = doc.texts.size();
            childIndent = 0;
        } else {
            int parent = active.lines.get(path(segments, depth));
            Matcher matcher = KEY_LINE.matcher(doc.texts.get(parent));
            if (!matcher.matches() || !Rest.parse(matcher.group(3)).value.isEmpty()) {
                throw new CannotEditException("'" + active.paths.get(parent) + "' has a value, not other keys");
            }
            int parentIndent = active.indents.get(parent);
            insertAt = parent + 1;
            Integer firstChild = null;
            for (int i = parent + 1; i < doc.texts.size(); i++) {
                Kind kind = active.kinds.get(i);
                if (kind == Kind.BLANK || kind == Kind.COMMENT) {
                    continue;
                }
                if (kind == Kind.DOC || active.indents.get(i) <= parentIndent) {
                    break;
                }
                if (firstChild == null && kind == Kind.KEY) {
                    firstChild = i;
                }
                insertAt = i + 1;
            }
            childIndent = firstChild != null ? active.indents.get(firstChild) : parentIndent + active.unit();
        }
        int unit = active.unit();
        List<String> added = new ArrayList<>();
        for (int i = depth; i < segments.length; i++) {
            String indent = " ".repeat(childIndent + (i - depth) * unit);
            added.add(indent + segments[i] + ":" + (i == segments.length - 1 ? " " + scalar : ""));
        }
        Doc result = doc.copy();
        result.insert(insertAt, added);
        return result.join();
    }

    /** Whether {@code edited} holds {@code value} at {@code key} and every other setting of {@code before}. */
    private static boolean keepsTheRest(Map<String, String> before, String edited, String key, String value) {
        Map<String, String> after;
        try {
            after = flatten(edited);
        } catch (CannotEditException e) {
            return false;
        }
        Map<String, String> expected = new HashMap<>();
        before.forEach((k, v) -> expected.put(uniform(k), v));
        expected.put(uniform(key), value);
        Map<String, String> actual = new HashMap<>();
        after.forEach((k, v) -> actual.put(uniform(k), v));
        return actual.equals(expected);
    }

    /** {@code value} as a YAML scalar that reads back as exactly that text: plain when safe, else single-quoted. */
    static String scalar(String value) {
        if (!value.isEmpty() && value.equals(value.strip()) && readsBack(value)) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static boolean readsBack(String value) {
        try {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load("k: " + value);
            return loaded instanceof Map<?, ?> map && map.size() == 1 && map.get("k") != null
                    && !(map.get("k") instanceof Map<?, ?>) && !(map.get("k") instanceof List<?>)
                    && value.equals(String.valueOf(map.get("k")));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // --- the text -------------------------------------------------------------------------------

    private enum Kind { BLANK, COMMENT, DOC, LIST, KEY, OTHER }

    /** A file's lines, each with its own ending, and its byte order mark. */
    private static final class Doc {
        final boolean bom;
        final List<String> texts;
        final List<String> endings;
        final String eol;

        private Doc(boolean bom, List<String> texts, List<String> endings, String eol) {
            this.bom = bom;
            this.texts = texts;
            this.endings = endings;
            this.eol = eol;
        }

        static Doc parse(String text) {
            boolean bom = text.startsWith("﻿");
            String body = bom ? text.substring(1) : text;
            List<String> texts = new ArrayList<>();
            List<String> endings = new ArrayList<>();
            int start = 0;
            String eol = null;
            while (start < body.length()) {
                int nl = body.indexOf('\n', start);
                if (nl < 0) {
                    texts.add(body.substring(start));
                    endings.add("");
                    break;
                }
                boolean crlf = nl > start && body.charAt(nl - 1) == '\r';
                String ending = crlf ? "\r\n" : "\n";
                if (eol == null) {
                    eol = ending;
                }
                texts.add(body.substring(start, crlf ? nl - 1 : nl));
                endings.add(ending);
                start = nl + 1;
            }
            return new Doc(bom, texts, endings, eol == null ? "\n" : eol);
        }

        Doc copy() {
            return new Doc(bom, new ArrayList<>(texts), new ArrayList<>(endings), eol);
        }

        void insert(int at, List<String> lines) {
            if (at > 0 && endings.get(at - 1).isEmpty()) {
                endings.set(at - 1, eol);
            }
            for (int i = 0; i < lines.size(); i++) {
                texts.add(at + i, lines.get(i));
                endings.add(at + i, eol);
            }
        }

        String join() {
            StringBuilder out = new StringBuilder(bom ? "﻿" : "");
            for (int i = 0; i < texts.size(); i++) {
                out.append(texts.get(i)).append(endings.get(i));
            }
            return out.toString();
        }
    }

    /** What follows a key's colon: the value, the comment after it, and the spaces around them. */
    private record Rest(String leading, String value, String between, String comment) {

        static Rest parse(String rest) {
            int i = 0;
            while (i < rest.length() && (rest.charAt(i) == ' ' || rest.charAt(i) == '\t')) {
                i++;
            }
            String leading = rest.substring(0, i);
            if (i < rest.length() && rest.charAt(i) == '#') {
                return new Rest(leading, "", "", rest.substring(i));
            }
            int end = i;
            if (i < rest.length() && (rest.charAt(i) == '"' || rest.charAt(i) == '\'')) {
                char quote = rest.charAt(i);
                int j = i + 1;
                while (j < rest.length()) {
                    char c = rest.charAt(j);
                    if (quote == '"' && c == '\\') {
                        j += 2;
                        continue;
                    }
                    if (c == quote) {
                        if (quote == '\'' && j + 1 < rest.length() && rest.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                end = Math.min(j + 1, rest.length());
            }
            int hash = -1;
            for (int j = end; j < rest.length(); j++) {
                if (rest.charAt(j) == '#' && j > 0 && (rest.charAt(j - 1) == ' ' || rest.charAt(j - 1) == '\t')) {
                    hash = j;
                    break;
                }
            }
            if (hash < 0) {
                return new Rest(leading, rest.substring(i).stripTrailing(), "", null);
            }
            String value = rest.substring(i, hash);
            String trimmed = value.stripTrailing();
            return new Rest(leading, trimmed, value.substring(trimmed.length()), rest.substring(hash));
        }
    }

    /**
     * The keys of a text by line: each key line's dotted path (in Spring's uniform form), indentation
     * and the lines of its parents. With {@code virtual}, commented lines that read as a key line once
     * the {@code #} is removed count as key lines too — the template's structure.
     */
    private static final class Structure {
        final Map<String, Integer> lines = new HashMap<>();
        final Map<Integer, String> paths = new HashMap<>();
        final Map<Integer, List<Integer>> chains = new HashMap<>();
        final List<Kind> kinds = new ArrayList<>();
        final List<Integer> indents = new ArrayList<>();
        /** The text of each line as this structure reads it: commented key lines uncommented. */
        final List<String> texts = new ArrayList<>();
        private final List<Boolean> commented = new ArrayList<>();
        private final Map<Integer, Integer> parents = new HashMap<>();

        private record Entry(int indent, String segment, int line) {
        }

        static Structure of(Doc doc, boolean virtual) {
            Structure s = new Structure();
            Deque<Entry> stack = new ArrayDeque<>();
            for (int i = 0; i < doc.texts.size(); i++) {
                String text = doc.texts.get(i);
                String read = text;
                boolean fromComment = false;
                Kind kind = kind(text);
                if (kind == Kind.COMMENT && virtual) {
                    Matcher comment = COMMENT_LINE.matcher(text);
                    if (comment.matches() && !comment.group(2).stripLeading().startsWith("#")) {
                        String candidate = comment.group(1) + comment.group(2);
                        Matcher key = KEY_LINE.matcher(candidate);
                        if (key.matches() && SIMPLE_KEY.matcher(key.group(2)).matches()) {
                            read = candidate;
                            kind = Kind.KEY;
                            fromComment = true;
                        }
                    }
                }
                int indent = indent(read);
                s.kinds.add(kind);
                s.indents.add(indent);
                s.texts.add(read);
                s.commented.add(fromComment);
                switch (kind) {
                    case DOC -> stack.clear();
                    case LIST -> {
                        while (!stack.isEmpty() && (stack.peek().indent > indent
                                || stack.peek().segment.equals(LIST_SEGMENT) && stack.peek().indent >= indent)) {
                            stack.pop();
                        }
                        stack.push(new Entry(indent, LIST_SEGMENT, i));
                    }
                    case KEY -> {
                        while (!stack.isEmpty() && stack.peek().indent >= indent) {
                            stack.pop();
                        }
                        Matcher key = KEY_LINE.matcher(read);
                        key.matches();
                        String segment = uniform(unquote(key.group(2).strip()));
                        List<Integer> chain = new ArrayList<>();
                        StringBuilder path = new StringBuilder();
                        List<Entry> ancestors = new ArrayList<>(stack);
                        java.util.Collections.reverse(ancestors);
                        for (Entry ancestor : ancestors) {
                            path.append(ancestor.segment).append('.');
                            chain.add(ancestor.line);
                        }
                        path.append(segment);
                        chain.add(i);
                        if (!stack.isEmpty()) {
                            s.parents.put(i, stack.peek().line);
                        }
                        s.paths.put(i, path.toString());
                        s.chains.put(i, chain);
                        s.lines.putIfAbsent(path.toString(), i);
                        stack.push(new Entry(indent, segment, i));
                    }
                    default -> {
                        // blank lines, comments and continuation lines do not change the structure
                    }
                }
            }
            return s;
        }

        boolean commented(int line) {
            return commented.get(line);
        }

        boolean hasChildren(int line) {
            return parents.containsValue(line);
        }

        /** The file's indentation step: the smallest step from a key to its child key; 2 when none. */
        int unit() {
            int unit = Integer.MAX_VALUE;
            for (Map.Entry<Integer, Integer> entry : parents.entrySet()) {
                int step = indents.get(entry.getKey()) - indents.get(entry.getValue());
                if (step > 0 && kinds.get(entry.getValue()) == Kind.KEY) {
                    unit = Math.min(unit, step);
                }
            }
            return unit == Integer.MAX_VALUE ? 2 : unit;
        }

        private static Kind kind(String text) {
            String trimmed = text.strip();
            if (trimmed.isEmpty()) {
                return Kind.BLANK;
            }
            if (trimmed.startsWith("#")) {
                return Kind.COMMENT;
            }
            if (text.startsWith("---") || text.startsWith("...")) {
                return Kind.DOC;
            }
            if (trimmed.equals("-") || trimmed.startsWith("- ")) {
                return Kind.LIST;
            }
            return KEY_LINE.matcher(text).matches() ? Kind.KEY : Kind.OTHER;
        }

        private static int indent(String text) {
            int i = 0;
            while (i < text.length() && text.charAt(i) == ' ') {
                i++;
            }
            return i;
        }

        private static String unquote(String key) {
            if (key.length() >= 2 && (key.startsWith("\"") && key.endsWith("\"")
                    || key.startsWith("'") && key.endsWith("'"))) {
                return key.substring(1, key.length() - 1);
            }
            return key;
        }
    }
}
