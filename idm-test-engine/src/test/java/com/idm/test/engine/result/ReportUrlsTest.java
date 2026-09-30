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

    @Test
    @DisplayName("Raw @ or / in a password, and several hosts, leave no credential")
    void testRawDelimitersInPassword() {
        assertThat(ReportUrls.sanitise("jdbc:postgresql://idm:p@ss@db.lab/idm")).isEqualTo("jdbc:postgresql://db.lab/idm");
        assertThat(ReportUrls.sanitise("https://svc:pa/ss@idm.lab/x")).isEqualTo("https://idm.lab/x");
        assertThat(ReportUrls.sanitise("jdbc:postgresql://u:p@h1,u:p@h2/db"))
                .doesNotContain("u:p").doesNotContain("p@");
    }

    @Test
    @DisplayName("Oracle credentials go for any driver type and case")
    void testOracleCredentials() {
        assertThat(ReportUrls.sanitise("jdbc:oracle:thin:scott/p@ss@db:1521:HR")).isEqualTo("jdbc:oracle:thin:@db:1521:HR");
        assertThat(ReportUrls.sanitise("jdbc:oracle:oci:scott/tiger@db")).isEqualTo("jdbc:oracle:oci:@db");
        assertThat(ReportUrls.sanitise("JDBC:ORACLE:THIN:scott/tiger@db:1521:HR"))
                .doesNotContain("tiger").doesNotContain("scott");
        assertThat(ReportUrls.sanitise("jdbc:oracle:thin:@//db.lab:1521/svc")).isEqualTo("jdbc:oracle:thin:@//db.lab:1521/svc");
        assertThat(ReportUrls.sanitise("jdbc:oracle:thin:@db:1521:HR")).isEqualTo("jdbc:oracle:thin:@db:1521:HR");
    }

    @Test
    @DisplayName("More credential parameter names, braced values and the MySQL address form are masked")
    void testMoreParameterShapes() {
        assertThat(ReportUrls.sanitise("jdbc:sqlserver://h:1433;user=a;pwd=s3cret")).isEqualTo("jdbc:sqlserver://h:1433;user=a;pwd=***");
        assertThat(ReportUrls.sanitise("https://h/x?sig=1&Authorization=2&pass=3"))
                .isEqualTo("https://h/x?sig=***&Authorization=***&pass=***");
        assertThat(ReportUrls.sanitise("jdbc:sqlserver://h;password={a;b}")).isEqualTo("jdbc:sqlserver://h;password=***");
        assertThat(ReportUrls.sanitise("jdbc:mysql://address=(host=h)(user=u)(password=p)/db"))
                .doesNotContain("password=p)").contains("(password=***)");
    }

    @Test
    @DisplayName("URLs without credentials are unchanged")
    void testUnchanged() {
        assertThat(ReportUrls.sanitise("ldaps://edir.lab:636")).isEqualTo("ldaps://edir.lab:636");
        assertThat(ReportUrls.sanitise("https://idm.lab:8543/IDMProv/role/service")).isEqualTo("https://idm.lab:8543/IDMProv/role/service");
        assertThat(ReportUrls.sanitise("jdbc:postgresql://db.lab/idm?ssl=true")).isEqualTo("jdbc:postgresql://db.lab/idm?ssl=true");
    }
}
