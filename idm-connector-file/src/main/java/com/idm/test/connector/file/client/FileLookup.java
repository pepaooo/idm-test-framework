package com.idm.test.connector.file.client;

import java.util.Optional;

/**
 * What {@link DelimitedFileConnector#readNewest} found in a folder.
 *
 * @param file         the newest candidate, read; empty when no file counts yet
 * @param olderMatches files whose name matched but which are older than the scenario's start (less the
 *                     folder's clock skew), so they cannot be the file this scenario is waiting for
 */
public record FileLookup(Optional<ReadFile> file, int olderMatches) {

    public FileLookup {
        file = file == null ? Optional.empty() : file;
    }
}
