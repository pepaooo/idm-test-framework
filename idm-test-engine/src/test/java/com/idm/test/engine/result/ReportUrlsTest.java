package com.idm.test.engine.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Report URL sanitising")
class ReportUrlsTest {

    @Test
    @DisplayName("User info before the host is removed")
    void testUserInfoRemoved() {
        assertThat(ReportUrls.sanitise("jdbc:postgresql://idm:s3cret@db.lab:5432/idm"))
                .isEqualTo("jdbc:postgresql://db.lab:5432/idm");
        assertThat(ReportUrls.sanitise("https://svc:pw@idm.lab:8543/IDMProv/role/service"))
                .isEqualTo("https://idm.lab:8543/IDMProv/role/service");
        assertThat(ReportUrls.sanitise("jdbc:oracle:thin:scott/tiger@db.lab:1521:HR"))
                .isEqualTo("jdbc:oracle:thin:@db.lab:1521:HR");
    }

    @Test
    @DisplayName("Credential-looking parameters are masked, others kept")
    void testCredentialParametersMasked() {
        assertThat(ReportUrls.sanitise("jdbc:postgresql://db.lab/idm?user=idm&password=s3cret&ssl=true"))
                .isEqualTo("jdbc:postgresql://db.lab/idm?user=idm&password=***&ssl=true");
        assertThat(ReportUrls.sanitise("https://api.lab/v1?api_key=abc123&Token=xyz"))
                .isEqualTo("https://api.lab/v1?api_key=***&Token=***");
        assertThat(ReportUrls.sanitise("jdbc:sqlserver://db.lab:1433;databaseName=idm;password=s3cret"))
                .isEqualTo("jdbc:sqlserver://db.lab:1433;databaseName=idm;password=***");
        assertThat(ReportUrls.sanitise("ldaps://edir.lab:636")).isEqualTo("ldaps://edir.lab:636");
    }

    @Test
    @DisplayName("Text that isn't a URL is masked the same way, and null stays null")
    void testNonUrl() {
        assertThat(ReportUrls.sanitise("not a url;secret=x")).isEqualTo("not a url;secret=***");
        assertThat(ReportUrls.sanitise(null)).isNull();
    }
}
