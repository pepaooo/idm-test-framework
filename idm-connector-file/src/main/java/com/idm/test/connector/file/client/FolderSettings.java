package com.idm.test.connector.file.client;

import java.time.Duration;

/**
 * One named folder from the settings ({@code idm.test.files.<name>}).
 *
 * @param path       the folder on this machine: a local directory or a mounted share. Relative paths
 *                   resolve against the working directory. Never shown in messages or evidence.
 * @param access     what scenarios may do there; {@code null} means {@link FolderAccess#READ}
 * @param format     the folder's file format; missing values come from {@link CsvFormat#DEFAULTS}
 * @param tempSuffix the suffix {@code file.write} writes under before renaming, and that
 *                   {@code file.verify} ignores; {@code null} or blank means {@link #DEFAULT_TEMP_SUFFIX}
 * @param clockSkew  how much older than the scenario's start a file may look and still count, because
 *                   a mounted share stamps files with the server's clock; {@code null} means
 *                   {@link #DEFAULT_CLOCK_SKEW}
 */
public record FolderSettings(String path, FolderAccess access, CsvFormat format, String tempSuffix,
                             Duration clockSkew) {

    public static final String DEFAULT_TEMP_SUFFIX = ".idmtest-tmp";
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(5);

    public FolderSettings {
        access = access == null ? FolderAccess.READ : access;
        format = CsvFormat.DEFAULTS.overriddenBy(format);
        tempSuffix = tempSuffix == null || tempSuffix.isBlank() ? DEFAULT_TEMP_SUFFIX : tempSuffix;
        clockSkew = clockSkew == null || clockSkew.isNegative() ? DEFAULT_CLOCK_SKEW : clockSkew;
    }

    /** A folder with the default format, suffix and clock skew. */
    public static FolderSettings of(String path, FolderAccess access) {
        return new FolderSettings(path, access, null, null, null);
    }
}
