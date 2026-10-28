package io.github.yy7511.locallutbox.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import io.github.yy7511.locallutbox.core.Analysis;

/** RGB histogram overlay. */
public class HistogramView extends View {

    private int[] hist = new int[Analysis.HIST_SIZE * 4];
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public HistogramView(Context c) {
        super(c);
    }

    public HistogramView(Context c, AttributeSet a) {
        super(c, a);
    }

    public HistogramView(Context c, AttributeSet a, int s) {
        super(c, a, s);
    }

    public void setHistogram(int[] h) {
        if (h != null && h.length == hist.length) {
            hist = h;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = Ui.dp(getContext(), 10);
        bgPaint.setColor(Ui.SCOPE_BG);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, bgPaint);

        gridPaint.setColor(Ui.SCOPE_GRID_SOFT);
        gridPaint.setStrokeWidth(1f);
        for (int i = 1; i <= 3; i++) {
            float x = w * i / 4f;
            canvas.drawLine(x, Ui.dp(getContext(), 5), x, h - Ui.dp(getContext(), 5), gridPaint);
        }
        canvas.drawLine(0, h * 0.5f, w, h * 0.5f, gridPaint);

        float max = 1f;
        for (int i = 0; i < hist.length; i++) {
            if (hist[i] > max) {
                max = hist[i];
            }
        }
        float logMax = (float) Math.log1p(max);

        drawChannel(canvas, 0, 0xFFE5605E, w, h, logMax);
        drawChannel(canvas, Analysis.HIST_SIZE, 0xFF6BCB77, w, h, logMax);
        drawChannel(canvas, Analysis.HIST_SIZE * 2, 0xFF5B9BD5, w, h, logMax);
    }

    private void drawChannel(Canvas canvas, int offset, int color, int w, int h, float logMax) {
        path.reset();
        path.moveTo(0, h);
        float top = Ui.dp(getContext(), 4);
        for (int i = 0; i < 256; i++) {
            float x = i / 255f * w;
            float v = (float) (Math.log1p(hist[offset + i]) / logMax);
            float y = h - top - v * (h - top * 2);
            path.lineTo(x, y);
        }
        path.lineTo(w, h);
        path.close();

        fillPaint.setColor(color);
        fillPaint.setAlpha(58);
        canvas.drawPath(path, fillPaint);

        strokePaint.setColor(color);
        strokePaint.setAlpha(170);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 0.7f)));
        canvas.drawPath(path, strokePaint);
    }
}
