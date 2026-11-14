package io.github.swaidi.jadwal;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Draws the physician report onto A4 pages. Android's own text layout handles Arabic shaping
 * and right-to-left order, so the same code serves both languages.
 * Blocks: {t:"h1"|"h2"|"p"|"small"|"li"|"table", text, cols:[..], rows:[[..]], widths:[..]}
 */
final class ReportPdf {
    private static final int W = 595, H = 842, M = 40;
    private static final int CONTENT_W = W - 2 * M;

    private final boolean rtl;
    private final String pageLabel;
    private PdfDocument doc;
    private PdfDocument.Page page;
    private Canvas cv;
    private float y;
    private int pageNo = 0;

    private ReportPdf(boolean rtl, String pageLabel) {
        this.rtl = rtl;
        this.pageLabel = pageLabel;
    }

    static void write(JSONArray blocks, boolean rtl, String pageLabel, File out) throws Exception {
        ReportPdf r = new ReportPdf(rtl, pageLabel);
        r.doc = new PdfDocument();
        r.newPage();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b != null) r.block(b);
        }
        r.finishPage();
        try (FileOutputStream fo = new FileOutputStream(out)) { r.doc.writeTo(fo); }
        r.doc.close();
    }

    private void newPage() {
        if (page != null) finishPage();
        pageNo++;
        page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
        cv = page.getCanvas();
        y = M;
    }

    private void finishPage() {
        if (page == null) return;
        TextPaint p = paint(8, false, "#6B7B80");
        String label = pageLabel.replace("{n}", String.valueOf(pageNo));
        StaticLayout l = layout(label, p, CONTENT_W, Layout.Alignment.ALIGN_CENTER);
        cv.save();
        cv.translate(M, H - M + 10);
        l.draw(cv);
        cv.restore();
        doc.finishPage(page);
        page = null;
    }

    private TextPaint paint(float size, boolean bold, String color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(Color.parseColor(color));
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p;
    }

    private StaticLayout layout(CharSequence text, TextPaint p, int width, Layout.Alignment align) {
        if (Build.VERSION.SDK_INT >= 23) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(10, width))
                    .setAlignment(align)
                    .setTextDirection(rtl ? TextDirectionHeuristics.FIRSTSTRONG_RTL : TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setLineSpacing(1.5f, 1f)
                    .setIncludePad(false)
                    .build();
        }
        return new StaticLayout(text, p, Math.max(10, width), align, 1f, 1.5f, false);
    }

    private float room() { return H - M - 14 - y; }

    /** Draws a paragraph, continuing on the next page line by line if needed. */
    private void drawFlow(StaticLayout l, float x) {
        int line = 0;
        while (line < l.getLineCount()) {
            int top = l.getLineTop(line);
            int last = line;
            while (last + 1 < l.getLineCount() && l.getLineBottom(last + 1) - top <= room()) last++;
            if (l.getLineBottom(line) - top > room()) { newPage(); continue; }
            int bottom = l.getLineBottom(last);
            cv.save();
            cv.translate(x, y - top);
            cv.clipRect(0, top, l.getWidth(), bottom);
            l.draw(cv);
            cv.restore();
            y += bottom - top;
            line = last + 1;
            if (line < l.getLineCount()) newPage();
        }
    }

    private void block(JSONObject b) {
        String t = b.optString("t", "p");
        String text = b.optString("text", "");
        switch (t) {
            case "h1": {
                StaticLayout l = layout(text, paint(18, true, "#0F3D3B"), CONTENT_W, Layout.Alignment.ALIGN_NORMAL);
                if (room() < l.getHeight() + 30) newPage();
                drawFlow(l, M);
                y += 6;
                break;
            }
            case "h2": {
                y += 10;
                StaticLayout l = layout(text, paint(13, true, "#0F3D3B"), CONTENT_W, Layout.Alignment.ALIGN_NORMAL);
                if (room() < l.getHeight() + 40) newPage();
                drawFlow(l, M);
                Paint line = new Paint();
                line.setColor(Color.parseColor("#B8C7CA"));
                cv.drawRect(M, y + 2, W - M, y + 3, line);
                y += 8;
                break;
            }
            case "small": {
                drawFlow(layout(text, paint(8.5f, false, "#4A5D63"), CONTENT_W, Layout.Alignment.ALIGN_NORMAL), M);
                y += 4;
                break;
            }
            case "li": {
                int indent = 14;
                StaticLayout l = layout(text, paint(10, false, "#17242A"), CONTENT_W - indent, Layout.Alignment.ALIGN_NORMAL);
                if (room() < 14) newPage();
                TextPaint bp = paint(10, true, "#17242A");
                float bx = rtl ? W - M - 6 : M + 2;
                cv.drawText("•", bx, y + bp.getTextSize(), bp);
                drawFlow(l, rtl ? M : M + indent);
                y += 3;
                break;
            }
            case "table":
                table(b);
                break;
            default: {
                boolean bold = b.optBoolean("bold", false);
                drawFlow(layout(text, paint(10, bold, "#17242A"), CONTENT_W, Layout.Alignment.ALIGN_NORMAL), M);
                y += 5;
            }
        }
    }

    private void table(JSONObject b) {
        JSONArray cols = b.optJSONArray("cols");
        JSONArray rows = b.optJSONArray("rows");
        JSONArray widths = b.optJSONArray("widths");
        if (cols == null || rows == null) return;
        int n = cols.length();
        int[] w = new int[n];
        int used = 0;
        for (int i = 0; i < n; i++) {
            double f = widths != null ? widths.optDouble(i, 1.0 / n) : 1.0 / n;
            w[i] = (int) Math.floor(CONTENT_W * f);
            used += w[i];
        }
        w[n - 1] += CONTENT_W - used;
        y += 2;
        drawRow(cols, w, true);
        for (int r = 0; r < rows.length(); r++) {
            JSONArray row = rows.optJSONArray(r);
            if (row == null) continue;
            if (rowHeight(row, w, false) > room()) { newPage(); drawRow(cols, w, true); }
            drawRow(row, w, false);
        }
        y += 6;
    }

    private float rowHeight(JSONArray cells, int[] w, boolean header) {
        float h = 0;
        for (int i = 0; i < w.length; i++) {
            StaticLayout l = layout(cells.optString(i, ""), paint(9, header, "#17242A"), w[i] - 8, Layout.Alignment.ALIGN_NORMAL);
            h = Math.max(h, l.getHeight());
        }
        return h + 8;
    }

    private void drawRow(JSONArray cells, int[] w, boolean header) {
        float h = rowHeight(cells, w, header);
        if (h > room()) newPage();
        if (header) {
            Paint bg = new Paint();
            bg.setColor(Color.parseColor("#E4EEF0"));
            cv.drawRect(M, y, W - M, y + h, bg);
        }
        float x = rtl ? W - M : M;
        for (int i = 0; i < w.length; i++) {
            StaticLayout l = layout(cells.optString(i, ""), paint(9, header, "#17242A"), w[i] - 8, Layout.Alignment.ALIGN_NORMAL);
            float cx = rtl ? x - w[i] + 4 : x + 4;
            cv.save();
            cv.translate(cx, y + 4);
            l.draw(cv);
            cv.restore();
            x = rtl ? x - w[i] : x + w[i];
        }
        Paint line = new Paint();
        line.setColor(Color.parseColor("#D6E0E2"));
        cv.drawRect(M, y + h - 0.5f, W - M, y + h, line);
        y += h;
    }
}
