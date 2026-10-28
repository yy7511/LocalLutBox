package io.github.yy7511.locallutbox.core;

import android.graphics.Bitmap;

/** Histogram + vectorscope sampling for the analysis panel. */
public final class Analysis {

    public static final int VEC_N = 144;
    public static final int HIST_SIZE = 256;

    private Analysis() {
    }

    public static int[] newHistogram() {
        return new int[HIST_SIZE * 4];
    }

    public static int[] newVecGrid() {
        return new int[VEC_N * VEC_N];
    }

    public static void compute(Bitmap bmp, int sampleMax,
                               int[] hist, int[] vecCount, int[] vecR, int[] vecG, int[] vecB) {
        if (bmp == null || bmp.isRecycled()) {
            return;
        }
        for (int i = 0; i < hist.length; i++) {
            hist[i] = 0;
        }
        for (int i = 0; i < vecCount.length; i++) {
            vecCount[i] = 0;
        }
        for (int i = 0; i < vecR.length; i++) {
            vecR[i] = 0;
            vecG[i] = 0;
            vecB[i] = 0;
        }

        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int max = Math.max(w, h);
        Bitmap small = bmp;
        if (max > sampleMax) {
            float s = sampleMax / (float) max;
            small = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * s)),
                    Math.max(1, Math.round(h * s)), true);
            w = small.getWidth();
            h = small.getHeight();
        }
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != bmp) {
            small.recycle();
        }

        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int l = (int) (0.2126f * r + 0.7152f * g + 0.0722f * b);

            hist[r]++;
            hist[HIST_SIZE + g]++;
            hist[HIST_SIZE * 2 + b]++;
            hist[HIST_SIZE * 3 + Math.min(255, l)]++;

            float y = 0.299f * r + 0.587f * g + 0.114f * b;
            float cb = (b - y) * 0.564f;
            float cr = (r - y) * 0.713f;
            int vx = (int) ((cb + 0.5f) * (VEC_N - 1));
            int vy = (int) ((0.5f - cr) * (VEC_N - 1));
            if (vx < 0 || vx >= VEC_N || vy < 0 || vy >= VEC_N) {
                continue;
            }
            int idx = vy * VEC_N + vx;
            vecCount[idx]++;
            vecR[idx] += r;
            vecG[idx] += g;
            vecB[idx] += b;
        }
    }
}
