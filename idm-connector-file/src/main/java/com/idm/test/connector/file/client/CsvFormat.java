package com.idm.test.connector.file.client;

/**
 * The format of a delimited file, as written in the settings or in a step: each value is text (or
 * {@code null} for "not given"), checked only when a file is read or written, so a mistyped setting
 * fails the step that uses it with a clear message instead of failing the whole run at start-up.
 * <p>
 * A folder's format fills its missing values from {@link #DEFAULTS}; a step's overrides replace
 * the folder's values one by one ({@link #overriddenBy}).
 *
 * @param delimiter  one character, for example {@code ","}, {@code ";"} or a tab
 * @param quote      one character, different from the delimiter
 * @param encoding   a charset name, for example {@code UTF-8} or {@code ISO-8859-1}
 * @param lineEnding the record separator for writing; reading accepts CRLF and LF
 * @param header     whether the first record is a header row
 */
public record CsvFormat(String delimiter, String quote, String encoding, LineEnding lineEnding, Boolean header) {

    /** The defaults of spec §5: comma, double quote, UTF-8, CRLF, with a header row. */
    public static final CsvFormat DEFAULTS = new CsvFormat(",", "\"", "UTF-8", LineEnding.CRLF, true);

    /** No overrides. */
    public static final CsvFormat NONE = new CsvFormat(null, null, null, null, null);

    /**
     * @return this format with every non-null value of {@code overrides} in place of its own
     */
    public CsvFormat overriddenBy(CsvFormat overrides) {
        if (overrides == null) {
            return this;
        }
        return new CsvFormat(
                overrides.delimiter != null ? overrides.delimiter : delimiter,
                overrides.quote != null ? overrides.quote : quote,
                overrides.encoding != null ? overrides.encoding : encoding,
                overrides.lineEnding != null ? overrides.lineEnding : lineEnding,
                overrides.header != null ? overrides.header : header);
    }
}
