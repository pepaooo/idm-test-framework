package com.idm.test.connector.file.client;

import com.idm.test.connector.file.client.FileProblem.Kind;
import com.idm.test.core.model.OperationResult;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.PatternSyntaxException;

/**
 * Reads and writes delimited text files in named folders from the settings, for scenarios that test
 * the NetIQ Identity Manager Driver for Delimited Text.
 * <p>
 * Every operation names a folder by its settings name and a file by a plain name inside it: a scenario
 * never gives a path. A name containing a separator, {@code ..}, a drive or a root is refused, and so
 * is a symbolic link that resolves outside the folder. Reading never follows symbolic links at all.
 * Each folder declares its {@link FolderAccess}; an operation the folder does not allow is refused.
 * <p>
 * {@link #write} writes under {@code <name><temp-suffix>} and then renames the file with an atomic move,
 * so a driver polling the folder never sees a partial file. It never overwrites an existing file, and
 * never leaves its temporary file behind. {@link #readNewest} reads the newest matching file modified
 * since a given instant, ignoring temporary files.
 * <p>
 * Failures are returned, never thrown, as an {@link OperationResult} carrying a {@link FileProblem}.
 * No message names a path.
 */
public final class DelimitedFileConnector {

    /** Moves the finished temporary file to its final name. A seam for testing failures. */
    @FunctionalInterface
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    private static final String NEED_READ = "read";
    private static final String NEED_WRITE = "write";
    private static final char BOM = '﻿';
    private static final String GLOB_CHARACTERS = "*?[]{}";

    private final Map<String, FolderSettings> folders;
    private final Mover mover;

    /**
     * @param folders the named folders, by settings name
     */
    public DelimitedFileConnector(Map<String, FolderSettings> folders) {
        this(folders, (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE));
    }

    DelimitedFileConnector(Map<String, FolderSettings> folders, Mover mover) {
        this.folders = Collections.unmodifiableMap(new LinkedHashMap<>(folders == null ? Map.of() : folders));
        this.mover = mover;
    }

    /** @return the settings of folder {@code name}, if it is configured */
    public Optional<FolderSettings> folder(String name) {
        return Optional.ofNullable(folders.get(name));
    }

    /** @return the configured folder names, sorted */
    public Set<String> folderNames() {
        return Collections.unmodifiableSet(new TreeSet<>(folders.keySet()));
    }

