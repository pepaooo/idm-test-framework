package com.idm.test.runner;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.i18n.ReportLocale;
import com.idm.test.engine.result.PdfReportWriter;
import com.idm.test.engine.result.RunFolder;
import com.idm.test.engine.spi.Message;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * {@code idmtest report <run-folder> --pdf summary|detailed [--out file]}: renders a PDF evidence report
 * from a finished run folder. It reads no settings and no secrets and starts no Spring context.
 */
public final class ReportCommand {

    /** The PDF creator of every file this command writes starts with this. */
    private static final String CREATOR_PREFIX = "idmtest";

    private ReportCommand() {
    }

    public static int run(CommandLine line, PrintStream out, PrintStream err) {
        Locale messages = Locale.ENGLISH;
        try {
            Locale override = null;
            if (line.locale() != null) {
                override = ReportLocale.resolve(line.locale());
                messages = override;
            }
            PdfReportWriter.Kind kind = PdfReportWriter.Kind.parse(line.pdf());
            RunFolder folder;
            try {
                folder = RunFolder.read(line.runFolder(), override, Locale.ENGLISH);
            } catch (RunFolder.Unreadable e) {
                err.println(Messages.render(Message.of("runner.report.noResults", line.runFolder()), messages));
                return ExitCodes.CANNOT_START;
            }
            Path target = line.out() != null ? line.out()
                    : line.runFolder().resolve(PdfReportWriter.fileName(folder.runId(), kind));
            if (Files.exists(target) && !isOurs(target)) {
                err.println(Messages.render(Message.of("runner.report.notOurs", target), messages));
                return ExitCodes.CANNOT_START;
            }
            byte[] pdf = PdfReportWriter.render(folder, kind, CREATOR_PREFIX + " " + IdmTest.version());
            write(target, pdf);
            out.println(Messages.render(Message.of("runner.report.written", target), messages));
            return ExitCodes.OK;
        } catch (ReportLocale.UnsupportedLocaleException e) {
            err.println(e.getMessage());
            return ExitCodes.CANNOT_START;
        } catch (RuntimeException e) {
            // an unexpected failure reading or rendering: one line, no stack trace, like the other refusals
            err.println(Messages.render(Message.of("runner.report.failed", String.valueOf(e.getMessage())), messages));
            return ExitCodes.CANNOT_START;
        }
    }

    /** True when {@code file} is a PDF whose creator says idmtest wrote it; anything else is foreign. */
    private static boolean isOurs(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            String creator = document.getDocumentInformation().getCreator();
            return creator != null && creator.startsWith(CREATOR_PREFIX);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Writes a temp file beside {@code target}, then moves it into place, so a reader never sees half a PDF. */
    private static void write(Path target, byte[] bytes) {
        Path temp = null;
        try {
            Path parent = target.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, ".idmtest-report-", ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    // the temp file is already moved or cannot be removed; nothing more to do
                }
            }
        }
    }
}
