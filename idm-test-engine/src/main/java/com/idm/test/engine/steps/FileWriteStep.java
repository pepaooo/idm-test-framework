package com.idm.test.engine.steps;

import com.idm.test.connector.file.client.DelimitedFileConnector;
import com.idm.test.connector.file.client.WrittenFile;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.FileEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes a delimited file into a named folder, for a Delimited Text driver to pick up.
 * <pre>
 * - file.write:
 *     folder: hr-in
 *     file: "hr-${user.userId}.csv"
 *     columns: [employeeId, givenName, surname]   # optional; else the first row's keys, in order
 *     rows:
 *       - { employeeId: "${user.userId}", givenName: "${user.firstName}", surname: "${user.lastName}" }
 *     as: written                                 # { folder, name, rows, size }
 * </pre>
 * The file appears under its final name in one atomic rename, never half-written, and an existing
 * file is never overwritten. A row key that is not a column fails the step, so a typo is never
 * dropped silently; a column a row does not give is written empty, and so is a null. Numbers and
 * booleans are written as their YAML text. Registers nothing: remove the file with a
 * {@code file.delete} in {@code cleanup:}.
 */
public final class FileWriteStep implements StepType {

    private static final String ROWS = "rows";
    private static final String COLUMNS = "columns";

    private final DelimitedFileConnector connector;

    public FileWriteStep(DelimitedFileConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "file.write";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of(FileSteps.FOLDER, FileSteps.FILE, ROWS);
    }

    @Override
    public Set<String> acceptedParams() {
        return Set.of(FileSteps.FOLDER, FileSteps.FILE, ROWS, COLUMNS, FileSteps.DELIMITER, FileSteps.QUOTE,
                FileSteps.ENCODING, FileSteps.LINE_ENDING, FileSteps.HEADER);
    }

    @Override
    public Optional<Message> literalProblem(Map<String, Object> params) {
        return FileSteps.literalFileProblem(params, false);
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String folder = String.valueOf(params.get(FileSteps.FOLDER));
        String file = String.valueOf(params.get(FileSteps.FILE));

        FileSteps.Overrides overrides = FileSteps.overrides(params);
        if (overrides.rejection() != null) {
            return overrides.rejection();
        }

        if (!(params.get(ROWS) instanceof List<?> rawRows)) {
            return StepOutcome.finalFailure(Message.of("step.file.write.rowsNotList", folder));
        }
        List<Map<?, ?>> rows = new ArrayList<>(rawRows.size());
        for (int i = 0; i < rawRows.size(); i++) {
            if (!(rawRows.get(i) instanceof Map<?, ?> row)) {
                return StepOutcome.finalFailure(Message.of("step.file.write.rowNotMap", folder, i + 1));
            }
            rows.add(row);
        }

        List<String> columns;
        Object rawColumns = params.get(COLUMNS);
        if (rawColumns != null) {
            if (!(rawColumns instanceof List<?> list) || list.isEmpty()) {
                return StepOutcome.finalFailure(Message.of("step.file.write.columnsShape", folder));
            }
            columns = list.stream().map(String::valueOf).toList();
        } else if (rows.isEmpty()) {
            return StepOutcome.finalFailure(Message.of("step.file.write.noColumns", folder));
        } else {
            columns = rows.get(0).keySet().stream().map(String::valueOf).toList();
        }

        List<List<String>> values = new ArrayList<>(rows.size());
        Set<String> known = new LinkedHashSet<>(columns);
        for (int i = 0; i < rows.size(); i++) {
            Map<?, ?> row = rows.get(i);
            for (Object key : row.keySet()) {
                if (!known.contains(String.valueOf(key))) {
                    return StepOutcome.finalFailure(Message.of("step.file.write.unknownColumn", folder, i + 1,
                            key, columns));
                }
            }
            List<String> line = new ArrayList<>(columns.size());
            for (String column : columns) {
                Object value = row.get(column);
                if (value instanceof Map<?, ?> || value instanceof List<?>) {
                    return StepOutcome.finalFailure(Message.of("step.file.write.valueNotScalar", folder, i + 1,
                            column));
                }
                line.add(value == null ? null : String.valueOf(value));
            }
            values.add(line);
        }

        OperationResult<WrittenFile> result = connector.write(folder, file, columns, values, overrides.format());
        if (!result.isSuccess()) {
            return FileSteps.failure(result, "step.file.write.failed", folder,
                    FileEvidence.failed(folder, file, FileEvidence.WRITE));
        }

        WrittenFile written = result.getDataOrThrow();
        Map<String, Object> captured = new LinkedHashMap<>();
        captured.put("folder", written.folder());
        captured.put("name", written.name());
        captured.put("rows", written.rows());
        captured.put("size", written.size());
        return StepOutcome.success(Message.of("step.file.write.written", written.rows(), written.name(), folder),
                        captured)
                .withEvidence(FileEvidence.written(folder, written.name(), written.size(), columns, values));
    }
}
