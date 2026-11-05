package com.idm.test.runner;

import com.idm.test.engine.result.JsonResultWriter;
import com.idm.test.engine.result.ScenarioResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReportCommand")
class ReportCommandTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int report(String... args) {
        return IdmTest.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private static Path runFolder(Path dir) {
        Path folder = dir.resolve("run-1");
        try {
            Files.createDirectory(folder);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        ScenarioResult passed = new ScenarioResult("Create user", "create.yaml", true, 10, List.of(), List.of(),
                null, null, null, List.of(), false, List.of());
        JsonResultWriter.write(List.of(passed), folder.resolve("results.json"));
        return folder;
    }

    @Test
    @DisplayName("Should exit 2 with a one-line error, not a stack trace, when the results cannot be rendered")
    void unexpectedFailureIsOneLine(@TempDir Path dir) throws Exception {
        Path folder = dir.resolve("run-1");
        Files.createDirectory(folder);
        Files.writeString(folder.resolve("results.json"), "[null]");

        int code = report("report", folder.toString(), "--pdf", "summary");

        assertThat(code).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).startsWith("the report could not be written").doesNotContain("\tat ")
                .doesNotContain("Exception").hasLineCount(1);
        assertThat(out()).isEmpty();
    }

    @Test
    @DisplayName("Should write <runId>-summary.pdf into the run folder by default")
    void writesDefaultPath(@TempDir Path dir) throws Exception {
        Path folder = runFolder(dir);

        int code = report("report", folder.toString(), "--pdf", "summary");

        Path pdf = folder.resolve("run-1-summary.pdf");
        assertThat(code).isEqualTo(ExitCodes.OK);
        assertThat(Files.readAllBytes(pdf)).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        assertThat(out()).contains(pdf.toString());
        assertThat(err()).isEmpty();
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            assertThat(document.getDocumentInformation().getCreator()).isEqualTo("idmtest " + IdmTest.version());
        }
        try (var files = Files.list(folder)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("Should write to --out, creating its parent folders")
    void writesToOut(@TempDir Path dir) throws Exception {
        Path folder = runFolder(dir);
        Path target = dir.resolve("deep/er/evidence.pdf");

        int code = report("report", folder.toString(), "--pdf", "detailed", "--out", target.toString());

        assertThat(code).isEqualTo(ExitCodes.OK);
        assertThat(Files.readAllBytes(target)).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        assertThat(folder.resolve("run-1-detailed.pdf")).doesNotExist();
    }

    @Test
    @DisplayName("Should overwrite a PDF that idmtest wrote")
    void overwritesOwnPdf(@TempDir Path dir) {
        Path folder = runFolder(dir);

        assertThat(report("report", folder.toString(), "--pdf", "summary")).isEqualTo(ExitCodes.OK);
        assertThat(report("report", folder.toString(), "--pdf", "summary")).isEqualTo(ExitCodes.OK);
        assertThat(err()).isEmpty();
    }

    @Test
    @DisplayName("Should refuse to overwrite a file idmtest did not write, and name it")
    void refusesForeignFile(@TempDir Path dir) throws Exception {
        Path folder = runFolder(dir);
        Path foreign = dir.resolve("mine.pdf");
        Files.writeString(foreign, "not a pdf at all");

        int code = report("report", folder.toString(), "--pdf", "summary", "--out", foreign.toString());

        assertThat(code).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains(foreign.toString());
        assertThat(Files.readString(foreign)).isEqualTo("not a pdf at all");
    }

    @Test
    @DisplayName("Should refuse a real PDF whose creator is not idmtest")
    void refusesForeignPdf(@TempDir Path dir) throws Exception {
        Path folder = runFolder(dir);
        Path foreign = dir.resolve("other.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.getDocumentInformation().setCreator("Word");
            document.save(foreign.toFile());
        }
        byte[] before = Files.readAllBytes(foreign);

        int code = report("report", folder.toString(), "--pdf", "summary", "--out", foreign.toString());

        assertThat(code).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(Files.readAllBytes(foreign)).isEqualTo(before);
    }

    @Test
    @DisplayName("Should exit 2 with the noResults text when results.json is missing")
    void missingResults(@TempDir Path dir) {
        int code = report("report", dir.toString(), "--pdf", "summary");

        assertThat(code).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("cannot read results.json in").contains(dir.toString());
    }

    @Test
    @DisplayName("Should exit 2 on a usage error and list report in the help")
    void usageAndHelp(@TempDir Path dir) {
        assertThat(report("report", dir.toString())).isEqualTo(ExitCodes.CANNOT_START);
        assertThat(err()).contains("--pdf");
    }

    @Test
    @DisplayName("Should list report in the help")
    void helpListsReport() {
        assertThat(report("--help")).isEqualTo(ExitCodes.OK);
        assertThat(out()).contains("report <run-folder> --pdf");
    }
}
