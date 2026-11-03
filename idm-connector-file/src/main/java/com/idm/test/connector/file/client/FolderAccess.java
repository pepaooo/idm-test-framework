package com.idm.test.connector.file.client;

/**
 * What scenarios may do in a named folder. Declared per folder in the lab settings; a folder
 * without a declaration is {@link #READ}, so a folder is never writable by accident.
 */
public enum FolderAccess {
    READ("read"),
    WRITE("write"),
    READ_WRITE("read-write");

    private final String settingsValue;

    FolderAccess(String settingsValue) {
        this.settingsValue = settingsValue;
    }

    /** @return true when {@code file.verify} may read files in the folder */
    public boolean canRead() {
        return this != WRITE;
    }

    /** @return true when {@code file.write} and {@code file.delete} may change files in the folder */
    public boolean canWrite() {
        return this != READ;
    }

    /** @return the value as written in the settings, for example {@code read-write} */
    public String settingsValue() {
        return settingsValue;
    }
}
