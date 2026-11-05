package com.idm.test.engine.result;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PdfPages")
class PdfPagesTest {

    private static String text(byte[] pdf, int page) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(page);
            s.setEndPage(page);
            return s.getText(doc);
        }
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int pages(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    @DisplayName("a long paragraph wraps onto several lines and extracts whole")
    void wrapsLongParagraph() throws IOException {
        String words = "alpha beta gamma delta ".repeat(27).trim().substring(0, 600).trim();
        byte[] pdf;
        try (PdfPages p = new PdfPages("hdr", Locale.ENGLISH)) {
            p.paragraph(words);
            pdf = p.finish();
        }
        String extracted = text(pdf, 1);
        assertThat(extracted.replaceAll("\\s+", " ")).contains(words);
        assertThat(extracted.lines().filter(l -> l.contains("alpha")).count()).isGreaterThan(1);
    }

    @Test
    @DisplayName("a monospace block continues across pages and keeps its last line")
    void monospaceBlockContinuesAcrossPages() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("line-").append(i).append('\n');
        }
        byte[] pdf;
        try (PdfPages p = new PdfPages("hdr", Locale.ENGLISH)) {
            p.mono(sb.toString().trim());
            pdf = p.finish();
        }
        int n = pages(pdf);
        assertThat(n).isGreaterThan(1);
        assertThat(text(pdf, n)).contains("line-399");
    }

    @Test
    @DisplayName("footers number the pages, in the report language")
    void footerNumbersPages() throws IOException {
        byte[] en;
        try (PdfPages p = new PdfPages("hdr", Locale.ENGLISH)) {
            p.paragraph("one");
            p.newPage();
            p.paragraph("two");
            p.newPage();
            p.paragraph("three");
            en = p.finish();
        }
        assertThat(text(en)).contains("Page 1 of 3", "Page 3 of 3", "Secrets masked");
        byte[] es;
        try (PdfPages p = new PdfPages("hdr", Locale.forLanguageTag("es"))) {
            p.newPage();
            p.newPage();
            es = p.finish();
        }
        assertThat(text(es)).contains("Página 3 de 3", "Secretos enmascarados");
    }

    @Test
    @DisplayName("the running header shows after the first page only")
    void runningHeaderOnlyAfterFirstPage() throws IOException {
        byte[] pdf;
        try (PdfPages p = new PdfPages("RUNNING-HEADER", Locale.ENGLISH)) {
            p.paragraph("first");
            p.newPage();
            p.paragraph("second");
            pdf = p.finish();
        }
        assertThat(text(pdf, 1)).doesNotContain("RUNNING-HEADER");
        assertThat(text(pdf, 2)).contains("RUNNING-HEADER");
    }

    @Test
    @DisplayName("accents and symbols the font has extract unchanged")
    void nonAsciiExtracts() throws IOException {
        byte[] pdf;
        try (PdfPages p = new PdfPages("hdr", Locale.ENGLISH)) {
            p.paragraph("Pérez ñ ü ✔ ✘");
            pdf = p.finish();
        }
        assertThat(text(pdf, 1)).contains("Pérez ñ ü ✔ ✘");
    }

    @Test
    @DisplayName("a glyph the font lacks becomes a question mark instead of throwing")
    void unsupportedGlyphBecomesQuestionMark() throws IOException {
        byte[] pdf;
        try (PdfPages p = new PdfPages("hdr", Locale.ENGLISH)) {
            // DejaVu Sans has U+1F600 but not CJK; DejaVu Sans Mono has neither
            p.paragraph("ok \u6F22");
            p.mono("ok \uD83D\uDE00");
            pdf = p.finish();
        }
        assertThat(text(pdf, 1).lines().filter(l -> l.equals("ok ?")).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("every font is embedded and the document info is set")
    void fontsEmbeddedAndInfoSet() throws IOException {
        byte[] pdf;
        try (PdfPages p = new PdfPages("hdr", Locale.ENGLISH)) {
            p.info("My title", "idmtest 1.0");
            p.title("T");
            p.heading("H");
            p.paragraph("p");
            p.mono("m");
            pdf = p.finish();
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getDocumentInformation().getTitle()).isEqualTo("My title");
            assertThat(doc.getDocumentInformation().getCreator()).isEqualTo("idmtest 1.0");
            assertThat(doc.getDocumentInformation().getCreationDate()).isNotNull();
            int fonts = 0;
            for (var page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    assertThat(font.isEmbedded()).as(font.getName()).isTrue();
                    fonts++;
                }
            }
            assertThat(fonts).isGreaterThanOrEqualTo(3);
        }
    }
}
