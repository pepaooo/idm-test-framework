package com.idm.test.engine.steps;

import com.idm.test.connector.file.client.DelimitedFileConnector;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.FileEvidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;
import com.idm.test.engine.spi.StepType;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deletes one file from a named folder, usually in {@code cleanup:}.
 * <pre>
 * cleanup:
 *   - file.delete:
 *       folder: hr-in
 *       file: "${written.name}"
 * </pre>
 * A file that is already absent is a success: the Delimited Text driver usually moves or deletes its
 * input after processing it. {@code file} is one exact name; a pattern is rejected before the run.
 * Captures whether a file was deleted.
 */
public final class FileDeleteStep implements StepType {

    private final DelimitedFileConnector connector;

    public FileDeleteStep(DelimitedFileConnector connector) {
        this.connector = connector;
    }

    @Override
    public String name() {
        return "file.delete";
    }

    @Override
    public Set<String> requiredParams() {
        return Set.of(FileSteps.FOLDER, FileSteps.FILE);
    }

    @Override
    public Optional<Message> literalProblem(Map<String, Object> params) {
        return FileSteps.literalFileProblem(params, false);
    }

    @Override
    public StepOutcome execute(Map<String, Object> params, boolean captureRequested) {
        String folder = String.valueOf(params.get(FileSteps.FOLDER));
        String file = String.valueOf(params.get(FileSteps.FILE));

        OperationResult<Boolean> result = connector.delete(folder, file);
        if (!result.isSuccess()) {
            return FileSteps.failure(result, "step.file.delete.failed", folder,
                    FileEvidence.failed(folder, file, FileEvidence.DELETE));
        }
        boolean deleted = result.getDataOrThrow();
        Message message = deleted
                ? Message.of("step.file.delete.deleted", file, folder)
                : Message.of("step.file.delete.absent", file, folder);
        return StepOutcome.success(message, deleted).withEvidence(FileEvidence.deleted(folder, file, deleted));
    }
}
