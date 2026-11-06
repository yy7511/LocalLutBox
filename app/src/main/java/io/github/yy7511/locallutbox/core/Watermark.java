package io.github.yy7511.locallutbox.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/** Draws a text watermark directly onto an exported bitmap. */
public final class Watermark {

    public static final int BOTTOM_RIGHT = 0;
    public static final int BOTTOM_LEFT = 1;
    public static final int TOP_RIGHT = 2;
    public static final int TOP_LEFT = 3;
    public static final int CENTER = 4;

    private Watermark() {
    }

    /**
     * Mutates {@code bmp} in place.
     *
     * @param position one of the constants above
     * @param opacity  percent (0..100)
     */
    public static void draw(Bitmap bmp, String text, int position, int opacity) {
        if (bmp == null || bmp.isRecycled() || text == null || text.trim().length() == 0) {
            return;
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        float size = Math.max(12f, Math.max(w, h) * 0.032f);
        p.setTextSize(size);
        p.setColor(Color.WHITE);
        p.setAlpha(Math.round(255 * Math.max(0, Math.min(100, opacity)) / 100f));
        p.setShadowLayer(Math.max(1f, size * 0.10f), 0f, Math.max(1f, size * 0.04f), 0xB3000000);

        float pad = Math.max(10f, size * 0.65f);
        float tw = p.measureText(text);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline;
        float x;
        switch (position) {
            case BOTTOM_LEFT:
                x = pad;
                baseline = h - pad - fm.descent;
                break;
            case TOP_RIGHT:
                x = w - pad - tw;
                baseline = pad - fm.ascent;
                break;
            case TOP_LEFT:
                x = pad;
                baseline = pad - fm.ascent;
                break;
            case CENTER:
                x = (w - tw) / 2f;
                baseline = (h - fm.ascent - fm.descent) / 2f;
                break;
            default:
                x = w - pad - tw;
                baseline = h - pad - fm.descent;
                break;
        }
        cv.drawText(text, Math.max(0f, x), Math.min(h, baseline), p);
    }
}
