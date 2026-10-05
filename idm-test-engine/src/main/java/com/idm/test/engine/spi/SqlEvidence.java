package com.idm.test.engine.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * One SQL statement as a step ran it. The constructor masks parameters bound to secret-named
 * columns and values in secret-named columns, keeps at most {@link #MAX_ROWS} rows and cuts long
 * values.
 *
 * @param rows        the rows returned (db.verify), or null
 * @param totalRows   how many rows the query returned before truncation, or null
 * @param updateCount the rows a db.execute statement changed, or null
 */
public record SqlEvidence(String database, String sql, List<Object> params, List<Map<String, Object>> rows,
                          Integer totalRows, Integer updateCount) implements Evidence {

    public SqlEvidence {
        params = EvidenceMasking.parameters(sql, params);
        rows = EvidenceMasking.rows(rows);
    }

    public static SqlEvidence query(String database, String sql, Object[] params, List<Map<String, Object>> rows) {
        return new SqlEvidence(database, sql, listOf(params), rows, rows.size(), null);
    }

    public static SqlEvidence update(String database, String sql, Object[] params, int count) {
        return new SqlEvidence(database, sql, listOf(params), null, null, count);
    }

    /** The statement reached the database, which answered with an error. */
    public static SqlEvidence failed(String database, String sql, Object[] params) {
        return new SqlEvidence(database, sql, listOf(params), null, null, null);
    }

    private static List<Object> listOf(Object[] params) {
        return params == null ? List.of() : new ArrayList<>(Arrays.asList(params));
    }
}
