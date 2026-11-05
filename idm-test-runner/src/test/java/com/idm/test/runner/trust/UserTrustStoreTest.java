package com.idm.test.runner.trust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTrustStoreTest {

    @TempDir Path temp;

    @Test
    void firstImportKeepsPublicRootsAndDeduplicatesByFingerprint() throws Exception {
        List<X509Certificate> roots = roots();
        Path javaHome = javaHome(roots.get(0));
        Path cert = temp.resolve("lab ca.der");
        Files.write(cert, roots.get(1).getEncoded());
        UserTrustStore store = new UserTrustStore(temp.resolve("home"), javaHome);

        assertThat(store.importCertificates(cert).imported()).isEqualTo(1);
        assertThat(store.importCertificates(cert).duplicates()).isEqualTo(1);
        assertThat(entries(store.path())).hasSize(2);
    }

    @Test
    void importsEveryCertificateInPemChain() throws Exception {
        List<X509Certificate> roots = roots();
        Path chain = temp.resolve("chain.pem");
        Files.writeString(chain, pem(roots.get(1)) + pem(roots.get(2)));
        UserTrustStore store = new UserTrustStore(temp.resolve("home"), javaHome(roots.get(0)));

        assertThat(store.importCertificates(chain).imported()).isEqualTo(2);
        assertThat(entries(store.path())).hasSize(3);
    }

    @Test
    void malformedInputDoesNotChangeExistingStore() throws Exception {
        List<X509Certificate> roots = roots();
        Path cert = temp.resolve("valid.der");
        Files.write(cert, roots.get(1).getEncoded());
        UserTrustStore store = new UserTrustStore(temp.resolve("home"), javaHome(roots.get(0)));
        store.importCertificates(cert);
        byte[] before = Files.readAllBytes(store.path());
        Path broken = temp.resolve("broken.pem");
        Files.writeString(broken, "not a certificate");

        assertThatThrownBy(() -> store.importCertificates(broken)).isInstanceOf(UserTrustStore.TrustException.class);
        assertThat(Files.readAllBytes(store.path())).isEqualTo(before);
    }

    @Test
    void refreshReplacesRuntimeRootsAndPreservesImportedCertificate() throws Exception {
        List<X509Certificate> roots = roots();
        Path javaHome = javaHome(roots.get(0));
        Path cert = temp.resolve("lab.der");
        Files.write(cert, roots.get(1).getEncoded());
        UserTrustStore store = new UserTrustStore(temp.resolve("home"), javaHome);
        store.importCertificates(cert);
        writeBase(javaHome, roots.get(2));

        store.refresh();

        assertThat(entries(store.path())).hasSize(2);
        assertThat(entries(store.path())).extracting(X509Certificate::getSubjectX500Principal)
                .contains(roots.get(1).getSubjectX500Principal(), roots.get(2).getSubjectX500Principal())
                .doesNotContain(roots.get(0).getSubjectX500Principal());
    }

    @Test
    void corruptExistingStoreIsNeverOverwritten() throws Exception {
        UserTrustStore store = new UserTrustStore(temp.resolve("home"), javaHome(roots().get(0)));
        Files.createDirectories(store.path().getParent());
        Files.writeString(store.path(), "corrupt");
        byte[] before = Files.readAllBytes(store.path());
        Path cert = temp.resolve("lab.der");
        Files.write(cert, roots().get(1).getEncoded());

        assertThatThrownBy(() -> store.importCertificates(cert)).isInstanceOf(UserTrustStore.TrustException.class);
        assertThatThrownBy(store::refresh).isInstanceOf(UserTrustStore.TrustException.class);
        assertThat(Files.readAllBytes(store.path())).isEqualTo(before);
    }

    @Test
    void convertsJksRuntimeRootsToPkcs12UserStore() throws Exception {
        List<X509Certificate> roots = roots();
        Path javaHome = temp.resolve("jks-java");
        Path cacerts = javaHome.resolve("lib/security/cacerts");
        Files.createDirectories(cacerts.getParent());
        KeyStore jks = KeyStore.getInstance("JKS");
        jks.load(null, "changeit".toCharArray());
        jks.setCertificateEntry("public", roots.get(0));
        try (var out = Files.newOutputStream(cacerts)) { jks.store(out, "changeit".toCharArray()); }
        Path cert = temp.resolve("lab.der");
        Files.write(cert, roots.get(1).getEncoded());
        UserTrustStore store = new UserTrustStore(temp.resolve("home"), javaHome);

        store.importCertificates(cert);

        byte[] bytes = Files.readAllBytes(store.path());
        assertThat(java.util.Arrays.copyOf(bytes, 4)).isNotEqualTo(new byte[] {
                (byte) 0xfe, (byte) 0xed, (byte) 0xfe, (byte) 0xed });
        KeyStore pkcs12 = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store.path())) { pkcs12.load(in, "changeit".toCharArray()); }
        assertThat(pkcs12.size()).isEqualTo(2);
    }

    @Test
    void importingAnExistingPublicRootCreatesAUserStore() throws Exception {
        X509Certificate root = roots().get(0);
        UserTrustStore store = new UserTrustStore(temp.resolve("home"), javaHome(root));
        Path cert = temp.resolve("public.der");
        Files.write(cert, root.getEncoded());

        assertThat(store.importCertificates(cert).imported()).isEqualTo(1);
        assertThat(store.path()).exists();
        assertThat(store.importCertificates(cert).duplicates()).isEqualTo(1);
        assertThat(entries(store.path())).hasSize(1);
    }

    @Test
    void refreshKeepsUserImportThroughRuntimeThatAlsoContainsIt() throws Exception {
        List<X509Certificate> roots = roots();
        Path javaHome = javaHome(roots.get(0));
        UserTrustStore store = new UserTrustStore(temp.resolve("home"), javaHome);
        Path cert = temp.resolve("lab.der");
        Files.write(cert, roots.get(1).getEncoded());
        store.importCertificates(cert);

        writeBase(javaHome, roots.get(1));
        store.refresh();
        writeBase(javaHome, roots.get(2));
        store.refresh();

        assertThat(entries(store.path())).extracting(X509Certificate::getSubjectX500Principal)
                .contains(roots.get(1).getSubjectX500Principal(), roots.get(2).getSubjectX500Principal());
    }

    @Test
    void invalidStoreParentDoesNotLookLikeNoStore() throws Exception {
        Path home = temp.resolve("home");
        Files.createDirectories(home);
        Files.writeString(home.resolve(".idmtest"), "not a directory");
        UserTrustStore store = new UserTrustStore(home, javaHome(roots().get(0)));

        assertThatThrownBy(store::exists).isInstanceOf(UserTrustStore.TrustException.class);
    }

    private Path javaHome(X509Certificate root) throws Exception {
        Path home = temp.resolve("java");
        writeBase(home, root);
        return home;
    }

    private static void writeBase(Path home, X509Certificate root) throws Exception {
        Path cacerts = home.resolve("lib/security/cacerts");
        Files.createDirectories(cacerts.getParent());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, "changeit".toCharArray());
        ks.setCertificateEntry("public", root);
        try (var out = Files.newOutputStream(cacerts)) { ks.store(out, "changeit".toCharArray()); }
    }

    private static List<X509Certificate> roots() throws Exception {
        KeyStore ks = KeyStore.getInstance(Path.of(System.getProperty("java.home"), "lib/security/cacerts").toFile(),
                "changeit".toCharArray());
        List<X509Certificate> result = new ArrayList<>();
        Enumeration<String> names = ks.aliases();
        while (names.hasMoreElements() && result.size() < 3) {
            var cert = ks.getCertificate(names.nextElement());
            if (cert instanceof X509Certificate x509) result.add(x509);
        }
        return result;
    }

    private static List<X509Certificate> entries(Path file) throws Exception {
        KeyStore ks = KeyStore.getInstance(file.toFile(), "changeit".toCharArray());
        List<X509Certificate> result = new ArrayList<>();
        Enumeration<String> names = ks.aliases();
        while (names.hasMoreElements()) result.add((X509Certificate) ks.getCertificate(names.nextElement()));
        return result;
    }

    private static String pem(X509Certificate cert) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }
}
