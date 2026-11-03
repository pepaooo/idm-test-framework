package com.idm.test.connector.file.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * A delimited file {@link DelimitedFileConnector#readNewest} read: its header and its data rows, each
 * row exactly as long as the header.
 *
 * @param folder the folder's settings name (never its path)
 * @param name   the file name
 * @param header the column names, in file order; unique ignoring case
 * @param rows   the data rows, in file order
 * @param size   the file's size in bytes
 */
public record ReadFile(String folder, String name, List<String> header, List<List<String>> rows, long size) {

    public ReadFile {
        header = List.copyOf(header);
        List<List<String>> copied = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            copied.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        rows = Collections.unmodifiableList(copied);
    }

    /**
     * @return the position of {@code column} in the header, ignoring case (as {@code db.verify}
     * matches column names), or empty when the file has no such column
     */
    public OptionalInt columnIndex(String column) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).equalsIgnoreCase(column)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /** @return each row as a map from column name to value, in header order */
    public List<Map<String, String>> rowMaps() {
        List<Map<String, String>> maps = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                map.put(header.get(i), row.get(i));
            }
            maps.add(map);
        }
        return maps;
    }
}
