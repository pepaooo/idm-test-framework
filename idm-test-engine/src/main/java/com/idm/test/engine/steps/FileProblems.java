package com.idm.test.engine.steps;

import com.idm.test.connector.file.client.FileProblem;
import com.idm.test.engine.spi.Message;

/**
 * Words the file connector's problems by key, so they read in the run's language: for the
 * {@code file.*} steps, and for the real-environment guards and the workbench's connection test.
 */
public final class FileProblems {

    private FileProblems() {
    }

    /** The connector's problem by key, so it reads in the run's language. */
    public static Message of(FileProblem problem) {
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
