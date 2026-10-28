package io.github.yy7511.locallutbox.core;

import android.graphics.Bitmap;

/** Bitmap-level wrapper around the pure-Java {@link Pipeline}. */
public final class Renderer {

    private Renderer() {
    }

    public static Bitmap render(Bitmap src, Lut lut, Adjust adj, int maxDim) {
        return render(src, lut, adj, maxDim, ColorSpace.SRGB);
    }

    /**
     * @param srcSpace colour space of the decoded photo; the LUT target space is
     *                 taken from {@link Lut#space}, output is always sRGB.
     */
    public static Bitmap render(Bitmap src, Lut lut, Adjust adj, int maxDim, int srcSpace) {
        if (src == null || src.isRecycled()) {
            return null;
        }
        int max = Math.max(src.getWidth(), src.getHeight());
        Bitmap work = src;
        if (maxDim > 0 && max > maxDim) {
            float s = maxDim / (float) max;
            int w = Math.max(1, Math.round(src.getWidth() * s));
            int h = Math.max(1, Math.round(src.getHeight() * s));
            work = Bitmap.createScaledBitmap(src, w, h, true);
        }
        int w = work.getWidth();
        int h = work.getHeight();
        int[] px = new int[w * h];
        work.getPixels(px, 0, w, 0, 0, w, h);
        int dstSpace = lut == null ? ColorSpace.SRGB : ColorSpace.clampSpace(lut.space);
        if (lut != null || adj == null || !adj.isNeutral() || ColorSpace.clampSpace(srcSpace) != dstSpace) {
            Pipeline.apply(px, w, h, adj == null ? new Adjust() : adj, lut, srcSpace);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        if (work != src) {
            work.recycle();
        }
        return out;
    }

    public static Bitmap renderNeutral(Bitmap src, int maxDim) {
        return render(src, null, new Adjust(), maxDim);
    }
}
