package com.idm.test.runner.trust;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.Message;
import com.idm.test.runner.CommandLine;
import com.idm.test.runner.ExitCodes;

import java.io.PrintStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateParsingException;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** CLI entry point for managing the user-level certificate store. */
public final class TrustCommand {
    private TrustCommand() { }

    public static int run(CommandLine line, PrintStream out, PrintStream err, Locale locale, UserTrustStore store) {
        return run(line, new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset())), out, err,
                locale, store, new ServerCertificateFetcher()::fetch, System.console() != null);
    }

    public static int run(CommandLine line, BufferedReader in, PrintStream out, PrintStream err, Locale locale,
                          UserTrustStore store, Function<ServerEndpoint, X509Certificate> fetcher) {
        return run(line, in, out, err, locale, store, fetcher, true);
    }

    static int run(CommandLine line, BufferedReader in, PrintStream out, PrintStream err, Locale locale,
                   UserTrustStore store, Function<ServerEndpoint, X509Certificate> fetcher, boolean interactive) {
        try {
            if ("import".equals(line.action())) {
                UserTrustStore.ImportResult result = store.importCertificates(Path.of(line.names().getFirst()));
                for (UserTrustStore.CertificateInfo cert : result.certificates()) {
                    out.println(Messages.render(Message.of(cert.duplicate() ? "runner.trust.duplicate" : "runner.trust.imported",
                            cert.subject(), cert.fingerprint()), locale));
                }
                out.println(Messages.render(Message.of("runner.trust.path", store.path()), locale));
            } else if ("import-server".equals(line.action())) {
                if (!interactive) throw new UserTrustStore.TrustException("import-server requires an interactive terminal");
                ServerEndpoint endpoint = ServerEndpoint.parse(line.names().getFirst());
                X509Certificate leaf = fetcher.apply(endpoint);
                out.println(Messages.render(Message.of("runner.trust.serverDetails", endpoint,
                        leaf.getSubjectX500Principal().getName(), leaf.getIssuerX500Principal().getName(),
                        leaf.getNotBefore().toInstant(), leaf.getNotAfter().toInstant(),
                        alternativeNames(leaf), UserTrustStore.fingerprint(leaf)), locale));
                out.println(Messages.render(Message.of("runner.trust.unverified"), locale));
                out.print(Messages.render(Message.of("runner.trust.confirm"), locale));
                out.flush();
                String answer = in.readLine();
                if (answer == null || !(answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"))) {
                    out.println(Messages.render(Message.of("runner.trust.cancelled"), locale));
                    return ExitCodes.OK;
                }
                UserTrustStore.ImportResult result = store.importCertificates(List.of(leaf));
                UserTrustStore.CertificateInfo cert = result.certificates().getFirst();
                out.println(Messages.render(Message.of(cert.duplicate() ? "runner.trust.duplicate" : "runner.trust.imported",
                        cert.subject(), cert.fingerprint()), locale));
                out.println(Messages.render(Message.of("runner.trust.path", store.path()), locale));
                out.println(Messages.render(Message.of("runner.trust.additive"), locale));
            } else {
                UserTrustStore.RefreshResult result = store.refresh();
                out.println(Messages.render(Message.of("runner.trust.refreshed", result.publicRoots(),
                        result.userCertificates(), store.path()), locale));
            }
            return ExitCodes.OK;
        } catch (UserTrustStore.TrustException | IllegalArgumentException | IOException e) {
            err.println(Messages.render(Message.of("runner.trust.error", e.getMessage()), locale));
            return ExitCodes.CANNOT_START;
        }
    }

    private static String alternativeNames(X509Certificate certificate) {
        try {
            var names = certificate.getSubjectAlternativeNames();
            if (names == null) return "(none)";
            return names.stream().filter(name -> name.size() > 1 && (name.get(0).equals(2) || name.get(0).equals(7)))
                    .map(name -> String.valueOf(name.get(1))).collect(java.util.stream.Collectors.joining(", "));
        } catch (CertificateParsingException e) {
            throw new UserTrustStore.TrustException("cannot read server certificate subject alternative names", e);
        }
    }
}
