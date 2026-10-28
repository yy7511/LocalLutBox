package io.github.yy7511.locallutbox.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import io.github.yy7511.locallutbox.core.Analysis;

/** Vectorscope (Cb/Cr) with graticule, drawn from a 144x144 accumulation grid. */
public class VectorscopeView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Bitmap dots;
    private boolean hasData;

    public VectorscopeView(Context c) {
        super(c);
    }

    public VectorscopeView(Context c, AttributeSet a) {
        super(c, a);
    }

    public VectorscopeView(Context c, AttributeSet a, int s) {
        super(c, a, s);
    }

    public void setData(int[] count, int[] sr, int[] sg, int[] sb) {
        if (count == null || count.length != Analysis.VEC_N * Analysis.VEC_N) {
            return;
        }
        int n = Analysis.VEC_N;
        if (dots == null || dots.getWidth() != n || dots.isRecycled()) {
            dots = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        }
        int[] px = new int[n * n];
        int max = 1;
        for (int i = 0; i < count.length; i++) {
            if (count[i] > max) {
                max = count[i];
            }
        }
        float logMax = (float) Math.log1p(max);
        boolean any = false;
        for (int i = 0; i < count.length; i++) {
            int c = count[i];
            if (c <= 0) {
                continue;
            }
            any = true;
            float t = (float) (Math.log1p(c) / logMax);
            int alpha = (int) (40 + t * 195);
            if (alpha > 240) {
                alpha = 240;
            }
            int rr = Math.min(255, sr[i] / c);
            int gg = Math.min(255, sg[i] / c);
            int bb = Math.min(255, sb[i] / c);
            px[i] = (alpha << 24) | (rr << 16) | (gg << 8) | bb;
        }
        dots.setPixels(px, 0, n, 0, 0, n, n);
        hasData = any;
        invalidate();
    }

    public void clear() {
        hasData = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float radius = Ui.dp(getContext(), 10);
        bgPaint.setColor(Ui.SCOPE_BG);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, radius, radius, bgPaint);

        float size = Math.min(w, h) - Ui.dp(getContext(), 8);
        float cx = w / 2f;
        float cy = h / 2f;
        float r = size / 2f;

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 0.7f)));
        gridPaint.setColor(Ui.SCOPE_GRID);
        canvas.drawCircle(cx, cy, r * 0.92f, gridPaint);
        canvas.drawCircle(cx, cy, r * 0.55f, gridPaint);
        gridPaint.setColor(Ui.SCOPE_GRID_SOFT);
        canvas.drawLine(cx - r, cy, cx + r, cy, gridPaint);
        canvas.drawLine(cx, cy - r, cx, cy + r, gridPaint);
        // Skin tone line (~33 degrees)
        gridPaint.setColor(Ui.SCOPE_SKIN);
        double ang = Math.toRadians(33);
        canvas.drawLine(cx, cy, (float) (cx + Math.cos(ang) * r), (float) (cy - Math.sin(ang) * r), gridPaint);

        if (hasData && dots != null) {
            float left = cx - r;
            float top = cy - r;
            rect.set(left, top, left + r * 2, top + r * 2);
            canvas.drawBitmap(dots, null, rect, dotPaint);
        }

        gridPaint.setColor(Ui.SCOPE_AXIS);
        gridPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, Math.max(1f, Ui.dp(getContext(), 1.2f)), gridPaint);
    }
}
