package com.idm.test.runner.trust;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.Message;
import com.idm.test.runner.CommandLine;
import com.idm.test.runner.ExitCodes;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;

/** CLI entry point for managing the user-level certificate store. */
public final class TrustCommand {
    private TrustCommand() { }

    public static int run(CommandLine line, PrintStream out, PrintStream err, Locale locale, UserTrustStore store) {
        try {
            if ("import".equals(line.action())) {
                UserTrustStore.ImportResult result = store.importCertificates(Path.of(line.names().getFirst()));
                for (UserTrustStore.CertificateInfo cert : result.certificates()) {
                    out.println(Messages.render(Message.of(cert.duplicate() ? "runner.trust.duplicate" : "runner.trust.imported",
                            cert.subject(), cert.fingerprint()), locale));
                }
                out.println(Messages.render(Message.of("runner.trust.path", store.path()), locale));
            } else {
                UserTrustStore.RefreshResult result = store.refresh();
                out.println(Messages.render(Message.of("runner.trust.refreshed", result.publicRoots(),
                        result.userCertificates(), store.path()), locale));
            }
            return ExitCodes.OK;
        } catch (UserTrustStore.TrustException e) {
            err.println(Messages.render(Message.of("runner.trust.error", e.getMessage()), locale));
            return ExitCodes.CANNOT_START;
        }
    }
}
