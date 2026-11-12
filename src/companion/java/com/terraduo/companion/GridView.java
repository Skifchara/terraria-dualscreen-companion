package com.terraduo.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Stage-1 test view: 10x5 inventory grid (row 0 = hotbar drawn at the bottom). */
public class GridView extends View {
final MainActivity a;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF[] cells = new RectF[50];

    GridView(MainActivity act) {
        super(act);
        a = act;
        setBackgroundColor(0xFF101820);
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float cw = w / MainActivity.COLS, ch = Math.min(cw, (h - 90) / (MainActivity.ROWS + 0.5f));
        p.setTextAlign(Paint.Align.CENTER);
        int[][] snap = a.inv;
        for (int i = 0; i < 50; i++) {
            int r = i / MainActivity.COLS, c = i % MainActivity.COLS;
            // row 0 is the hotbar: it is drawn at the bottom, the other rows above it
            float y = (r == 0) ? 60 + (MainActivity.ROWS - 1) * ch + 8 : 60 + (r - 1) * ch;
            RectF rc = new RectF(c * cw + 2, y + 2, (c + 1) * cw - 2, y + ch - 2);
            cells[i] = rc;
            boolean on = (i == a.sel), from = (i == a.pendingFrom);
            p.setStyle(Paint.Style.FILL);
            p.setColor(from ? 0xFF6A4A00 : on ? 0xFF1F5FA8 : 0xFF23303C);
            cv.drawRoundRect(rc, 6, 6, p);
            if (snap[i][0] != 0) {
                p.setColor(0xFFFFFFFF);
                p.setTextSize(ch * 0.28f);
                cv.drawText("#" + snap[i][0], rc.centerX(), rc.centerY(), p);
                p.setTextSize(ch * 0.24f);
                p.setColor(0xFFFFD866);
                cv.drawText("x" + snap[i][1], rc.centerX(), rc.bottom - 6, p);
            }
        }
        p.setColor(a.dead ? 0xFFFF6060 : 0xFF9FB4C7);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(30);
        cv.drawText(a.status + (a.dead ? "  [DEAD]" : "") + (a.pendingFrom >= 0 ? "  move from " + a.pendingFrom + "?" : ""), 12, 40, p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
        for (int i = 0; i < 50; i++) {
            if (cells[i] != null && cells[i].contains(e.getX(), e.getY())) {
                a.tap(i);
                invalidate();
                break;
            }
        }
        return true;
    }
}
