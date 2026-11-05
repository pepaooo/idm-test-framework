package com.idm.test.runner.trust;

import com.idm.test.runner.CommandLine;
import com.idm.test.runner.ExitCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class TrustCommandTest {
    @TempDir Path temp;

    @Test
    void importServerRequiresExplicitConfirmation() throws Exception {
        X509Certificate cert = root(1);
        for (String answer : new String[]{"", "no\n", "\n"}) {
            UserTrustStore store = new UserTrustStore(temp.resolve("home-" + answer.length()), fakeJavaHome(root(0)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code = TrustCommand.run(CommandLine.parse("trust", "import-server", "lab.example:443"),
                    new BufferedReader(new StringReader(answer)), new PrintStream(out),
                    new PrintStream(new ByteArrayOutputStream()), Locale.ENGLISH, store, endpoint -> cert);
            assertThat(code).isEqualTo(ExitCodes.OK);
            assertThat(store.exists()).isFalse();
            assertThat(out.toString()).contains("SHA-256", "Trust this certificate");
        }
    }

    @Test
    void importServerYesWritesLeafToUserStore() throws Exception {
        X509Certificate cert = root(1);
        UserTrustStore store = new UserTrustStore(temp.resolve("approved-home"), fakeJavaHome(root(0)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = TrustCommand.run(CommandLine.parse("trust", "import-server", "lab.example:443"),
                new BufferedReader(new StringReader("yes\n")), new PrintStream(out),
                new PrintStream(new ByteArrayOutputStream()), Locale.ENGLISH, store, endpoint -> cert);
        assertThat(code).isEqualTo(ExitCodes.OK);
        assertThat(store.exists()).isTrue();
        assertThat(out.toString()).contains("lab.example:443", "SHA-256", cert.getSubjectX500Principal().getName());
    }

    @Test
    void importReportsCertificateAndRefreshSucceeds() throws Exception {
        X509Certificate cert = root(1);
        Path input = temp.resolve("lab.der");
        Files.write(input, cert.getEncoded());
        UserTrustStore store = new UserTrustStore(temp.resolve("home"), fakeJavaHome(root(0)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int imported = TrustCommand.run(CommandLine.parse("trust", "import", input.toString()),
                new PrintStream(out), new PrintStream(err), Locale.ENGLISH, store);
        int refreshed = TrustCommand.run(CommandLine.parse("trust", "refresh"),
                new PrintStream(out), new PrintStream(err), Locale.ENGLISH, store);

        assertThat(imported).isEqualTo(ExitCodes.OK);
        assertThat(refreshed).isEqualTo(ExitCodes.OK);
        assertThat(store.exists()).isTrue();
        assertThat(out.toString()).contains(cert.getSubjectX500Principal().getName(), "SHA-256", "refreshed");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void malformedCertificateReturnsUsageErrorWithoutCreatingStore() throws Exception {
        Path input = temp.resolve("bad.pem");
        Files.writeString(input, "bad certificate");
        UserTrustStore store = new UserTrustStore(temp.resolve("home"), Path.of(System.getProperty("java.home")));
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = TrustCommand.run(CommandLine.parse("trust", "import", input.toString()),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err), Locale.ENGLISH, store);

        assertThat(code).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(store.exists()).isFalse();
        assertThat(err.toString()).contains("X.509 certificates");
    }

    private Path fakeJavaHome(X509Certificate root) throws Exception {
        Path home = temp.resolve("java");
        Path cacerts = home.resolve("lib/security/cacerts");
        Files.createDirectories(cacerts.getParent());
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, "changeit".toCharArray());
        store.setCertificateEntry("public", root);
        try (var out = Files.newOutputStream(cacerts)) { store.store(out, "changeit".toCharArray()); }
        return home;
    }

    private static X509Certificate root(int index) throws Exception {
        KeyStore roots = KeyStore.getInstance(Path.of(System.getProperty("java.home"), "lib/security/cacerts").toFile(),
                "changeit".toCharArray());
        Enumeration<String> aliases = roots.aliases();
        for (int i = 0; i < index; i++) aliases.nextElement();
        return (X509Certificate) roots.getCertificate(aliases.nextElement());
    }
}
