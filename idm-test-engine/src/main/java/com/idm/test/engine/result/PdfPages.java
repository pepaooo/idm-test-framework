package com.idm.test.engine.result;

import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.Message;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lays text, tables, tiles and monospace blocks out on A4 pages with PDFBox.
 *
 * <p>Plain PDF kept PDF/A-ready: the three DejaVu fonts are embedded (subset), colours are opaque RGB,
 * and nothing uses transparency. A cursor {@code y} moves down the page; when the next line does not fit
 * above the bottom margin a new page starts. Footers and the running header are drawn in {@link #finish()},
 * once the page count is known.
 */
final class PdfPages implements AutoCloseable {

    /** How a table cell is drawn. */
    enum Style { NORMAL, BOLD, PASS, FAIL, BADGE, MUTED }

    /** One table cell. */
    record Cell(String text, Style style) {
    }

    private static final String FONT_DIR = "/com/idm/test/engine/result/fonts/";
    private static final float MARGIN = 42f;
    private static final float TOP = 52f;
    private static final float BOTTOM = 46f;
    private static final float PAD = 4f;

    private static final Color TEXT = new Color(0x1F, 0x29, 0x37);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color PASS = new Color(0x15, 0x80, 0x3D);
    private static final Color FAIL = new Color(0xB9, 0x1C, 0x1C);
    private static final Color RULE = new Color(0xD1, 0xD5, 0xDB);
    private static final Color TILE_FILL = new Color(0xF3, 0xF4, 0xF6);
    private static final Color BADGE_FILL = new Color(0x4B, 0x55, 0x63);

    private final PDDocument doc = new PDDocument();
    private final PDFont regular;
    private final PDFont bold;
    private final PDFont monoFont;
    private final String runningHeader;
    private final Locale locale;
    private final PDRectangle size = PDRectangle.A4;
    private final float contentWidth = size.getWidth() - 2 * MARGIN;

    private PDPageContentStream out;
    private float y;
    private boolean atTop;

    PdfPages(String runningHeader, Locale locale) {
        this.runningHeader = runningHeader;
        this.locale = locale;
        try {
            this.regular = load("DejaVuSans.ttf");
            this.bold = load("DejaVuSans-Bold.ttf");
            this.monoFont = load("DejaVuSansMono.ttf");
        } catch (IOException e) {
            close();
            throw new UncheckedIOException(e);
        }
        startPage();
    }

    private PDFont load(String name) throws IOException {
        try (InputStream in = PdfPages.class.getResourceAsStream(FONT_DIR + name)) {
            if (in == null) {
                throw new IOException("missing bundled font " + name);
            }
            return PDType0Font.load(doc, in, true);
        }
    }

    void info(String title, String creator) {
        PDDocumentInformation info = new PDDocumentInformation();
        info.setTitle(title);
        info.setCreator(creator);
        info.setCreationDate(Calendar.getInstance());
        doc.setDocumentInformation(info);
    }

    void title(String text) {
        textBlock(text, bold, 18f, 24f, TEXT, 4f);
    }

    void heading(String text) {
        keepTogether(40f);
        y -= 8f;
        textBlock(text, bold, 13f, 18f, TEXT, 3f);
    }

    void paragraph(String text) {
        textBlock(text, regular, 10f, 14f, TEXT, 4f);
    }

    void muted(String text) {
        textBlock(text, regular, 9f, 12f, MUTED, 3f);
    }

    void newPage() {
        startPage();
    }

    /** Starts a new page unless {@code height} points still fit on the current one. */
    void keepTogether(float height) {
        if (!atTop && y - height < BOTTOM) {
            startPage();
        }
    }

    void table(List<List<Cell>> rows, float... columnWidthFractions) {
        if (rows.isEmpty()) {
            return;
        }
        int columns = columnWidthFractions.length;
        float total = 0f;
        for (float f : columnWidthFractions) {
            total += f;
        }
        float[] widths = new float[columns];
        for (int i = 0; i < columns; i++) {
            widths[i] = contentWidth * columnWidthFractions[i] / total;
        }
        for (List<Cell> row : rows) {
            List<List<String>> wrapped = new ArrayList<>();
            int lines = 1;
            for (int i = 0; i < columns; i++) {
                Cell cell = i < row.size() ? row.get(i) : new Cell("", Style.NORMAL);
                List<String> l = wrap(cell.text(), fontFor(cell.style()), 9f, widths[i] - 2 * PAD);
                wrapped.add(l);
                lines = Math.max(lines, l.size());
            }
            float rowHeight = lines * 12f + 2 * PAD;
            keepTogether(rowHeight);
            float x = MARGIN;
            for (int i = 0; i < columns; i++) {
                Cell cell = i < row.size() ? row.get(i) : new Cell("", Style.NORMAL);
                drawCell(cell, wrapped.get(i), x, widths[i]);
                x += widths[i];
            }
            line(MARGIN, y - rowHeight, MARGIN + contentWidth, y - rowHeight);
            y -= rowHeight;
            atTop = false;
        }
        y -= 6f;
    }

    private void drawCell(Cell cell, List<String> lines, float x, float width) {
        PDFont font = fontFor(cell.style());
        float baseline = y - PAD - 9f;
        if (cell.style() == Style.BADGE) {
            String text = lines.isEmpty() ? "" : lines.get(0);
            float w = textWidth(font, text, 8f) + 8f;
            fill(BADGE_FILL, x + PAD, y - PAD - 11f, w, 13f);
            draw(text, font, 8f, Color.WHITE, x + PAD + 4f, y - PAD - 8f);
            return;
        }
        Color color = switch (cell.style()) {
            case PASS -> PASS;
            case FAIL -> FAIL;
            case MUTED -> MUTED;
            default -> TEXT;
        };
        for (String text : lines) {
            draw(text, font, 9f, color, x + PAD, baseline);
            baseline -= 12f;
        }
    }

    private PDFont fontFor(Style style) {
        return switch (style) {
            case BOLD, PASS, FAIL, BADGE -> bold;
            default -> regular;
        };
    }

    /** Boxes with a small label above a large value, up to four to a row. */
    void tiles(List<Map.Entry<String, String>> labelValue) {
        int perRow = 4;
        float gap = 8f;
        float tileWidth = (contentWidth - gap * (perRow - 1)) / perRow;
        float tileHeight = 44f;
        for (int i = 0; i < labelValue.size(); i += perRow) {
            keepTogether(tileHeight + gap);
            for (int j = 0; j < perRow && i + j < labelValue.size(); j++) {
                Map.Entry<String, String> e = labelValue.get(i + j);
                float x = MARGIN + j * (tileWidth + gap);
                fill(TILE_FILL, x, y - tileHeight, tileWidth, tileHeight);
                List<String> label = wrap(e.getKey(), regular, 8f, tileWidth - 2 * PAD);
                draw(label.isEmpty() ? "" : label.get(0), regular, 8f, MUTED, x + 6f, y - 14f);
                List<String> value = wrap(e.getValue(), bold, 14f, tileWidth - 2 * PAD);
                draw(value.isEmpty() ? "" : value.get(0), bold, 14f, TEXT, x + 6f, y - 34f);
            }
            y -= tileHeight + gap;
            atTop = false;
        }
    }

    /** A monospace block; it splits across pages line by line. */
    void mono(String text) {
        float fontSize = 8f;
        float leading = 10f;
        float maxWidth = contentWidth - 2 * PAD;
        for (String raw : clean(text).split("\n", -1)) {
            for (String piece : wrapChars(raw, monoFont, fontSize, maxWidth)) {
                keepTogether(leading);
                fill(TILE_FILL, MARGIN, y - leading, contentWidth, leading);
                draw(piece, monoFont, fontSize, TEXT, MARGIN + PAD, y - 7.5f);
                y -= leading;
                atTop = false;
            }
        }
        y -= 6f;
    }

    /** Draws the footers and the running header, saves, and returns the document bytes. */
    byte[] finish() {
        try {
            closeStream();
            int pages = doc.getNumberOfPages();
            String masked = Messages.render(Message.of("pdf.secretsMasked"), locale);
            for (int i = 0; i < pages; i++) {
                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, doc.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String pageLabel = sanitize(Messages.render(Message.of("pdf.page", i + 1, pages), locale), regular);
                    drawOn(cs, sanitize(masked, regular), regular, 8f, MUTED, MARGIN, 28f);
                    float w = textWidth(regular, pageLabel, 8f);
                    drawOn(cs, pageLabel, regular, 8f, MUTED, size.getWidth() - MARGIN - w, 28f);
                    if (i > 0) {
                        drawOn(cs, truncate(sanitize(runningHeader, regular), regular, 8f, contentWidth),
                                regular, 8f, MUTED, MARGIN, size.getHeight() - 30f);
                    }
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            doc.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            closeStream();
            doc.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- internals ----

    private void startPage() {
        try {
            closeStream();
            PDPage page = new PDPage(size);
            doc.addPage(page);
            out = new PDPageContentStream(doc, page);
            y = size.getHeight() - TOP;
            atTop = true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void closeStream() throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
    }

    private void textBlock(String text, PDFont font, float fontSize, float leading, Color color, float after) {
        for (String line : wrap(text, font, fontSize, contentWidth)) {
            keepTogether(leading);
            draw(line, font, fontSize, color, MARGIN, y - fontSize);
            y -= leading;
            atTop = false;
        }
        y -= after;
    }

    private void draw(String text, PDFont font, float fontSize, Color color, float x, float baseline) {
        try {
            drawOn(out, text, font, fontSize, color, x, baseline);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void drawOn(PDPageContentStream cs, String text, PDFont font, float fontSize, Color color,
                               float x, float baseline) throws IOException {
        if (text.isEmpty()) {
            return;
        }
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setNonStrokingColor(color);
        cs.newLineAtOffset(x, baseline);
        cs.showText(text);
        cs.endText();
    }

    private void fill(Color color, float x, float yBottom, float w, float h) {
        try {
            out.setNonStrokingColor(color);
            out.addRect(x, yBottom, w, h);
            out.fill();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void line(float x1, float y1, float x2, float y2) {
        try {
            out.setStrokingColor(RULE);
            out.setLineWidth(0.5f);
            out.moveTo(x1, y1);
            out.lineTo(x2, y2);
            out.stroke();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static float textWidth(PDFont font, String text, float fontSize) {
        try {
            return font.getStringWidth(text) / 1000f * fontSize;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Control characters out, tabs to spaces; a line break is kept as {@code \n}. */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
    }

    /** Replaces every code point the font cannot encode with {@code ?}. */
    private static String sanitize(String text, PDFont font) {
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            String s = new String(Character.toChars(cp));
            if (Character.isISOControl(cp)) {
                sb.append(' ');
                return;
            }
            try {
                font.encode(s);
                sb.append(s);
            } catch (IOException | IllegalArgumentException e) {
                sb.append('?');
            }
        });
        return sb.toString();
    }

    private static List<String> wrap(String text, PDFont font, float fontSize, float maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : clean(text).split("\n", -1)) {
            String safe = sanitize(paragraph, font);
            StringBuilder current = new StringBuilder();
            for (String word : safe.split(" ", -1)) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (textWidth(font, candidate, fontSize) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                List<String> pieces = wrapChars(word, font, fontSize, maxWidth);
                lines.addAll(pieces.subList(0, pieces.size() - 1));
                current.append(pieces.get(pieces.size() - 1));
            }
            lines.add(current.toString());
        }
        return lines;
    }

    /** Breaks one line at character boundaries, for words and monospace lines wider than the column. */
    private static List<String> wrapChars(String text, PDFont font, float fontSize, float maxWidth) {
        String safe = sanitize(text, font);
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int[] cps = safe.codePoints().toArray();
        for (int cp : cps) {
            String ch = new String(Character.toChars(cp));
            if (!current.isEmpty() && textWidth(font, current + ch, fontSize) > maxWidth) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            current.append(ch);
        }
        pieces.add(current.toString());
        return pieces;
    }

    private static String truncate(String text, PDFont font, float fontSize, float maxWidth) {
        if (textWidth(font, text, fontSize) <= maxWidth) {
            return text;
        }
        String s = text;
        while (!s.isEmpty() && textWidth(font, s + "…", fontSize) > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }
}
