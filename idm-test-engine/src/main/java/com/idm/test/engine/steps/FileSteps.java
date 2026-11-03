package com.idm.test.engine.steps;

import com.idm.test.connector.file.client.CsvFormat;
import com.idm.test.connector.file.client.DelimitedFileConnector;
import com.idm.test.connector.file.client.FileProblem;
import com.idm.test.connector.file.client.LineEnding;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.spi.Evidence;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepOutcome;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the three {@code file.*} steps share: their format parameters, the check of a literal file
 * name, and the wording of the connector's problems.
 */
final class FileSteps {

    static final String FOLDER = "folder";
    static final String FILE = "file";
    static final String DELIMITER = "delimiter";
    static final String QUOTE = "quote";
    static final String ENCODING = "encoding";
    static final String LINE_ENDING = "lineEnding";
    static final String HEADER = "header";

    /** The format parameters every file step reading a file accepts. */
    static final Set<String> READ_FORMAT = Set.of(DELIMITER, QUOTE, ENCODING, HEADER);

    private FileSteps() {
    }

    /** A step's format overrides, or the final failure that a malformed one is. */
    record Overrides(CsvFormat format, StepOutcome rejection) {
    }

    static Overrides overrides(Map<String, Object> params) {
        LineEnding lineEnding = null;
        Object rawLineEnding = params.get(LINE_ENDING);
        if (rawLineEnding != null) {
            try {
                lineEnding = LineEnding.valueOf(String.valueOf(rawLineEnding).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return new Overrides(null, StepOutcome.finalFailure(
                        Message.of("step.file.badLineEnding", rawLineEnding)));
            }
        }
        Boolean header = null;
        Object rawHeader = params.get(HEADER);
        if (rawHeader instanceof Boolean flag) {
            header = flag;
        } else if (rawHeader != null) {
            String text = String.valueOf(rawHeader).trim();
            if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
                header = Boolean.valueOf(text);
            } else {
                return new Overrides(null, StepOutcome.finalFailure(Message.of("step.file.badHeader", rawHeader)));
            }
        }
        return new Overrides(new CsvFormat(text(params.get(DELIMITER)), text(params.get(QUOTE)),
                text(params.get(ENCODING)), lineEnding, header), null);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The problem with a literal {@code file} value, for the validator; values holding an expression
     * are left for the connector to check at run time.
     *
     * @param globAllowed whether {@code file} may be a pattern ({@code file.verify} only)
     */
    static Optional<Message> literalFileProblem(Map<String, Object> params, boolean globAllowed) {
        if (!(params.get(FILE) instanceof String file) || file.contains("${") || file.startsWith("js:")) {
            return Optional.empty();
        }
        if (!DelimitedFileConnector.isPlainName(file)) {
            return Optional.of(Message.of("validation.file.notPlain", file));
        }
        if (!globAllowed && DelimitedFileConnector.hasGlob(file)) {
            return Optional.of(Message.of("validation.file.glob", file));
        }
        return Optional.empty();
    }

    /**
     * A failed connector result as the step's failure: final when waiting cannot change it (an
     * unknown or missing folder, refused access, a bad name or format), otherwise an ordinary failure
     * of the result's type, which a {@code wait} retries.
     *
     * @param failureKey the step's "failed" key, taking (folder, problem)
     * @param evidence   shown unless the failure is final (the folder was never reached), or null
     */
    static StepOutcome failure(OperationResult<?> result, String failureKey, String folder, Evidence evidence) {
        Optional<FileProblem> problem = FileProblem.of(result);
        Object detail = problem.<Object>map(FileSteps::message).orElse(result.getMessage());
        Message message = Message.of(failureKey, folder, detail);
        ErrorType type = result.getErrorType().orElse(ErrorType.UNKNOWN_ERROR);
        if (type == ErrorType.CONFIGURATION_ERROR) {
            return StepOutcome.finalFailure(message);
        }
        StepOutcome failure = StepOutcome.failure(message, type);
        return evidence == null ? failure : failure.withEvidence(evidence);
    }

    /** The connector's problem by key, so it reads in the run's language. */
    static Message message(FileProblem problem) {
        return Message.of(key(problem.kind()), problem.values().toArray());
    }

    private static String key(FileProblem.Kind kind) {
        return switch (kind) {
            case NOT_CONFIGURED -> "step.file.connector.notConfigured";
            case NO_PATH -> "step.file.connector.noPath";
            case FOLDER_MISSING -> "step.file.connector.folderMissing";
            case NOT_A_DIRECTORY -> "step.file.connector.notADirectory";
            case ACCESS_DENIED -> "step.file.connector.accessDenied";
            case UNSAFE_NAME -> "step.file.connector.unsafeName";
            case RESERVED_NAME -> "step.file.connector.reservedName";
            case BAD_PATTERN -> "step.file.connector.badPattern";
            case BAD_FORMAT -> "step.file.connector.badFormat";
            case BAD_ROWS -> "step.file.connector.badRows";
            case NO_HEADER -> "step.file.connector.noHeader";
            case ALREADY_EXISTS -> "step.file.connector.alreadyExists";
            case TEMP_EXISTS -> "step.file.connector.tempExists";
            case NOT_A_FILE -> "step.file.connector.notAFile";
            case NO_ATOMIC_MOVE -> "step.file.connector.noAtomicMove";
            case UNENCODABLE -> "step.file.connector.unencodable";
            case NOT_READABLE -> "step.file.connector.notReadable";
            case NOT_WRITABLE -> "step.file.connector.notWritable";
            case IO_FAILED -> "step.file.connector.ioFailed";
            case PARSE_FAILED -> "step.file.connector.parseFailed";
        };
    }
}