    /**
     * Writes a delimited file: the header (when the format has one) and then the rows.
     *
     * @param folder    the folder's settings name; needs write access
     * @param file      a plain file name; must not exist yet
     * @param columns   the header, in order; at least one column
     * @param rows      the data rows, each exactly as long as {@code columns}; a null value writes an
     *                  empty field
     * @param overrides format values that replace the folder's, or {@code null}
     */
    public OperationResult<WrittenFile> write(String folder, String file, List<String> columns,
                                              List<List<String>> rows, CsvFormat overrides) {
        try {
            Folder target = open(folder, NEED_WRITE);
            Format format = target.format(overrides);
            checkName(target, file);
            if (file.endsWith(target.settings.tempSuffix())) {
                throw refusal(Kind.RESERVED_NAME, folder, file, target.settings.tempSuffix());
            }
            checkRows(folder, columns, rows);

            Path destination = target.real.resolve(file);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw refusal(Kind.ALREADY_EXISTS, folder, file);
            }

            String tempName = file + target.settings.tempSuffix();
            Path temp = target.real.resolve(tempName);
            writeTemporary(target, file, tempName, temp, format, columns, rows);
            try {
                mover.move(temp, destination);
            } catch (AtomicMoveNotSupportedException e) {
                deleteQuietly(temp);
                throw refusal(Kind.NO_ATOMIC_MOVE, folder);
            } catch (IOException | RuntimeException e) {
                deleteQuietly(temp);
                throw refusal(Kind.IO_FAILED, folder, file, reason(e, target.real));
            }

            long size = sizeOf(target, file, destination);
            return OperationResult.success(new WrittenFile(folder, file, rows.size(), size),
                    "Wrote " + rows.size() + " row(s) to '" + file + "' in folder '" + folder + "'");
        } catch (Refusal refusal) {
            return refusal.problem.toResult();
        }
    }

    /**
     * Reads the newest file in a folder whose name matches {@code pattern} and that was modified at or
     * after {@code notBefore} less the folder's clock skew. Temporary files (ending in the folder's
     * temporary suffix), directories and symbolic links are never candidates. Of two candidates with the
     * same time, the later name in lexical order is read, so the choice is the same on every attempt.
     *
     * @param folder    the folder's settings name; needs read access
     * @param pattern   an exact file name or a glob ({@code *}, {@code ?}, {@code [...]}, {@code {a,b}})
     *                  matching names inside the folder only
     * @param notBefore usually the scenario's start
     * @param overrides format values that replace the folder's, or {@code null}; the format must have a
     *                  header row
     * @return the file read, or an empty lookup when no file counts yet
     */
    public OperationResult<FileLookup> readNewest(String folder, String pattern, Instant notBefore,
                                                  CsvFormat overrides) {
        try {
            Folder source = open(folder, NEED_READ);
            Format format = source.format(overrides);
            if (!format.header) {
                throw refusal(Kind.NO_HEADER, folder);
            }
            PathMatcher matcher = matcher(folder, pattern);
            Instant threshold = notBefore.minus(source.settings.clockSkew());

            Path newest = null;
            FileTime newestTime = null;
            int older = 0;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(source.real)) {
                for (Path entry : entries) {
                    Path name = entry.getFileName();
                    if (!matcher.matches(name)
                            || name.toString().endsWith(source.settings.tempSuffix())
                            || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    FileTime modified;
                    try {
                        modified = Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS);
                    } catch (NoSuchFileException gone) {
                        continue; // moved away (by the driver, say) between listing and looking
                    }
                    if (modified.toInstant().isBefore(threshold)) {
                        older++;
                        continue;
                    }
                    if (newest == null || isLater(modified, name, newestTime, newest.getFileName())) {
                        newest = entry;
                        newestTime = modified;
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                throw refusal(Kind.IO_FAILED, folder, pattern, reason(e, source.real));
            }

            if (newest == null) {
                return OperationResult.success(new FileLookup(Optional.empty(), older),
                        "No file matching '" + pattern + "' in folder '" + folder + "' yet");
            }
            ReadFile read = read(source, newest, format);
            return OperationResult.success(new FileLookup(Optional.of(read), older),
                    "Read " + read.rows().size() + " row(s) from '" + read.name() + "' in folder '" + folder + "'");
        } catch (Refusal refusal) {
            return refusal.problem.toResult();
        }
    }

    /**
     * Deletes one file. A file that is already absent is a success ({@code false}): the driver usually
     * moves or deletes its input itself, so this is the normal case in cleanup.
     *
     * @param folder the folder's settings name; needs write access
     * @param file   a plain file name (no glob)
     * @return {@code true} when a file was deleted, {@code false} when it was already absent
     */
    public OperationResult<Boolean> delete(String folder, String file) {
        try {
            Folder target = open(folder, NEED_WRITE);
            checkName(target, file);
            Path path = target.real.resolve(file);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return OperationResult.success(false, "'" + file + "' in folder '" + folder + "' is already absent");
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw refusal(Kind.NOT_A_FILE, folder, file);
            }
            try {
                Files.delete(path);
            } catch (NoSuchFileException e) {
                return OperationResult.success(false, "'" + file + "' in folder '" + folder + "' is already absent");
            } catch (IOException e) {
                throw refusal(Kind.IO_FAILED, folder, file, reason(e, target.real));
            }
            return OperationResult.success(true, "Deleted '" + file + "' in folder '" + folder + "'");
        } catch (Refusal refusal) {
            return refusal.problem.toResult();
        }
    }

    /**
     * Checks that a folder exists and allows what it declares: a {@code read} folder can be listed, a
     * {@code write} folder accepts a new file (a probe file, created and deleted again). For the
     * workbench's connection test and the real-environment guards.
     */
    public OperationResult<Void> probe(String folder) {
        try {
            Folder target = open(folder, null);
            if (target.settings.access().canRead()) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(target.real)) {
                    entries.iterator().hasNext();
                } catch (IOException | UncheckedIOException e) {
                    throw refusal(Kind.NOT_READABLE, folder, reason(e, target.real));
                }
            }
            if (target.settings.access().canWrite()) {
                Path probe = target.real.resolve(".idmtest-probe-" + UUID.randomUUID() + target.settings.tempSuffix());
                try {
                    Files.newByteChannel(probe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close();
                } catch (IOException e) {
                    throw refusal(Kind.NOT_WRITABLE, folder, reason(e, target.real));
                } finally {
                    deleteQuietly(probe);
                }
            }
            return OperationResult.success(null, "Folder '" + folder + "' is "
                    + target.settings.access().settingsValue());
        } catch (Refusal refusal) {
            return refusal.problem.toResult();
        }
    }

    /**
     * Checks, without creating anything, that a folder is configured, exists and allows what a run
     * needs, both as declared and as the operating system reports it. For the real-environment guards,
     * which run before anything else and must not drop files into a folder a driver is watching.
     */
    public OperationResult<Void> inspect(String folder, boolean read, boolean write) {
        try {
            Folder target = open(folder, null);
            if (read) {
                open(folder, NEED_READ);
                if (!Files.isReadable(target.real)) {
                    throw refusal(Kind.NOT_READABLE, folder, "the operating system does not allow it");
                }
            }
            if (write) {
                open(folder, NEED_WRITE);
                if (!Files.isWritable(target.real)) {
                    throw refusal(Kind.NOT_WRITABLE, folder, "the operating system does not allow it");
                }
            }
            return OperationResult.success(null, "Folder '" + folder + "' is usable");
        } catch (Refusal refusal) {
            return refusal.problem.toResult();
        }
    }

    // ---------------------------------------------------------------- folders and names

    /** A configured folder, resolved to its real path on this attempt. */
    private record Folder(String name, FolderSettings settings, Path real) {

        Format format(CsvFormat overrides) throws Refusal {
            return Format.of(name, settings.format().overriddenBy(overrides));
        }
    }

    /**
     * @param needed {@link #NEED_READ}, {@link #NEED_WRITE}, or {@code null} for no access check
     */
    private Folder open(String folder, String needed) throws Refusal {
        FolderSettings settings = folders.get(folder);
        if (settings == null) {
            throw refusal(Kind.NOT_CONFIGURED, folder, folderNames());
        }
        if (needed != null) {
            boolean allowed = NEED_READ.equals(needed) ? settings.access().canRead() : settings.access().canWrite();
            if (!allowed) {
                throw refusal(Kind.ACCESS_DENIED, folder, settings.access().settingsValue(), needed);
            }
        }
        if (settings.path() == null || settings.path().isBlank()) {
            throw refusal(Kind.NO_PATH, folder);
        }
        Path real;
        try {
            real = Path.of(settings.path()).toRealPath();
        } catch (InvalidPathException e) {
            throw refusal(Kind.NO_PATH, folder);
        } catch (NoSuchFileException e) {
            throw refusal(Kind.FOLDER_MISSING, folder);
        } catch (IOException e) {
            throw refusal(Kind.NOT_READABLE, folder, reason(e, null));
        }
        if (!Files.isDirectory(real)) {
            throw refusal(Kind.NOT_A_DIRECTORY, folder);
        }
        return new Folder(folder, settings, real);
    }

    /**
     * A plain name: not empty, not {@code .} or {@code ..}, no separator, drive, control character or
     * glob character; and, when it is an existing symbolic link, one that resolves inside the folder.
     */
    private static void checkName(Folder folder, String file) throws Refusal {
        if (!isPlainName(file) || hasGlob(file)) {
            throw refusal(Kind.UNSAFE_NAME, folder.name, file);
        }
        Path path;
        try {
            path = folder.real.resolve(file);
        } catch (InvalidPathException e) {
            throw refusal(Kind.UNSAFE_NAME, folder.name, file);
        }
        if (!path.getParent().equals(folder.real)) {
            throw refusal(Kind.UNSAFE_NAME, folder.name, file);
        }
        if (Files.isSymbolicLink(path)) {
            try {
                if (!path.toRealPath().startsWith(folder.real)) {
                    throw refusal(Kind.UNSAFE_NAME, folder.name, file);
                }
            } catch (IOException e) {
                // A dangling link: where it points is unknown, so it is not provably inside.
                throw refusal(Kind.UNSAFE_NAME, folder.name, file);
            }
        }
    }

    /**
     * @return true when {@code name} is a plain name inside a folder: not empty, not {@code .} or
     * {@code ..}, without a separator, drive, control character, or leading or trailing blank. Glob
     * characters are allowed here; see {@link #hasGlob}. The scenario validator uses this to reject a
     * literal file name before anything runs; the connector checks every name again at run time.
     */
    public static boolean isPlainName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c < ' ' || c == 0x7F) {
                return false;
            }
        }
        // Leading or trailing blanks are almost always a typo, and Windows drops trailing ones.
        return name.equals(name.strip());
    }

    /** @return true when {@code name} contains a glob character ({@code * ? [ ] { }}) */
    public static boolean hasGlob(String name) {
        return name != null && containsAny(name, GLOB_CHARACTERS);
    }

    private static boolean containsAny(String text, String characters) {
        for (int i = 0; i < characters.length(); i++) {
            if (text.indexOf(characters.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static PathMatcher matcher(String folder, String pattern) throws Refusal {
        if (!isPlainName(pattern)) {
            throw refusal(Kind.BAD_PATTERN, folder, String.valueOf(pattern),
                    "a pattern matches names inside the folder only");
        }
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (PatternSyntaxException e) {
            throw refusal(Kind.BAD_PATTERN, folder, pattern, e.getDescription());
        }
    }

    private static boolean isLater(FileTime time, Path name, FileTime otherTime, Path otherName) {
        int byTime = time.compareTo(otherTime);
        return byTime > 0 || (byTime == 0 && name.toString().compareTo(otherName.toString()) > 0);
    }

    // ---------------------------------------------------------------- formats

    /** A checked, usable format. */
    private record Format(char delimiter, char quote, Charset charset, LineEnding lineEnding, boolean header) {

        static Format of(String folder, CsvFormat format) throws Refusal {
            char delimiter = singleCharacter(folder, "delimiter", format.delimiter());
            char quote = singleCharacter(folder, "quote", format.quote());
            if (quote == delimiter) {
                throw refusal(Kind.BAD_FORMAT, folder, "quote", format.quote());
            }
            Charset charset;
            try {
                charset = Charset.forName(format.encoding());
            } catch (IllegalArgumentException e) { // an unknown, unsupported, illegal or missing name
                throw refusal(Kind.BAD_FORMAT, folder, "encoding", String.valueOf(format.encoding()));
            }
            return new Format(delimiter, quote, charset, format.lineEnding(), format.header());
        }

        private static char singleCharacter(String folder, String setting, String value) throws Refusal {
            if (value == null || value.length() != 1 || value.charAt(0) == '\r' || value.charAt(0) == '\n') {
                throw refusal(Kind.BAD_FORMAT, folder, setting, String.valueOf(value));
            }
            return value.charAt(0);
        }

        CSVFormat forWriting() {
            return CSVFormat.DEFAULT.builder()
                    .setDelimiter(delimiter)
                    .setQuote(quote)
                    .setRecordSeparator(lineEnding.separator())
                    .get();
        }

        CSVFormat forReading() {
            return CSVFormat.DEFAULT.builder()
                    .setDelimiter(delimiter)
                    .setQuote(quote)
                    .setIgnoreEmptyLines(true)
                    .get();
        }
    }

    // ---------------------------------------------------------------- writing

    private static void checkRows(String folder, List<String> columns, List<List<String>> rows) throws Refusal {
        if (columns == null || columns.isEmpty()) {
            throw refusal(Kind.BAD_ROWS, folder, "no columns");
        }
        Set<String> seen = new HashSet<>();
        for (String column : columns) {
            if (column == null || column.isEmpty()) {
                throw refusal(Kind.BAD_ROWS, folder, "a column name is empty");
            }
            if (!seen.add(column.toLowerCase(Locale.ROOT))) {
                throw refusal(Kind.BAD_ROWS, folder, "column '" + column + "' appears twice");
            }
        }
        if (rows == null) {
            throw refusal(Kind.BAD_ROWS, folder, "no rows");
        }
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row == null || row.size() != columns.size()) {
                throw refusal(Kind.BAD_ROWS, folder, "row " + (i + 1) + " has "
                        + (row == null ? 0 : row.size()) + " value(s) for " + columns.size() + " column(s)");
            }
        }
    }

    private static void writeTemporary(Folder target, String file, String tempName, Path temp, Format format,
                                       List<String> columns, List<List<String>> rows) throws Refusal {
        Writer writer;
        try {
            // CREATE_NEW: a leftover temporary file is not ours to overwrite or delete.
            writer = Files.newBufferedWriter(temp, format.charset,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw refusal(Kind.TEMP_EXISTS, target.name, tempName);
        } catch (IOException e) {
            throw refusal(Kind.IO_FAILED, target.name, file, reason(e, target.real));
        }
        try (CSVPrinter printer = new CSVPrinter(writer, format.forWriting())) {
            if (format.header) {
                printer.printRecord(columns);
            }
            for (List<String> row : rows) {
                List<String> values = new ArrayList<>(row.size());
                for (String value : row) {
                    values.add(value == null ? "" : value);
                }
                printer.printRecord(values);
            }
        } catch (CharacterCodingException e) {
            deleteQuietly(temp);
            throw refusal(Kind.UNENCODABLE, target.name, file, format.charset.name());
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw refusal(Kind.IO_FAILED, target.name, file, reason(e, target.real));
        }
    }

    private static long sizeOf(Folder folder, String file, Path path) throws Refusal {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw refusal(Kind.IO_FAILED, folder.name, file, reason(e, folder.real));
        }
    }

    // ---------------------------------------------------------------- reading

    private static ReadFile read(Folder folder, Path path, Format format) throws Refusal {
        String name = path.getFileName().toString();
        long size = sizeOf(folder, name, path);
        List<String> header = null;
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, format.charset)) {
            reader.mark(1);
            if (reader.read() != BOM) {
                reader.reset();
            }
            try (CSVParser parser = CSVParser.parse(reader, format.forReading())) {
                for (CSVRecord record : parser) {
                    List<String> values = record.toList();
                    if (header == null) {
                        header = checkHeader(folder, name, values);
                    } else if (values.size() != header.size()) {
                        throw refusal(Kind.PARSE_FAILED, folder.name, name, "record " + record.getRecordNumber()
                                + " has " + values.size() + " field(s); the header has " + header.size());
                    } else {
                        rows.add(values);
                    }
                }
            }
        } catch (CharacterCodingException e) {
            throw refusal(Kind.PARSE_FAILED, folder.name, name, "the file is not valid " + format.charset.name());
        } catch (UncheckedIOException e) {
            throw unreadable(folder, name, format, e.getCause());
        } catch (IOException e) {
            throw unreadable(folder, name, format, e);
        } catch (IllegalStateException e) {
            // Commons CSV reports some malformed input this way.
            throw refusal(Kind.PARSE_FAILED, folder.name, name, String.valueOf(e.getMessage()));
        }
        if (header == null) {
            throw refusal(Kind.PARSE_FAILED, folder.name, name, "the file is empty (no header row)");
        }
        return new ReadFile(folder.name, name, header, rows, size);
    }

    private static Refusal unreadable(Folder folder, String name, Format format, IOException e) {
        if (e instanceof CharacterCodingException) {
            return refusal(Kind.PARSE_FAILED, folder.name, name, "the file is not valid " + format.charset.name());
        }
        if (e instanceof FileSystemException) {
            return refusal(Kind.IO_FAILED, folder.name, name, reason(e, folder.real));
        }
        // Commons CSV's own syntax errors (unbalanced quotes, characters after a closing quote).
        return refusal(Kind.PARSE_FAILED, folder.name, name, reason(e, folder.real));
    }

    private static List<String> checkHeader(Folder folder, String name, List<String> header) throws Refusal {
        Set<String> seen = new HashSet<>();
        for (String column : header) {
            if (column.isBlank()) {
                throw refusal(Kind.PARSE_FAILED, folder.name, name, "the header has an empty column name");
            }
            if (!seen.add(column.toLowerCase(Locale.ROOT))) {
                throw refusal(Kind.PARSE_FAILED, folder.name, name, "column '" + column + "' appears twice in the header");
            }
        }
        return header;
    }

    // ---------------------------------------------------------------- failures

    /** Ends an operation with a problem. Internal control flow only, never seen by a caller. */
    private static final class Refusal extends Exception {

        private static final long serialVersionUID = 1L;

        private final transient FileProblem problem;

        Refusal(FileProblem problem) {
            super(null, null, false, false);
            this.problem = problem;
        }
    }

    private static Refusal refusal(Kind kind, Object... values) {
        return new Refusal(FileProblem.of(kind, values));
    }

    /**
     * A short reason without paths: a {@link FileSystemException} names its file, so only its kind and
     * reason are kept; any other message has the folder's path replaced.
     */
    private static String reason(Exception e, Path folder) {
        if (e instanceof UnmappableCharacterException) {
            return "a value cannot be encoded";
        }
        if (e instanceof FileSystemException fse) {
            String kind = fse.getClass().getSimpleName();
            return fse.getReason() == null ? kind : kind + ": " + fse.getReason();
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (folder != null) {
            message = message.replace(folder.toString(), "<folder>");
        }
        return message;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort: the original failure is the one to report.
        }
    }
}
