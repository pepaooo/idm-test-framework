package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.ValueComparison;
import com.idm.test.connector.file.client.DelimitedFileConnector;
import com.idm.test.connector.file.client.FileLookup;
import com.idm.test.connector.file.client.ReadFile;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.FileEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepContext;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

/**
 * Asserts on the rows of a delimited file in a named folder, such as one a Delimited Text driver
 * wrote.
 * <pre>
 * - file.verify:
 *     folder: hr-out
 *     file: "hr-out-*.csv"              # an exact name or a glob
 *     where: { employeeId: E123 }       # optional; selects the rows to assert on
 *     expect:
 *       rowCount: 1
 *       row: { status: provisioned }
 *     wait: { timeout: 2m, poll: 5s }
 * </pre>
 * Reads the newest matching file modified since the scenario started (less the folder's clock skew),
 * so a file left by an earlier run cannot make it pass. {@code where:} and {@code expect} compare
 * values as {@code db.verify} does, and match column names ignoring case. {@code expect} is
 * {@link RowExpectations}, shared with {@code db.verify}.
 * <p>
 * Retryable: no file yet, a file that does not parse yet (the driver may still be writing it) and
 * rows that differ are ordinary failures a {@code wait} retries. An unknown or unreadable folder, a
 * malformed {@code expect} or {@code where}, and a {@code where} column the file does not have are
 * final. Captures {@code { name, rows }}: the file read and the selected rows as maps.
 */
public final class FileVerifyStep implements StepType {

    private static final String EXPECT = "expect";
    private static final String WHERE = "where";

    private static final RowExpectations.Keys KEYS = new RowExpectations.Keys(
            "step.file.verify.expectNotMap",
            "step.file.verify.expectEmpty",
            "step.file.verify.unknownKeys",
            "step.file.verify.rowShape",
            "step.file.verify.nullExpected",
            "step.file.verify.rowCountMismatch",
            "step.file.verify.rowNeedsOne",
            "step.file.verify.columnMismatch");

    private final DelimitedFileConnector connector;

    public FileVerifyStep(DelimitedFileConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "file.verify";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of(FileSteps.FOLDER, FileSteps.FILE, EXPECT);
    }

    @Override
    public Set<String> acceptedParams() {
        Set<String> accepted = new LinkedHashSet<>(List.of(FileSteps.FOLDER, FileSteps.FILE, EXPECT, WHERE, "wait"));
        accepted.addAll(FileSteps.READ_FORMAT);
        return accepted;
    }

    @Override
    public Optional<Message> literalProblem(Map<String, Object> params) {
        return FileSteps.literalFileProblem(params, true);
    }

    /**
     * Outside the engine there is no scenario start, so only files from this moment on count. The
     * engine always calls {@link #execute(Map, boolean, StepContext)}.
     */
    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        return execute(params, captureRequested, new StepContext(Instant.now()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested, StepContext context) {
        String folder = String.valueOf(params.get(FileSteps.FOLDER));
        String pattern = String.valueOf(params.get(FileSteps.FILE));
        Object rawExpect = params.get(EXPECT);

        StepOutcome rejection = RowExpectations.rejectMalformed(rawExpect, KEYS, folder);
        if (rejection != null) {
            return rejection;
        }
        Map<String, Object> expect = (Map<String, Object>) rawExpect;

        Object rawWhere = params.get(WHERE);
        Map<String, Object> where = Map.of();
        if (rawWhere != null) {
            if (!(rawWhere instanceof Map<?, ?> map) || map.isEmpty()) {
                return StepOutcome.finalFailure(Message.of("step.file.verify.whereShape", folder));
            }
            for (Map.Entry<?, ?> column : map.entrySet()) {
                // As in expect's row: a misspelled column parses as a null value.
                if (column.getValue() == null) {
                    return StepOutcome.finalFailure(Message.of("step.file.verify.whereNull", folder, column.getKey()));
                }
            }
            where = (Map<String, Object>) map;
        }

        FileSteps.Overrides overrides = FileSteps.overrides(params);
        if (overrides.rejection() != null) {
            return overrides.rejection();
        }

        OperationResult<FileLookup> result =
                connector.readNewest(folder, pattern, context.scenarioStartedAt(), overrides.format());
        if (!result.isSuccess()) {
            return FileSteps.failure(result, "step.file.verify.failed", folder,
                    FileEvidence.failed(folder, pattern, FileEvidence.READ));
        }
        FileLookup lookup = result.getDataOrThrow();
        if (lookup.file().isEmpty()) {
            return StepOutcome.failure(Message.of("step.file.verify.noFile", folder, pattern, lookup.olderMatches()))
                    .withEvidence(FileEvidence.notFound(folder, pattern));
        }
        ReadFile file = lookup.file().get();

        Map<String, Integer> whereColumns = new LinkedHashMap<>();
        for (String column : where.keySet()) {
            OptionalInt index = file.columnIndex(column);
            if (index.isEmpty()) {
                return StepOutcome.finalFailure(Message.of("step.file.verify.unknownWhereColumn", folder,
                        file.name(), column, file.header()));
            }
            whereColumns.put(column, index.getAsInt());
        }

        Set<Integer> selected = new LinkedHashSet<>();
        for (int i = 0; i < file.rows().size(); i++) {
            List<String> row = file.rows().get(i);
            boolean matches = true;
            for (Map.Entry<String, Integer> column : whereColumns.entrySet()) {
                if (!ValueComparison.equal(where.get(column.getKey()), row.get(column.getValue()))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                selected.add(i);
            }
        }

        List<Map<String, Object>> selectedRows = new ArrayList<>(selected.size());
        for (int i : selected) {
            Map<String, Object> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            List<String> row = file.rows().get(i);
            for (int c = 0; c < file.header().size(); c++) {
                byName.put(file.header().get(c), row.get(c));
            }
            selectedRows.add(byName);
        }

        FileEvidence evidence = FileEvidence.read(folder, file.name(), file.size(), file.header(), file.rows(),
                selected);
        RowExpectations.Comparison comparison = RowExpectations.compare(selectedRows, expect, KEYS, file.name());
        if (comparison.mismatch() != null) {
            return StepOutcome.failure(comparison.mismatch()).withEvidence(evidence).withChecks(comparison.checks());
        }

        Message message = Message.of("step.file.verify.verified", selected.size(), file.name(), folder);
        if (!captureRequested) {
            return StepOutcome.success(message).withEvidence(evidence).withChecks(comparison.checks());
        }
        List<Map<String, Object>> rows = new ArrayList<>(selected.size());
        List<Map<String, String>> all = file.rowMaps();
        for (int i : selected) {
            rows.add(new LinkedHashMap<>(all.get(i)));
        }
        Map<String, Object> captured = new LinkedHashMap<>();
        captured.put("name", file.name());
        captured.put("rows", rows);
        return StepOutcome.success(message, captured).withEvidence(evidence).withChecks(comparison.checks());
    }
}
