package com.idm.test.runner;

import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.engine.i18n.Messages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JDBC driver pre-flight check")
class DriverCheckTest {

    private static final Path DRIVERS = Path.of("/Users/ana/My Tests/drivers");

    @Test
    @DisplayName("Should name the URL, the database and the drivers/ folder when no driver accepts the URL")
    void testMissingDriver() {
        DatabaseProperties databases = databases("hr", "jdbc:db2://h/x", null);

        assertThatThrownBy(() -> DriverCheck.check(databases, Set.of("hr"), DRIVERS))
                .isInstanceOf(UsageException.class)
                .hasMessage("no JDBC driver for 'jdbc:db2://h/x' (database 'hr'); "
                        + "put the driver jar in /Users/ana/My Tests/drivers/");
    }

    @Test
    @DisplayName("Should say it in Spanish too")
    void testMissingDriverInSpanish() {
        DatabaseProperties databases = databases("hr", "jdbc:db2://h/x", null);

        assertThatThrownBy(() -> DriverCheck.check(databases, Set.of("hr"), DRIVERS))
                .isInstanceOfSatisfying(UsageException.class, e -> assertThat(
                        Messages.render(e.detail(), Locale.forLanguageTag("es")))
                        .contains("'jdbc:db2://h/x'", "'hr'", "/Users/ana/My Tests/drivers/")
                        .doesNotContain("no JDBC driver"));
    }

    @Test
    @DisplayName("Should keep credentials in the URL out of the message")
    void testUrlCredentialsHidden() {
        DatabaseProperties databases = databases("hr", "jdbc:db2://scott:tiger@h/x;password=s3cret-pw", null);

        assertThatThrownBy(() -> DriverCheck.check(databases, Set.of("hr"), DRIVERS))
                .isInstanceOf(UsageException.class)
                .hasMessageNotContaining("tiger")
                .hasMessageNotContaining("s3cret-pw")
                .hasMessageContaining("database 'hr'");
    }

    @Test
    @DisplayName("Should pass for the bundled PostgreSQL, Oracle and SQL Server drivers")
    void testBundledDrivers() {
        DatabaseProperties databases = databases("sap", "jdbc:postgresql://h/x", null);
        databases.getDatabases().put("hr", config("jdbc:oracle:thin:@h:1521:HR", null));
        databases.getDatabases().put("crm", config("jdbc:sqlserver://h:1433;databaseName=crm", null));

        assertThatCode(() -> DriverCheck.check(databases, Set.of("sap", "hr", "crm"), DRIVERS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should check a configured driver-class-name by loading the class")
    void testDriverClassName() {
        DatabaseProperties present = databases("sap", "jdbc:postgresql://h/x", "org.postgresql.Driver");
        DatabaseProperties absent = databases("hr", "jdbc:postgresql://h/x", "com.ibm.db2.jcc.DB2Driver");

        assertThatCode(() -> DriverCheck.check(present, Set.of("sap"), DRIVERS)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DriverCheck.check(absent, Set.of("hr"), DRIVERS))
                .hasMessageStartingWith("no JDBC driver for 'jdbc:postgresql://h/x' (database 'hr')");
    }

    @Test
    @DisplayName("Should check only the databases the selected scenarios use")
    void testUnusedDatabaseIgnored() {
        DatabaseProperties databases = databases("hr", "jdbc:db2://h/x", null);

        assertThatCode(() -> DriverCheck.check(databases, Set.of(), DRIVERS)).doesNotThrowAnyException();
        assertThatCode(() -> DriverCheck.check(databases, Set.of("unknown"), DRIVERS)).doesNotThrowAnyException();
    }

    private static DatabaseProperties databases(String name, String url, String driverClassName) {
        DatabaseProperties properties = new DatabaseProperties();
        properties.setDatabases(new java.util.HashMap<>(Map.of(name, config(url, driverClassName))));
        return properties;
    }

    private static DatabaseProperties.DatabaseConfig config(String url, String driverClassName) {
        DatabaseProperties.DatabaseConfig config = new DatabaseProperties.DatabaseConfig();
        config.setJdbcUrl(url);
        config.setDriverClassName(driverClassName);
        return config;
    }
}
