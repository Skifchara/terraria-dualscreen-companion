package com.terraduo.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws text with the game's own bitmap font. The glyph sheets are decoded on the PC from the user's own
 * installation (tools/extract_assets.py) and pushed to this app's private folder; the glyph metrics come from the
 * running game through the TL Pro mod ("font" request) and are cached. Without them, plain Android text is used.
 */
final class GameFont {
    private static final class Glyph {
        int x, y, w, h, cropX, cropY, sheet;
        float left, right, width;
    }

    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Bitmap[] sheets = new Bitmap[8];
    private final Paint paint = new Paint();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final File metricsFile;
    private float lineSpacing = 24;
    private float spacing;
    private boolean ready;
    private int tintColor;

    GameFont(Context context) {
        File external = context.getExternalFilesDir(null);
        metricsFile = new File(context.getFilesDir(), "font_mouse.json");
        paint.setFilterBitmap(false);
        if (external != null) {
            for (int index = 0; index < sheets.length; index++) {
                File sheet = new File(external, "atlas/font_mouse_" + index + ".png");
                if (!sheet.isFile()) break;
                sheets[index] = BitmapFactory.decodeFile(sheet.getPath());
            }
        }
        loadMetrics();
    }

    boolean ready() { return ready; }

    boolean needsMetrics() { return !ready && sheets[0] != null; }

    /** Applies a response of the mod's {"op":"font","name":"mouse"} request and caches it. */
    void acceptMetrics(JSONObject response) {
        if (response.optJSONArray("glyphs") == null) return;
        try (FileOutputStream out = new FileOutputStream(metricsFile)) {
            out.write(response.toString().getBytes("UTF-8"));
        } catch (IOException ignored) {
            // metrics still apply for this run
        }
        parse(response);
    }

    private void loadMetrics() {
        if (!metricsFile.isFile() || sheets[0] == null) return;
        try (FileInputStream in = new FileInputStream(metricsFile)) {
            byte[] data = new byte[(int) metricsFile.length()];
            int read = 0;
            while (read < data.length) {
                int count = in.read(data, read, data.length - read);
                if (count < 0) break;
                read += count;
            }
            parse(new JSONObject(new String(data, 0, read, "UTF-8")));
        } catch (Exception ignored) {
            // re-requested from the game next time
        }
    }

    private void parse(JSONObject response) {
        JSONArray list = response.optJSONArray("glyphs");
        if (list == null || sheets[0] == null) return;
        glyphs.clear();
        lineSpacing = (float) response.optDouble("line", 24);
        spacing = (float) response.optDouble("spacing", 0);
        for (int index = 0; index < list.length(); index++) {
            JSONArray row = list.optJSONArray(index);
            if (row == null || row.length() < 11) continue;
            Glyph glyph = new Glyph();
            glyph.x = row.optInt(1);
            glyph.y = row.optInt(2);
            glyph.w = row.optInt(3);
            glyph.h = row.optInt(4);
            glyph.cropX = row.optInt(5);
            glyph.cropY = row.optInt(6);
            glyph.left = (float) row.optDouble(7);
            glyph.right = (float) row.optDouble(8);
            glyph.width = (float) row.optDouble(9);
            glyph.sheet = row.optInt(10);
            glyphs.put(row.optInt(0), glyph);
        }
        ready = !glyphs.isEmpty();
    }

    /** Width of {@code text} when its line height is {@code size} pixels. */
    float measure(String text, float size) {
        float scale = size / lineSpacing;
        float pen = 0;
        for (int index = 0; index < text.length(); index++) {
            Glyph glyph = glyphs.get((int) text.charAt(index));
            if (glyph == null) glyph = glyphs.get((int) '?');
            if (glyph == null) continue;
            pen += glyph.left + glyph.width + glyph.right + spacing;
        }
        return pen * scale;
    }

    /** Draws with the top-left corner at (x, y); returns the width drawn. */
    float draw(Canvas canvas, String text, float x, float y, float size, int color, boolean outline) {
        float scale = size / lineSpacing;
        if (outline) {
            int dark = 0xff000000;
            drawRun(canvas, text, x - scale, y, scale, dark);
            drawRun(canvas, text, x + scale, y, scale, dark);
            drawRun(canvas, text, x, y - scale, scale, dark);
            drawRun(canvas, text, x, y + scale, scale, dark);
        }
        return drawRun(canvas, text, x, y, scale, color);
    }

    private float drawRun(Canvas canvas, String text, float x, float y, float scale, int color) {
        if (tintColor != color || paint.getColorFilter() == null) {
            paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            tintColor = color;
        }
        float pen = 0;
        for (int index = 0; index < text.length(); index++) {
            Glyph glyph = glyphs.get((int) text.charAt(index));
            if (glyph == null) glyph = glyphs.get((int) '?');
            if (glyph == null) continue;
            pen += glyph.left;
            Bitmap sheet = glyph.sheet >= 0 && glyph.sheet < sheets.length ? sheets[glyph.sheet] : null;
            if (sheet != null && glyph.w > 0 && glyph.h > 0) {
                src.set(glyph.x, glyph.y, glyph.x + glyph.w, glyph.y + glyph.h);
                float left = x + (pen + glyph.cropX) * scale;
                float top = y + glyph.cropY * scale;
                dst.set(left, top, left + glyph.w * scale, top + glyph.h * scale);
                canvas.drawBitmap(sheet, src, dst, paint);
            }
            pen += glyph.width + glyph.right + spacing;
        }
        return pen * scale;
    }
}
