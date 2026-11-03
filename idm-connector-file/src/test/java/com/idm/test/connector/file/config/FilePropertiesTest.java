package com.idm.test.connector.file.config;

import com.idm.test.connector.file.client.CsvFormat;
import com.idm.test.connector.file.client.FolderAccess;
import com.idm.test.connector.file.client.FolderSettings;
import com.idm.test.connector.file.client.LineEnding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FilePropertiesTest {

    private static FileProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("idm.test", FileProperties.class)
                .orElseGet(FileProperties::new);
    }

    @Test
    @DisplayName("Should bind every folder setting from spec section 5")
    void testBindsEverySetting() {
        FileProperties properties = bind(Map.of(
                "idm.test.files.hr-in.path", "/mnt/idm/hr/in",
                "idm.test.files.hr-in.access", "read-write",
                "idm.test.files.hr-in.delimiter", ";",
                "idm.test.files.hr-in.quote", "'",
                "idm.test.files.hr-in.encoding", "ISO-8859-1",
                "idm.test.files.hr-in.line-ending", "LF",
                "idm.test.files.hr-in.header", "false",
                "idm.test.files.hr-in.temp-suffix", ".part",
                "idm.test.files.hr-in.clock-skew", "30s"));

        FolderSettings settings = properties.toSettings().get("hr-in");

        assertThat(settings.path()).isEqualTo("/mnt/idm/hr/in");
        assertThat(settings.access()).isEqualTo(FolderAccess.READ_WRITE);
        assertThat(settings.format()).isEqualTo(new CsvFormat(";", "'", "ISO-8859-1", LineEnding.LF, false));
        assertThat(settings.tempSuffix()).isEqualTo(".part");
        assertThat(settings.clockSkew()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("Should apply the defaults to a folder that gives only a path")
    void testDefaults() {
        FolderSettings settings = bind(Map.of("idm.test.files.hr-out.path", "/mnt/idm/hr/out"))
                .toSettings().get("hr-out");

        assertThat(settings.access()).isEqualTo(FolderAccess.READ);
        assertThat(settings.format()).isEqualTo(CsvFormat.DEFAULTS);
        assertThat(settings.format()).isEqualTo(new CsvFormat(",", "\"", "UTF-8", LineEnding.CRLF, true));
        assertThat(settings.tempSuffix()).isEqualTo(".idmtest-tmp");
        assertThat(settings.clockSkew()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Should bind each access value as written in the settings")
    void testAccessValues() {
        for (FolderAccess access : FolderAccess.values()) {
            FileProperties properties = bind(Map.of(
                    "idm.test.files.x.path", "/x",
                    "idm.test.files.x.access", access.settingsValue()));
            assertThat(properties.toSettings().get("x").access()).isEqualTo(access);
        }
    }

    @Test
    @DisplayName("Should have no folders when none are configured")
    void testNoFolders() {
        assertThat(bind(Map.of()).toSettings()).isEmpty();
    }
}
