package com.idm.test.connector.file.client;

/**
 * A file {@link DelimitedFileConnector#write} wrote.
 *
 * @param folder the folder's settings name (never its path)
 * @param name   the final file name
 * @param rows   the number of data rows, not counting the header
 * @param size   the file's size in bytes
 */
public record WrittenFile(String folder, String name, int rows, long size) {
}
