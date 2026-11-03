package com.idm.test.engine.spi;

import com.idm.test.engine.result.Secrets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One delimited file as a {@code file.*} step wrote, read or deleted it. Names the folder by its
 * settings name only, never its path: reports are shared beyond the machine that ran them.
 * <p>
 * The constructor masks values in secret-named columns, cuts long values and keeps at most
 * {@link #MAX_FILE_ROWS} rows within {@link #MAX_FILE_CHARS} characters of values. Rows a
 * {@code file.verify} selected are kept whatever the row and character budget (up to
 * {@link #MAX_FILE_ROWS} of them), because they are the ones its checks were made on. Applying the constructor again to its
 * own output changes nothing.
 *
 * @param folder       the folder's settings name
 * @param file         the file name; for a read that found no file, the pattern it looked for
 * @param operation    {@code write}, {@code read} or {@code delete}
 * @param present      whether the file was there: false for a read that found no file yet and for a
 *                     delete of an absent file; null when the step never reached the folder
 * @param size         the file's size in bytes, or null
 * @param header       the column names in file order, or an empty list
 * @param rows         the rows kept, in file order
 * @param totalRows    how many data rows the file had, or null
 * @param selectedRows how many rows {@code where:} selected (read only), or null
 */
public record FileEvidence(String folder, String file, String operation, Boolean present, Long size,
                           List<String> header, List<Row> rows, Integer totalRows, Integer selectedRows)
        implements Evidence {

    /** Rows kept of one file. */
    public static final int MAX_FILE_ROWS = 50;

    /** Characters of values kept across the rows of one file, selected rows aside. */
    public static final int MAX_FILE_CHARS = 65_536;

    public static final String WRITE = "write";
    public static final String READ = "read";
    public static final String DELETE = "delete";

    /**
     * One data row.
     *
     * @param number   the row's position among the data rows, from 1 (the header is not counted)
     * @param selected whether {@code where:} selected it
     * @param values   column name to value, in header order
     */
    public record Row(int number, boolean selected, Map<String, Object> values) {

        public Row {
            Map<String, Object> masked = new LinkedHashMap<>();
            if (values != null) {
                values.forEach((column, value) -> masked.put(column,
                        Secrets.isSecret(column) ? Secrets.MASK : EvidenceMasking.value(value)));
            }
            values = Collections.unmodifiableMap(masked);
        }

        private int chars() {
            int chars = 0;
            for (Object value : values.values()) {
                chars += value == null ? 0 : String.valueOf(value).length();
            }
            return chars;
        }
    }

    public FileEvidence {
        header = header == null ? List.of()
                : header.stream().map(column -> EvidenceMasking.truncate(String.valueOf(column), MAX_VALUE_CHARS))
                        .toList();
        rows = kept(rows);
    }

    private static List<Row> kept(List<Row> rows) {
        if (rows == null) {
            return List.of();
        }
        List<Row> kept = new ArrayList<>();
        int chars = 0;
        int selected = 0;
        for (Row raw : rows) {
            if (raw == null) {
                continue;
            }
            Row row = new Row(raw.number(), raw.selected(), raw.values());
            if (row.selected()) {
                if (selected < MAX_FILE_ROWS) {
                    kept.add(row);
                    selected++;
                }
                continue;
            }
            int rowChars = row.chars();
            // An unselected row fills the room the selected ones before it left. A row dropped here
            // changes no state, so a second pass over the kept rows decides each one the same way.
            if (kept.size() < MAX_FILE_ROWS && chars + rowChars <= MAX_FILE_CHARS) {
                kept.add(row);
                chars += rowChars;
            }
        }
        return Collections.unmodifiableList(kept);
    }

    /** {@code file.write} wrote {@code rows} under {@code header}. */
    public static FileEvidence written(String folder, String file, long size, List<String> header,
                                       List<List<String>> rows) {
        return new FileEvidence(folder, file, WRITE, true, size, header, rowsOf(header, rows, Set.of()),
                rows == null ? 0 : rows.size(), null);
    }

    /**
     * {@code file.verify} read the file, and {@code where:} selected the rows at {@code selected}
     * (positions in {@code rows}, from 0).
     */
    public static FileEvidence read(String folder, String file, long size, List<String> header,
                                    List<List<String>> rows, Set<Integer> selected) {
        return new FileEvidence(folder, file, READ, true, size, header, rowsOf(header, rows, selected),
                rows == null ? 0 : rows.size(), selected.size());
    }

    /** {@code file.verify} found no file matching {@code pattern} yet. */
    public static FileEvidence notFound(String folder, String pattern) {
        return new FileEvidence(folder, pattern, READ, false, null, List.of(), List.of(), null, null);
    }

    /** {@code file.delete} deleted the file, or found it already absent. */
    public static FileEvidence deleted(String folder, String file, boolean existed) {
        return new FileEvidence(folder, file, DELETE, existed, null, List.of(), List.of(), null, null);
    }

    /** The step reached the folder, or tried to, and failed: nothing to show but where. */
    public static FileEvidence failed(String folder, String file, String operation) {
        return new FileEvidence(folder, file, operation, null, null, List.of(), List.of(), null, null);
    }

    private static List<Row> rowsOf(List<String> header, List<List<String>> rows, Set<Integer> selected) {
        if (rows == null || header == null) {
            return List.of();
        }
        List<Row> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            Map<String, Object> values = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                values.put(header.get(c), row != null && c < row.size() ? row.get(c) : null);
            }
            result.add(new Row(i + 1, selected.contains(i), values));
        }
        return result;
    }
}
