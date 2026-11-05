package com.idm.test.runner.trust;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** A user's private copy of Java's public roots plus certificates explicitly imported for idmtest. */
public final class UserTrustStore {
    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final String USER_PREFIX = "idmtest-";

    public record CertificateInfo(String subject, String fingerprint, boolean duplicate) { }
    public record ImportResult(int imported, int duplicates, List<CertificateInfo> certificates) { }
    public record RefreshResult(int publicRoots, int userCertificates) { }

    public static final class TrustException extends RuntimeException {
        public TrustException(String message, Throwable cause) { super(message, cause); }
        public TrustException(String message) { super(message); }
    }

    private final Path path;
    private final Path javaHome;

    public UserTrustStore(Path userHome, Path javaHome) {
        path = userHome.resolve(".idmtest/truststore.p12");
        this.javaHome = javaHome;
    }

    public static UserTrustStore current() {
        return new UserTrustStore(Path.of(System.getProperty("user.home")), Path.of(System.getProperty("java.home")));
    }

    public Path path() { return path; }
    public boolean exists() {
        try {
            Files.readAttributes(path, BasicFileAttributes.class);
            return true;
        } catch (NoSuchFileException e) {
            try {
                BasicFileAttributes parent = Files.readAttributes(path.getParent(), BasicFileAttributes.class);
                if (parent.isDirectory()) return false;
                throw new TrustException("cannot access idmtest truststore at " + path
                        + ": its parent is not a directory");
            } catch (NoSuchFileException missingParent) {
                return false;
            } catch (IOException | SecurityException accessError) {
                throw new TrustException("cannot access idmtest truststore at " + path
                        + ": " + accessError.getMessage(), accessError);
            }
        } catch (IOException | SecurityException e) {
            throw new TrustException("cannot access idmtest truststore at " + path + ": " + e.getMessage(), e);
        }
    }

    public ImportResult importCertificates(Path file) {
        return importCertificates(readCertificates(file), file.toString());
    }

    public ImportResult importCertificates(List<X509Certificate> incoming) {
        if (incoming == null || incoming.isEmpty()) throw new TrustException("no X.509 certificates supplied");
        return importCertificates(incoming, "server");
    }

    private ImportResult importCertificates(List<X509Certificate> incoming, String source) {
        boolean hadStore = exists();
        KeyStore store = hadStore ? readStore(path) : readBase();
        List<CertificateInfo> infos = new ArrayList<>();
        int added = 0;
        for (X509Certificate cert : incoming) {
            String fingerprint = fingerprint(cert);
            String existingAlias = findAlias(store, fingerprint);
            boolean duplicate = existingAlias != null && existingAlias.startsWith(USER_PREFIX);
            infos.add(new CertificateInfo(cert.getSubjectX500Principal().getName(), fingerprint, duplicate));
            if (!duplicate) {
                try {
                    if (existingAlias != null) store.deleteEntry(existingAlias);
                    store.setCertificateEntry(USER_PREFIX + fingerprint, cert);
                } catch (GeneralSecurityException e) {
                    throw new TrustException("cannot add certificate from " + source + ": " + e.getMessage(), e);
                }
                added++;
            }
        }
        if (added > 0 || !hadStore) writeStore(store);
        return new ImportResult(added, incoming.size() - added, List.copyOf(infos));
    }

    public RefreshResult refresh() {
        if (!exists()) throw new TrustException("no idmtest truststore exists; run 'idmtest trust import <certificate-file>' first");
        KeyStore old = readStore(path);
        KeyStore fresh = readBase();
        int publicRoots = count(fresh);
        int preserved = 0;
        try {
            Enumeration<String> aliases = old.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!alias.startsWith(USER_PREFIX)) continue;
                Certificate cert = old.getCertificate(alias);
                if (cert instanceof X509Certificate x509) {
                    String existingAlias = findAlias(fresh, fingerprint(x509));
                    if (existingAlias != null && !existingAlias.equals(alias)) fresh.deleteEntry(existingAlias);
                    fresh.setCertificateEntry(alias, cert);
                    preserved++;
                }
            }
        } catch (GeneralSecurityException e) {
            throw new TrustException("cannot refresh " + path + ": " + e.getMessage(), e);
        }
        writeStore(fresh);
        return new RefreshResult(publicRoots, preserved);
    }

    public void selectForCurrentJvm() {
        if (System.getProperty("javax.net.ssl.trustStore") != null || !exists()) return;
        readStore(path); // Fail before connecting if the file is corrupt or unreadable.
        System.setProperty("javax.net.ssl.trustStore", path.toAbsolutePath().toString());
        System.setProperty("javax.net.ssl.trustStorePassword", new String(PASSWORD));
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
    }

    private KeyStore readBase() {
        Path cacerts = javaHome.resolve("lib/security/cacerts");
        try {
            KeyStore source = KeyStore.getInstance(cacerts.toFile(), PASSWORD);
            KeyStore target = KeyStore.getInstance("PKCS12");
            target.load(null, PASSWORD);
            Enumeration<String> aliases = source.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (source.isCertificateEntry(alias)) target.setCertificateEntry(alias, source.getCertificate(alias));
            }
            return target;
        } catch (IOException | GeneralSecurityException e) {
            throw new TrustException("cannot read Java's public CA store at " + cacerts + ": " + e.getMessage(), e);
        }
    }

    private static KeyStore readStore(Path file) {
        try {
            return KeyStore.getInstance(file.toFile(), PASSWORD);
        } catch (IOException | GeneralSecurityException e) {
            throw new TrustException("cannot read idmtest truststore at " + file
                    + "; restore a backup or move the file aside and import your CA certificates again: " + e.getMessage(), e);
        }
    }

    private static List<X509Certificate> readCertificates(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Collection<? extends Certificate> parsed = CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (parsed.isEmpty()) throw new TrustException("no X.509 certificates found in " + file);
            List<X509Certificate> result = new ArrayList<>();
            for (Certificate cert : parsed) result.add((X509Certificate) cert);
            return result;
        } catch (IOException | GeneralSecurityException e) {
            throw new TrustException("cannot read X.509 certificates from " + file + ": " + e.getMessage(), e);
        }
    }

    private static String findAlias(KeyStore store, String fingerprint) {
        try {
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate cert = store.getCertificate(alias);
                if (cert instanceof X509Certificate x509 && fingerprint(x509).equals(fingerprint)) return alias;
            }
            return null;
        } catch (GeneralSecurityException e) {
            throw new TrustException("cannot inspect truststore: " + e.getMessage(), e);
        }
    }

    public static String fingerprint(X509Certificate cert) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new TrustException("cannot fingerprint certificate: " + e.getMessage(), e);
        }
    }

    private static int count(KeyStore store) {
        try { return store.size(); }
        catch (GeneralSecurityException e) { throw new TrustException("cannot count truststore entries", e); }
    }

    private void writeStore(KeyStore store) {
        Path parent = path.getParent();
        Path staged = null;
        try {
            Files.createDirectories(parent);
            restrict(parent, true);
            staged = Files.createTempFile(parent, "truststore-", ".tmp");
            restrict(staged, false);
            try (OutputStream out = Files.newOutputStream(staged)) { store.store(out, PASSWORD); }
            Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new TrustException("cannot safely replace truststore at " + path + ": atomic rename is unavailable", e);
        } catch (IOException | GeneralSecurityException e) {
            throw new TrustException("cannot write truststore at " + path + ": " + e.getMessage(), e);
        } finally {
            if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) { }
        }
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, directory
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows does not expose POSIX permissions.
        }
    }
}
