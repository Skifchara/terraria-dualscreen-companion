package com.terraduo.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;

final class TerrariaMapView extends View {
    private static final int[] STEPS = {1, 2, 4, 8, 16};
    private final Paint paint = new Paint();
    private final RectF frame = new RectF();
    private final Runnable changed;
    private Bitmap bitmap;
    private boolean follow = true;
    private int zoom = 1;
    private int left;
    private int top;
    private int step;
    private int playerX;
    private int playerY;
    private int centerX;
    private int centerY;
    private float startX;
    private float startY;
    private float dragX;
    private float dragY;

    TerrariaMapView(Context context, Runnable changed) {
        super(context);
        this.changed = changed;
        setBackgroundColor(0xff0d141c);
        setContentDescription("Карта исследованных областей");
    }

    boolean hasMap() { return bitmap != null; }

    void clear() {
        bitmap = null;
        follow = true;
        invalidate();
    }

    void center() {
        follow = true;
        changed.run();
    }

    void zoom(int offset) {
        zoom = Math.max(0, Math.min(STEPS.length - 1, zoom + offset));
        changed.run();
    }

    void addViewport(JSONObject request) {
        TlProPanel.put(request, "step", STEPS[zoom]);
        if (!follow) {
            TlProPanel.put(request, "x", centerX);
            TlProPanel.put(request, "y", centerY);
        }
    }

    void update(JSONObject map, JSONArray position) {
        if (map == null || position == null) return;
        int width = map.optInt("width");
        int height = map.optInt("height");
        JSONArray pixels = map.optJSONArray("pixels");
        if (width < 1 || height < 1 || width > 256 || height > 256 ||
            pixels == null || pixels.length() != width * height) return;
        int[] colors = new int[pixels.length()];
        for (int index = 0; index < colors.length; index++) {
            int rgb = pixels.optInt(index);
            colors[index] = rgb == 0 ? 0xff0d141c : 0xff000000 | rgb;
        }
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        bitmap.setPixels(colors, 0, width, 0, 0, width, height);
        left = map.optInt("left");
        top = map.optInt("top");
        step = Math.max(1, map.optInt("step", 1));
        playerX = position.optInt(0);
        playerY = position.optInt(1);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) {
            paint.setColor(0xffb5c5d4);
            paint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Карта загружается", getWidth() / 2f, getHeight() / 2f, paint);
            return;
        }
        float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float x = (getWidth() - width) / 2 + dragX;
        float y = (getHeight() - height) / 2 + dragY;
        frame.set(x, y, x + width, y + height);
        paint.setFilterBitmap(false);
        canvas.drawBitmap(bitmap, null, frame, paint);
        float markerX = x + (playerX - left) * scale / step;
        float markerY = y + (playerY - top) * scale / step;
        if (frame.contains(markerX, markerY)) {
            paint.setAntiAlias(true);
            paint.setColor(0xff111927);
            canvas.drawCircle(markerX, markerY, 10, paint);
            paint.setColor(0xfff8d580);
            canvas.drawCircle(markerX, markerY, 7, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX();
                startY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                dragX = event.getX() - startX;
                dragY = event.getY() - startY;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                float scale = frame.width() / bitmap.getWidth();
                if (scale > 0 && Math.abs(dragX) + Math.abs(dragY) > 6) {
                    follow = false;
                    centerX = left + bitmap.getWidth() * step / 2 - Math.round(dragX * step / scale);
                    centerY = top + bitmap.getHeight() * step / 2 - Math.round(dragY * step / scale);
                    changed.run();
                }
                performClick();
                // Fall through to clear the drag offset.
            case MotionEvent.ACTION_CANCEL:
                dragX = 0;
                dragY = 0;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
