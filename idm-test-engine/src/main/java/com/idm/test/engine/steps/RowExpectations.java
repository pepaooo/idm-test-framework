package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.ValueComparison;
import com.idm.test.engine.spi.Check;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code expect: { rowCount, row }} assertion shared by {@code db.verify} and
 * {@code file.verify}: the checks that an {@code expect} asserts something real, made before any
 * query or read, and the comparison of the rows found against it.
 * <p>
 * {@code rowCount} and {@code row} are separate keys rather than one merged matcher, so a table or
 * file with a column named {@code rows} or {@code rowCount} cannot change how a scenario parses
 * (spec §7.3). Each step passes its own message keys, so its wording stays its own.
 */
final class RowExpectations {

    static final String ROW_COUNT = "rowCount";
    static final String ROW = "row";
    private static final Set<String> EXPECT_KEYS = Set.of(ROW_COUNT, ROW);
    /** The name of the check on how many rows were found. */
    private static final String ROW_COUNT_CHECK = "row count";

    /**
     * The message keys of one step. The first five take {@code (target, ...)} as {@code db.verify}'s
     * always have; the comparison keys take {@code (target, ...)} as well, where target is whatever
     * the step names in its messages (a database, a file).
     */
    record Keys(String expectNotMap, String expectEmpty, String unknownKeys, String rowShape, String nullExpected,
                String rowCountMismatch, String rowNeedsOne, String columnMismatch) {
    }

    /** The checks made and the first difference, if any. */
    record Comparison(List<Check> checks, Message mismatch) {
    }

    private RowExpectations() {
    }

    /**
     * An {@code expect} that asserts nothing, whose keys are typos, or whose {@code row} is empty or
     * not a map, would pass having compared nothing at all — the vacuous-assertion trap, caught
     * repeatedly in this project. Checked before the query or read, since none of these problems
     * depend on what is found.
     * <p>
     * Takes the raw, unvalidated value rather than an already-cast {@code Map}: a scenario can write
     * {@code expect} as a scalar, and {@code StepType.execute} must never throw, so the type check has
     * to happen here, before any caller casts it.
     *
     * @return a final failure, or null when {@code rawExpect} is usable
     */
    @SuppressWarnings("unchecked")
    static StepOutcome rejectMalformed(Object rawExpect, Keys keys, Object target) {
        if (rawExpect != null && !(rawExpect instanceof Map)) {
            return StepOutcome.finalFailure(Message.of(keys.expectNotMap(), target, ROW_COUNT, ROW));
        }

        Map<String, Object> expect = (Map<String, Object>) rawExpect;
        if (expect == null || expect.isEmpty()) {
            return StepOutcome.finalFailure(Message.of(keys.expectEmpty(), target, ROW_COUNT, ROW));
        }

        Set<String> unknown = new TreeSet<>(expect.keySet());
        unknown.removeAll(EXPECT_KEYS);
        if (!unknown.isEmpty()) {
            return StepOutcome.finalFailure(Message.of(keys.unknownKeys(), target, unknown,
                    new TreeSet<>(EXPECT_KEYS)));
        }

        // Positive rule: if ROW is present at all, it must be a Map with at least one
        // entry. This is one condition rather than a "not a map" check plus an "empty map"
        // check so that a present-but-null value (expect: { row: null }) cannot slip past
        // both — containsKey agrees with compare() about what "present" means.
        if (expect.containsKey(ROW)
                && !(expect.get(ROW) instanceof Map<?, ?> rowMap && !rowMap.isEmpty())) {
            return StepOutcome.finalFailure(Message.of(keys.rowShape(), target, ROW));
        }

        // The hole one level in: a misspelled column name (`staus:` instead of `status:`)
        // parses as an entry whose value is null, indistinguishable from an explicit
        // `staus: null` after Map.get() — and ValueComparison.equal(null, null) is true, so
        // a typo'd column would report "verified" having compared nothing.
        if (expect.containsKey(ROW)) {
            for (Map.Entry<?, ?> column : ((Map<?, ?>) expect.get(ROW)).entrySet()) {
                if (column.getValue() == null) {
                    return StepOutcome.finalFailure(Message.of(keys.nullExpected(), target, column.getKey()));
                }
            }
        }

        return null;
    }

    /**
     * Compares the rows found with an {@code expect} that {@link #rejectMalformed} accepted. Every
     * expectation is compared, so a finding shows each one; the mismatch names the first that differs.
     * Checks are named "row count" and by each expected column, as written.
     *
     * @param rows rows whose {@code get(column)} finds a column the way the step matches names (both
     *             steps ignore case)
     */
    @SuppressWarnings("unchecked")
    static Comparison compare(List<Map<String, Object>> rows, Map<String, Object> expect, Keys keys, Object target) {
        List<Check> checks = new ArrayList<>();
        Message mismatch = null;

        if (expect.containsKey(ROW_COUNT)) {
            Object expectedCount = expect.get(ROW_COUNT);
            boolean matched = ValueComparison.equal(expectedCount, rows.size());
            checks.add(Check.compared(ROW_COUNT_CHECK, expectedCount, rows.size(), matched));
            if (!matched) {
                mismatch = Message.of(keys.rowCountMismatch(), target, expectedCount, rows.size());
            }
        }

        if (expect.containsKey(ROW)) {
            if (rows.size() != 1) {
                // Matching "the first row" of several would depend on their order —
                // intermittently green, which is worse than red.
                checks.add(Check.compared(ROW_COUNT_CHECK, 1, rows.size(), false));
                if (mismatch == null) {
                    mismatch = Message.of(keys.rowNeedsOne(), target, rows.size());
                }
            } else {
                Map<String, Object> actual = rows.get(0);
                Map<String, Object> expectedRow = (Map<String, Object>) expect.get(ROW);

                for (Map.Entry<String, Object> column : expectedRow.entrySet()) {
                    Object actualValue = actual.get(column.getKey());
                    boolean matched = ValueComparison.equal(column.getValue(), actualValue);
                    checks.add(Check.compared(column.getKey(), column.getValue(), actualValue, matched));
                    if (!matched && mismatch == null) {
                        mismatch = Message.of(keys.columnMismatch(), target, column.getKey(),
                                column.getValue(), actualValue);
                    }
                }
            }
        }

        return new Comparison(checks, mismatch);
    }
}
