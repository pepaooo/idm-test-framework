package com.idm.test.connector.file.config;

import com.idm.test.connector.file.client.CsvFormat;
import com.idm.test.connector.file.client.FolderAccess;
import com.idm.test.connector.file.client.FolderSettings;
import com.idm.test.connector.file.client.LineEnding;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The named folders the file steps may use.
 * <p>
 * Example configuration in application.yml:
 * <pre>
 * idm:
 *   test:
 *     files:
 *       hr-in:                       # Delimited Text driver input
 *         path: /mnt/idm/hr/in
 *         access: write              # read | write | read-write; default read
 *         delimiter: ","             # default ","
 *         quote: '"'                 # default '"'
 *         encoding: UTF-8            # default UTF-8
 *         line-ending: CRLF          # CRLF | LF; default CRLF
 *         header: true               # default true
 *         temp-suffix: .idmtest-tmp  # default .idmtest-tmp
 *         clock-skew: 5s             # default 5s
 *       hr-out:
 *         path: /mnt/idm/hr/out
 *         access: read
 * </pre>
 * Format values are not checked here: a mistyped one fails the step that uses the folder, with a
 * message naming the setting, rather than the whole run at start-up.
 */
@ConfigurationProperties(prefix = "idm.test")
public class FileProperties {

    private Map<String, FolderConfig> files = new LinkedHashMap<>();

    public Map<String, FolderConfig> getFiles() {
        return files;
    }

    public void setFiles(Map<String, FolderConfig> files) {
        this.files = files;
    }

    /** @return the folders as the connector takes them, defaults applied */
    public Map<String, FolderSettings> toSettings() {
        Map<String, FolderSettings> settings = new LinkedHashMap<>();
        files.forEach((name, config) -> settings.put(name, config.toSettings()));
        return settings;
    }

    /** One folder. Every value except {@code path} is optional. */
    public static class FolderConfig {
        private String label;
        private String path;
        private FolderAccess access;
        private String delimiter;
        private String quote;
        private String encoding;
        private LineEnding lineEnding;
        private Boolean header;
        private String tempSuffix;
        private Duration clockSkew;

        public FolderSettings toSettings() {
            return new FolderSettings(path, access, new CsvFormat(delimiter, quote, encoding, lineEnding, header),
                    tempSuffix, clockSkew);
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public FolderAccess getAccess() {
            return access;
        }

        public void setAccess(FolderAccess access) {
            this.access = access;
        }

        public String getDelimiter() {
            return delimiter;
        }

        public void setDelimiter(String delimiter) {
            this.delimiter = delimiter;
        }

        public String getQuote() {
            return quote;
        }

        public void setQuote(String quote) {
            this.quote = quote;
        }

        public String getEncoding() {
            return encoding;
        }

        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }

        public LineEnding getLineEnding() {
            return lineEnding;
        }

        public void setLineEnding(LineEnding lineEnding) {
            this.lineEnding = lineEnding;
        }

        public Boolean getHeader() {
            return header;
        }

        public void setHeader(Boolean header) {
            this.header = header;
        }

        public String getTempSuffix() {
            return tempSuffix;
        }

        public void setTempSuffix(String tempSuffix) {
            this.tempSuffix = tempSuffix;
        }

        public Duration getClockSkew() {
            return clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }
    }
}
